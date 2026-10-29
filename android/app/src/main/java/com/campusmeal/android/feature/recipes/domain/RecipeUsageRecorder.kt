package com.campusmeal.android.feature.recipes.domain

import com.campusmeal.android.core.session.AccountSessionScope

/** Result of offering one `FEATURE_USED { feature: RECIPES }` interaction to the durable outbox. */
sealed interface UsageAdmission {
    /** Durably admitted by the S3-09 outbox; it owns delivery from here. */
    data object Admitted : UsageAdmission

    /** The outbox refused it (capacity, stale scope…); nothing was stored. */
    data class Rejected(val reason: String) : UsageAdmission

    /** No durable outbox exists in this build: the interaction was **not** recorded anywhere. */
    data object NotIntegrated : UsageAdmission
}

/**
 * Admission point for the BQ3 usage event (plan §4.2: once per Recipes screen visit, when its main content
 * is shown after an explicit entry, offline included). S3-09 (Daniel) provides the outbox; until then the
 * only implementation is [OutboxNotIntegratedUsageRecorder]. [visitId] is a UUID v4 created once per
 * visit, for use as the event's `clientEventId`; [occurredAtEpochMillis] is the real UTC instant.
 */
fun interface RecipeUsageRecorder {
    suspend fun admitRecipesUsed(scope: AccountSessionScope, visitId: String, occurredAtEpochMillis: Long): UsageAdmission
}

/**
 * Explicitly not durable: S3-09's outbox is absent from this checkout. It stores, queues and sends
 * nothing, and says so, so no caller can mistake it for delivery. Replace it in `AppContainer` with an
 * adapter over the outbox's admission API when S3-09 merges.
 */
object OutboxNotIntegratedUsageRecorder : RecipeUsageRecorder {
    override suspend fun admitRecipesUsed(scope: AccountSessionScope, visitId: String, occurredAtEpochMillis: Long): UsageAdmission =
        UsageAdmission.NotIntegrated
}
