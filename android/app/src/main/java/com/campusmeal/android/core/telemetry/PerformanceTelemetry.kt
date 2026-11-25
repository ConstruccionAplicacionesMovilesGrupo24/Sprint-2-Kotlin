package com.campusmeal.android.core.telemetry

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.outbox.ComparisonLoadOutcome
import com.campusmeal.android.core.outbox.DeviceInfo
import com.campusmeal.android.core.outbox.OutboxAdmission
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.PerformanceEvent
import com.campusmeal.android.core.outbox.PerformanceEventType
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BQ1 telemetry of this process (S4-10): app sessions, fatal crashes and comparison loads, admitted to the
 * durable outbox like any other event.
 *
 * **Session.** One per identified account session (account + generation) and process, from the moment a
 * screen of the app is visible. A process started only to deliver pending work is not a session: counting
 * it would lower the crash rate. Going to the background does not end it.
 *
 * **Crash.** The marker left by [FatalCrashHandler] is imported the next time the **same** account is
 * active and a screen is visible. Its session start is admitted again with its original id, so a crash
 * that happened before the start reached Room still has its session. The marker is deleted only after both
 * events are durable.
 */
class PerformanceTelemetry(
    private val outbox: OutboxRepository,
    private val coordinator: AccountSessionCoordinator,
    private val markers: CrashMarkerStore,
    private val crashHandler: FatalCrashHandler,
    private val backgroundScope: CoroutineScope,
    private val device: DeviceInfo = currentDevice(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val visible = MutableStateFlow(false)

    @Volatile
    private var monitored: Pair<AccountSessionScope, MonitoredSession>? = null

    /** Observes the first visible screen without touching the session of a background-only process. */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    visible.value = true
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        backgroundScope.launch {
            visible.first { it }
            coordinator.sessionState.collect { state -> onScope(state.scope) }
        }
    }

    /** One finished comparison load. Fire and forget: the screen never waits for telemetry. */
    fun recordLoad(
        scope: AccountSessionScope,
        loadId: String,
        outcome: ComparisonLoadOutcome,
        durationMs: Long?,
        startedAtEpochMillis: Long,
    ) {
        val event = PerformanceEvent(
            clientEventId = UUID.randomUUID().toString(),
            type = PerformanceEventType.COMPARISON_LOAD,
            device = device,
            occurredAtEpochMillis = startedAtEpochMillis,
            sessionId = monitored?.takeIf { it.first == scope }?.second?.sessionId,
            loadId = loadId,
            screen = SCREEN_COMPARISON,
            durationMs = durationMs?.coerceIn(0, MAX_DURATION_MS),
            outcome = outcome,
        )
        backgroundScope.launch { admit(scope, event) }
    }

    fun onComparisonVisible(isVisible: Boolean) {
        crashHandler.screen = if (isVisible) SCREEN_COMPARISON else null
    }

    private suspend fun onScope(scope: AccountSessionScope?) {
        if (scope == null) {
            // Nobody is signed in: a crash now belongs to no account.
            monitored = null
            crashHandler.session = null
            return
        }
        if (monitored?.first == scope) return
        val session = MonitoredSession(
            accountId = scope.accountId,
            sessionId = UUID.randomUUID().toString(),
            startEventId = UUID.randomUUID().toString(),
            startedAtEpochMillis = clock(),
            crashEventId = UUID.randomUUID().toString(),
            device = device,
        )
        monitored = scope to session
        // Before any suspension: a crash right after this point is already attributable.
        crashHandler.session = session
        admit(scope, sessionStart(session.startEventId, session.sessionId, session.startedAtEpochMillis, device))
        importMarkers(scope)
    }

    private suspend fun importMarkers(scope: AccountSessionScope) {
        val found = withContext(Dispatchers.IO) { markers.read(scope.accountId) }
        for (marker in found) {
            val start = sessionStart(marker.sessionStartEventId, marker.sessionId, marker.sessionStartedAtEpochMillis, marker.device)
            val crash = PerformanceEvent(
                clientEventId = marker.crashEventId,
                type = PerformanceEventType.FATAL_CRASH,
                device = marker.device,
                occurredAtEpochMillis = marker.crashedAtEpochMillis,
                sessionId = marker.sessionId,
                screen = marker.screen,
                exceptionClass = marker.exceptionClass,
                injected = marker.injected,
            )
            // Not admitted (session changed, outbox full): the marker waits for the next activation.
            if (!admit(scope, start) || !admit(scope, crash)) return
            withContext(Dispatchers.IO) { markers.delete(marker) }
        }
    }

    private fun sessionStart(eventId: String, sessionId: String, startedAt: Long, device: DeviceInfo) = PerformanceEvent(
        clientEventId = eventId,
        type = PerformanceEventType.SESSION_START,
        device = device,
        occurredAtEpochMillis = startedAt,
        sessionId = sessionId,
    )

    private suspend fun admit(scope: AccountSessionScope, event: PerformanceEvent): Boolean {
        val admission = outbox.enqueuePerformance(scope, event)
        // Type and outcome only.
        if (BuildConfig.DEBUG) Log.d(TAG, "${event.type} ${event.outcome ?: ""} ${event.durationMs ?: ""} $admission")
        return admission == OutboxAdmission.Admitted
    }

    companion object {
        private const val TAG = "CampusMealTelemetry"
        private const val SCREEN_COMPARISON = "MEAL_COMPARISON"
        private const val MAX_DURATION_MS = 600_000L

        private fun currentDevice() = DeviceInfo(
            deviceModel = printable("${Build.MANUFACTURER} ${Build.MODEL}", 80),
            osVersion = printable("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", 40),
            appVersion = printable(BuildConfig.VERSION_NAME, 40),
        )

        /** The backend refuses control characters and surrounding blanks. */
        private fun printable(value: String, maxLength: Int): String =
            value.filterNot { it.isISOControl() }.trim().take(maxLength).trim().ifEmpty { "unknown" }
    }
}
