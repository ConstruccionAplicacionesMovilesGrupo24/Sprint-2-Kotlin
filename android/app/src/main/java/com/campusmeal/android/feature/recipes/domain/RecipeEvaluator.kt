package com.campusmeal.android.feature.recipes.domain

import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.recipes.domain.model.Recipe
import com.campusmeal.android.feature.recipes.domain.model.RecipeIngredient
import com.campusmeal.android.feature.recipes.domain.model.UnitDimension
import java.text.Normalizer
import java.util.Locale

/** How one recipe ingredient relates to the usable inventory. */
enum class IngredientStatus {
    /** Enough comparable quantity (required or optional). */
    AVAILABLE,

    /** Comparable rows exist but their total is below the required amount. */
    INSUFFICIENT,

    /** Rows with a matching name exist, but none has a unit that converts to the ingredient's unit. */
    UNIT_NOT_COMPARABLE,

    /** No usable row has a matching name. */
    MISSING,
}

/**
 * [availableBase] and [requiredBase] are in the dimension's base unit (g, ml or unit). [earliestExpiration]
 * is the earliest date among the usable comparable rows that matched.
 */
data class IngredientCheck(
    val ingredient: RecipeIngredient,
    val status: IngredientStatus,
    val availableBase: Double,
    val requiredBase: Double,
    val baseUnit: String,
    val matchedItemNames: List<String>,
    val earliestExpiration: String?,
)

data class RecipeEvaluation(
    val recipe: Recipe,
    val ingredients: List<IngredientCheck>,
    /** The recipe's tags include every selected preference (always true with no selection). */
    val dietOk: Boolean,
    val timeOk: Boolean,
    /** Earliest expiration among usable comparable rows matching a **required** ingredient. */
    val priorityDate: String?,
) {
    val requiredIngredientsOk: Boolean
        get() = ingredients.none { !it.ingredient.optional && it.status != IngredientStatus.AVAILABLE }

    val matches: Boolean get() = requiredIngredientsOk && dietOk && timeOk
}

/** Why recipes did not match, for a short no-match explanation. A recipe can count in several. */
data class NoMatchSummary(
    val missingIngredient: Int,
    val insufficientQuantity: Int,
    val unitNotComparable: Int,
    val dietMismatch: Int,
    val tooLong: Int,
)

data class Bq3Result(
    /** Matching recipe ids in the deterministic BQ3 order. */
    val matchIds: List<String>,
    /** Every recipe's evaluation, for explanations and the detail screen. */
    val evaluations: Map<String, RecipeEvaluation>,
    val summary: NoMatchSummary,
)

/**
 * BQ3: which recipes the current user can cook with the available ingredients, dietary preferences and
 * maximum preparation time (Sprint 3 plan §4.1, rules shared with CampusMealBack `docs/SPRINT3_BQ3.md`).
 *
 * Pure and deterministic: no Android, Room, network or clock. [todayIsoDate] is today's Bogotá date
 * supplied by the caller. [checkCancelled] is called inside the loops so abandoned work stops early.
 *
 * [inventory] must be the **complete** inventory (`GET inventory`, S3-03), whose domain rows are active by
 * contract (inactive rows are dropped by `toCompleteInventoryOrNull`). Rows that are expired, have a
 * non-positive or non-finite quantity, or an invalid date are not usable. Recipes are evaluated
 * independently: a match never consumes quantities needed by another recipe.
 */
fun evaluateBq3(
    recipes: List<Recipe>,
    inventory: List<InventoryItem>,
    selectedDiet: Set<DietaryPreference>,
    maxPreparationMinutes: Int,
    todayIsoDate: String,
    checkCancelled: () -> Unit = {},
): Bq3Result {
    val usable = inventory.mapNotNull { item ->
        checkCancelled()
        val days = BogotaCalendar.daysBetween(todayIsoDate, item.expirationDate)
        if (days == null || days < 0 || !item.quantity.isFinite() || item.quantity <= 0.0) {
            null
        } else {
            UsableRow(item, normalizeIngredientName(item.name), normalizeInventoryUnit(item.unit))
        }
    }

    val evaluations = recipes.map { recipe ->
        checkCancelled()
        evaluateRecipe(recipe, usable, selectedDiet, maxPreparationMinutes, checkCancelled)
    }

    val matchIds = evaluations
        .filter { it.matches }
        .sortedWith(
            // Missing dates sort last; names and ids compare by code point, never by device locale.
            compareBy<RecipeEvaluation, String?>(nullsLast()) { it.priorityDate }
                .thenBy { it.recipe.preparationMinutes }
                .thenBy { it.recipe.name }
                .thenBy { it.recipe.id },
        )
        .map { it.recipe.id }

    val nonMatching = evaluations.filterNot { it.matches }
    val summary = NoMatchSummary(
        missingIngredient = nonMatching.count { e -> e.requiredChecks().any { it.status == IngredientStatus.MISSING } },
        insufficientQuantity = nonMatching.count { e -> e.requiredChecks().any { it.status == IngredientStatus.INSUFFICIENT } },
        unitNotComparable = nonMatching.count { e -> e.requiredChecks().any { it.status == IngredientStatus.UNIT_NOT_COMPARABLE } },
        dietMismatch = nonMatching.count { !it.dietOk },
        tooLong = nonMatching.count { !it.timeOk },
    )
    return Bq3Result(matchIds, evaluations.associateBy { it.recipe.id }, summary)
}

