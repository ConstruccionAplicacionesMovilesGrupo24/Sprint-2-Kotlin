package com.campusmeal.android.core.telemetry

import com.campusmeal.android.core.outbox.DeviceInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown on purpose by the debug build's crash button: its session is never real usage. */
class InjectedCrashException : RuntimeException("Injected by the debug crash button")

/** The app session a crash would end. Everything the marker needs is already here before the crash. */
data class MonitoredSession(
    val accountId: String,
    val sessionId: String,
    val startEventId: String,
    val startedAtEpochMillis: Long,
    /** Generated with the session: creating one needs no work (or memory) inside the crashing thread. */
    val crashEventId: String,
    val device: DeviceInfo,
)

/**
 * Records a minimal marker of a fatal JVM exception and lets the crash continue: the previous handler
 * (Android's own) always runs, so the process ends exactly as before.
 *
 * It does not see native crashes, ANRs, force-stops or system kills, and it records nothing while no
 * account session is active.
 */
class FatalCrashHandler(
    private val store: CrashMarkerStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {

    @Volatile
    var session: MonitoredSession? = null

    /** Coarse name of the screen on top, when known. */
    @Volatile
    var screen: String? = null

    private val handled = AtomicBoolean(false)
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install() {
        store.prepare()
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val active = session
            // A second thread crashing meanwhile must not write again.
            if (active != null && handled.compareAndSet(false, true)) {
                store.write(
                    CrashMarker(
                        accountId = active.accountId,
                        sessionId = active.sessionId,
                        sessionStartEventId = active.startEventId,
                        sessionStartedAtEpochMillis = active.startedAtEpochMillis,
                        crashEventId = active.crashEventId,
                        crashedAtEpochMillis = clock(),
                        device = active.device,
                        screen = screen,
                        exceptionClass = error.javaClass.name.take(MAX_CLASS_NAME),
                        injected = error.isInjected(),
                    ),
                )
            }
        } catch (_: Throwable) {
            // Out of memory or disk full: losing this marker is better than hiding the original crash.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    private fun Throwable.isInjected(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (current is InjectedCrashException) return true
            current = current.cause
            depth++
        }
        return false
    }

    private companion object {
        const val MAX_CLASS_NAME = 200
        const val MAX_CAUSES = 8
    }
}
