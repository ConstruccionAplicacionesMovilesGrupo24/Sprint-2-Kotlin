package com.campusmeal.android.feature.decision.domain

import com.campusmeal.android.feature.decision.data.repository.MealDecisionRepository
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import com.campusmeal.android.feature.decision.domain.model.MealDecisionError
import com.campusmeal.android.feature.decision.domain.model.MealDecisionResult

/**
 * The BQ5 entry point for the ViewModel: it validates the context, stamps the request time and
 * delegates the comparison. It deliberately holds no scoring rule of its own — ordering, scores and
 * the recommended alternative all come from the backend.
 */
class CompareMealOptionsUseCase(
    private val repository: MealDecisionRepository,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {

    suspend operator fun invoke(context: DecisionContext): MealDecisionResult {
        if (context.availableMinutes <= 0 || context.maximumBudget < 0) {
            return MealDecisionResult.Failure(MealDecisionError.InvalidContext)
        }
        return repository.compare(context, currentTimeMillis())
    }
}
