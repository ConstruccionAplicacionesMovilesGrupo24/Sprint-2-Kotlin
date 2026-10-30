package com.campusmeal.android.feature.recipes.domain

import com.campusmeal.android.core.session.AccountSessionScope

/** Result of offering one `FEATURE_USED { feature: RECIPES }` interaction to the durable outbox. */
sealed interface UsageAdmission {
    /** Durably stored by the S3-09 outbox, which owns delivery from here. */
    data object Admitted : UsageAdmission

    /** The outbox refused it (session changed, outbox full); nothing was stored. */
    data class Rejected(val reason: String) : UsageAdmission
}

/**
 * Admission point for the BQ3 usage event (plan §4.2: once per Recipes screen visit, when its main content
 * is shown after an explicit entry, offline included). Bound in `AppContainer` to
 * `OutboxRecipeUsageRecorder`, the adapter over the S3-09 outbox. [visitId] is a UUID v4 created once per
 * visit and used as the event's `clientEventId`; [occurredAtEpochMillis] is the real UTC instant.
 */
fun interface RecipeUsageRecorder {
    suspend fun admitRecipesUsed(scope: AccountSessionScope, visitId: String, occurredAtEpochMillis: Long): UsageAdmission
}
