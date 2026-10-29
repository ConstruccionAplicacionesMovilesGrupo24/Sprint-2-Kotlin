package com.campusmeal.android.feature.recipes.data.usage

import com.campusmeal.android.core.outbox.OutboxAdmission
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.UsageFeature
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.recipes.domain.RecipeUsageRecorder
import com.campusmeal.android.feature.recipes.domain.UsageAdmission

/**
 * BQ3 → BQ6 bridge: one `FEATURE_USED { feature: RECIPES }` per Recipes visit, stored in the S3-09
 * outbox. [visitId] becomes the `clientEventId`, so admitting the same visit twice stores one event.
 */
class OutboxRecipeUsageRecorder(private val outbox: OutboxRepository) : RecipeUsageRecorder {

    override suspend fun admitRecipesUsed(scope: AccountSessionScope, visitId: String, occurredAtEpochMillis: Long): UsageAdmission =
        when (val admission = outbox.enqueueUsage(scope, UsageFeature.RECIPES, visitId, occurredAtEpochMillis)) {
            OutboxAdmission.Admitted -> UsageAdmission.Admitted
            is OutboxAdmission.Rejected -> UsageAdmission.Rejected(admission.reason)
        }
}
