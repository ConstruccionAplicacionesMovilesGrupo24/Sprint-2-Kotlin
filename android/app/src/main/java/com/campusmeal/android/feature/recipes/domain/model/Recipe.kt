package com.campusmeal.android.feature.recipes.domain.model

import com.campusmeal.android.feature.context.domain.DietaryPreference

/** The only `GET recipes` response shape this app understands. */
const val SUPPORTED_RECIPE_CONTRACT_VERSION = 1

/** Allowed range of the "maximum preparation time" filter (plan §4.1). */
val PREPARATION_MINUTES_RANGE: IntRange = 5..180

/** Units a catalog ingredient may use; each belongs to one dimension and never converts across them. */
enum class RecipeUnit(val wire: String, val dimension: UnitDimension, val toBase: Double) {
    G("g", UnitDimension.MASS, 1.0),
    KG("kg", UnitDimension.MASS, 1000.0),
    ML("ml", UnitDimension.VOLUME, 1.0),
    L("L", UnitDimension.VOLUME, 1000.0),
    UNIT("unit", UnitDimension.COUNT, 1.0),
    ;

    companion object {
        /** Exact wire value from the catalog (case-sensitive: `L`, never `l`). */
        fun fromWire(value: String): RecipeUnit? = entries.firstOrNull { it.wire == value }
    }
}

/** Base units: grams, millilitres and units. */
enum class UnitDimension(val baseSymbol: String) { MASS("g"), VOLUME("ml"), COUNT("unit") }

data class RecipeIngredient(
    /** Display name. */
    val name: String,
    /** Matching key, already normalized by the catalog. */
    val normalizedName: String,
    /** Extra normalized matching keys. */
    val aliases: List<String>,
    /** Amount for the whole recipe yield; never scaled. */
    val quantity: Double,
    val unit: RecipeUnit,
    /** Optional ingredients never block a match. */
    val optional: Boolean,
) {
    val matchingKeys: Set<String> get() = (listOf(normalizedName) + aliases).toSet()
}

data class Recipe(
    val id: String,
    val name: String,
    val preparationMinutes: Int,
    val servings: Int,
    val dietaryTags: Set<DietaryPreference>,
    val ingredients: List<RecipeIngredient>,
    val steps: List<String>,
)

/**
 * One validated catalog version. [generatedAt] is when the backend author produced this version: it
 * identifies the content, it is not when this device fetched it and it never drives the cache TTL.
 */
data class RecipeCatalog(
    val contractVersion: Int,
    val catalogVersion: String,
    val generatedAt: String,
    val recipes: List<Recipe>,
)

/** The catalog stored on this device. [fetchedAtEpochMillis] is the local time of the last successful fetch. */
data class RecipeCatalogSnapshot(
    val catalog: RecipeCatalog,
    val fetchedAtEpochMillis: Long,
)
