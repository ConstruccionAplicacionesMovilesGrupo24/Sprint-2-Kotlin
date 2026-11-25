package com.campusmeal.android.feature.decision.presentation

import com.campusmeal.android.core.outbox.ComparisonLoadOutcome
import java.util.UUID

/** A finished load: exactly one is reported per [loadId]. */
data class FinishedLoad(
    val loadId: String,
    val outcome: ComparisonLoadOutcome,
    val durationMs: Long,
    val startedAtEpochMillis: Long,
)

/**
 * Times one comparison load at a time, from the explicit request to the first frame that shows its content
 * (BQ1). Durations use a monotonic clock, so changing the phone's time cannot produce a negative or huge
 * one. Main thread only.
 *
 * Content outcomes are only finished by [rendered]; errors are terminal when they arrive; a load that is
 * replaced, or whose screen is left first, is CANCELLED. After any of them the load is closed: whatever
 * arrives later for the same id is ignored.
 */
class ComparisonLoadTimer(
    private val monotonicMillis: () -> Long,
    private val wallClockMillis: () -> Long,
    private val onFinished: (FinishedLoad) -> Unit,
) {

    private class Active(val loadId: String, val startedMonotonic: Long, val startedAt: Long, var content: ComparisonLoadOutcome? = null)

    private var active: Active? = null

    /** A new explicit request. The previous one, if still open, was superseded. */
    fun start(): String {
        cancel()
        return UUID.randomUUID().toString().also { active = Active(it, monotonicMillis(), wallClockMillis()) }
    }

    /** The answer is ready to be drawn; the load ends when it is. */
    fun contentReady(loadId: String, outcome: ComparisonLoadOutcome) {
        current(loadId)?.content = outcome
    }

    /** The content of [loadId] was drawn for the first time. */
    fun rendered(loadId: String) {
        current(loadId)?.content?.let(::finish)
    }

    /** The load ended without content. */
    fun failed(loadId: String, outcome: ComparisonLoadOutcome) {
        if (current(loadId) != null) finish(outcome)
    }

    fun cancel() {
        if (active != null) finish(ComparisonLoadOutcome.CANCELLED)
    }

    /** No request was made, or its session ended: there is nothing to report. */
    fun discard(loadId: String) {
        if (current(loadId) != null) active = null
    }

    private fun current(loadId: String) = active?.takeIf { it.loadId == loadId }

    private fun finish(outcome: ComparisonLoadOutcome) {
        val load = active ?: return
        active = null
        onFinished(FinishedLoad(load.loadId, outcome, monotonicMillis() - load.startedMonotonic, load.startedAt))
    }
}
