package com.campusmeal.android.feature.decision.domain.model

import com.campusmeal.android.feature.context.domain.MealLocation

/** Diet filters the comparison request can carry. Names match the backend enum values. */
enum class DietaryPreference { VEGETARIAN, VEGAN, GLUTEN_FREE }

/** What the user entered plus where they are. The request time is added by the use case. */
data class DecisionContext(
    val location: MealLocation,
    val availableMinutes: Int,
    val maximumBudget: Long,
    val dietaryPreferences: Set<DietaryPreference>,
    val includeDelivery: Boolean,
)

/**
 * A BQ5 answer as ranked by the backend. [alternatives] keeps the backend order and every score
 * untouched: Android never re-sorts, re-scores or picks a recommendation of its own.
 */
data class MealDecision(
    val recommendationId: String,
    val alternatives: List<MealAlternative>,
    val mainExplanation: String,
    val supportingReasons: List<String>,
) {
    /** The alternative the backend marked as recommended, or null if it could not be shown. */
    val recommended: MealAlternative? get() = alternatives.firstOrNull { it.recommended }
}

/** Fields every alternative shares. Cost is in whole Colombian pesos. */
data class MealAlternative(
    val rank: Int,
    val score: Double,
    val recommended: Boolean,
    val estimatedMinutes: Int,
    val estimatedCost: Long,
    val option: MealOption,
)

/** The Cook, Walk and Order specifics. */
sealed interface MealOption {

    /** [expiringIngredients] may be empty when the recipe uses nothing that expires soon. */
    data class Cook(val expiringIngredients: List<ExpiringIngredient>) : MealOption

    data class Walk(val restaurant: Restaurant, val walkingMinutes: Int) : MealOption

    /** [deliveryFee] is the additional cost on top of the meal. */
    data class Order(val restaurant: Restaurant, val deliveryMinutes: Int, val deliveryFee: Long) : MealOption
}

data class ExpiringIngredient(val itemId: String, val name: String, val remainingDays: Int)

data class Restaurant(val id: String, val name: String)

/** Outcome of a comparison, one case per non-loading UI state. */
sealed interface MealDecisionResult {

    data class Content(val decision: MealDecision) : MealDecisionResult

    /**
     * Some alternatives were incomplete or of an unsupported type and were left out.
     * [decision] still holds the valid ones in backend order.
     */
    data class PartialContent(val decision: MealDecision, val skippedAlternatives: Int) : MealDecisionResult

    data object NoAvailableAlternatives : MealDecisionResult

    /** There is no session, or the backend answered HTTP 401. */
    data object Unauthorized : MealDecisionResult

    data class Failure(val error: MealDecisionError) : MealDecisionResult
}

sealed interface MealDecisionError {

    /** Available time must be greater than zero and the budget non-negative. No request is sent. */
    data object InvalidContext : MealDecisionError

    /** Connectivity failure or HTTP 5xx. Null [httpCode] means no HTTP response. */
    data class BackendUnavailable(val httpCode: Int?) : MealDecisionError

    /** Any other non-2xx status except 401. */
    data class Http(val code: Int) : MealDecisionError

    /** The response broke the contract, or none of its alternatives could be shown. */
    data object InvalidResponse : MealDecisionError
}