private fun RecipeEvaluation.requiredChecks() = ingredients.filterNot { it.ingredient.optional }

private fun evaluateRecipe(
    recipe: Recipe,
    usable: List<UsableRow>,
    selectedDiet: Set<DietaryPreference>,
    maxPreparationMinutes: Int,
    checkCancelled: () -> Unit,
): RecipeEvaluation {
    val checks = recipe.ingredients.map { ingredient ->
        checkCancelled()
        checkIngredient(ingredient, usable)
    }
    val priorityDate = checks
        .filter { !it.ingredient.optional }
        .mapNotNull { it.earliestExpiration }
        .minOrNull()
    return RecipeEvaluation(
        recipe = recipe,
        ingredients = checks,
        dietOk = recipe.dietaryTags.containsAll(selectedDiet),
        timeOk = recipe.preparationMinutes <= maxPreparationMinutes,
        priorityDate = priorityDate,
    )
}

private fun checkIngredient(ingredient: RecipeIngredient, usable: List<UsableRow>): IngredientCheck {
    val keys = ingredient.matchingKeys
    val named = usable.filter { it.normalizedName in keys }
    val dimension = ingredient.unit.dimension
    // Only rows whose unit converts within the ingredient's dimension contribute; others never subtract.
    val comparable = named.filter { it.unit?.dimension == dimension }
    val available = comparable.sumOf { it.item.quantity * checkNotNull(it.unit).toBase }
    val required = ingredient.quantity * ingredient.unit.toBase
    val status = when {
        named.isEmpty() -> IngredientStatus.MISSING
        comparable.isEmpty() -> IngredientStatus.UNIT_NOT_COMPARABLE
        // Small tolerance for conversions such as 0.15 kg -> 150 g in floating point.
        available.isFinite() && available + QUANTITY_TOLERANCE >= required -> IngredientStatus.AVAILABLE
        else -> IngredientStatus.INSUFFICIENT
    }
    return IngredientCheck(
        ingredient = ingredient,
        status = status,
        availableBase = available,
        requiredBase = required,
        baseUnit = dimension.baseSymbol,
        matchedItemNames = comparable.map { it.item.name },
        earliestExpiration = comparable.minOfOrNull { it.item.expirationDate },
    )
}

private const val QUANTITY_TOLERANCE = 1e-9

private data class UsableRow(val item: InventoryItem, val normalizedName: String, val unit: InventoryUnit?)

/** An inventory unit after the fixed alias table, with the factor to its dimension's base unit. */
internal data class InventoryUnit(val dimension: UnitDimension, val toBase: Double)

private val INVENTORY_UNIT_ALIASES: Map<String, InventoryUnit> = buildMap {
    val gram = InventoryUnit(UnitDimension.MASS, 1.0)
    val kilo = InventoryUnit(UnitDimension.MASS, 1000.0)
    val milli = InventoryUnit(UnitDimension.VOLUME, 1.0)
    val litre = InventoryUnit(UnitDimension.VOLUME, 1000.0)
    val unit = InventoryUnit(UnitDimension.COUNT, 1.0)
    listOf("g", "gr", "gram", "grams").forEach { put(it, gram) }
    listOf("kg", "kilo", "kilos").forEach { put(it, kilo) }
    put("ml", milli)
    listOf("l", "lt", "liter", "litre", "liters").forEach { put(it, litre) }
    listOf("unit", "units", "u", "und", "unidad", "unidades").forEach { put(it, unit) }
}

/** Lowercase and trim, then the fixed alias table. Unknown units (cups, loaf…) return null. */
internal fun normalizeInventoryUnit(unit: String): InventoryUnit? =
    INVENTORY_UNIT_ALIASES[unit.trim().lowercase(Locale.ROOT)]

private val COMBINING_MARKS = Regex("[̀-ͯ]")
private val WHITESPACE = Regex("\\s+")

/**
 * The BQ3 name normalization shared with the backend catalog (`normalizeIngredientName`): NFD, remove
 * combining accents, lowercase with a fixed locale, trim and collapse whitespace.
 */
fun normalizeIngredientName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .trim()
        .replace(WHITESPACE, " ")
