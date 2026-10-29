package com.campusmeal.android.feature.recipes.data.mapper

import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.recipes.data.remote.RecipeCatalogResponseDto
import com.campusmeal.android.feature.recipes.data.remote.RecipeDto
import com.campusmeal.android.feature.recipes.data.remote.RecipeIngredientDto
import com.campusmeal.android.feature.recipes.domain.model.Recipe
import com.campusmeal.android.feature.recipes.domain.model.RecipeCatalog
import com.campusmeal.android.feature.recipes.domain.model.RecipeIngredient
import com.campusmeal.android.feature.recipes.domain.model.RecipeUnit
import com.campusmeal.android.feature.recipes.domain.model.SUPPORTED_RECIPE_CONTRACT_VERSION
import com.campusmeal.android.feature.recipes.domain.normalizeIngredientName
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Result of validating a whole catalog response. */
sealed interface CatalogValidation {
    data class Valid(val catalog: RecipeCatalog) : CatalogValidation

    /** [reason] names the first broken rule; it never contains user data. */
    data class Invalid(val reason: String) : CatalogValidation
}

private const val MAX_RECIPES = 200
private const val MAX_VERSION_LENGTH = 40
private const val MAX_NAME_LENGTH = 150
private val PREPARATION_RANGE = 1..240
private val SERVINGS_RANGE = 1..20
private val INGREDIENTS_RANGE = 1..20
private val STEPS_RANGE = 1..15
private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val UTC_INSTANT = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$""")

/**
 * Validates the complete response against contract version 1 (plan §4.1). One broken recipe rejects the
 * whole catalog: dropping it would present an incomplete catalog as complete. An empty `recipes` list
 * is valid.
 */
fun RecipeCatalogResponseDto.validate(): CatalogValidation {
    if (contractVersion != SUPPORTED_RECIPE_CONTRACT_VERSION) return invalid("unsupported contractVersion $contractVersion")
    if (catalogVersion.isBlank() || catalogVersion.length > MAX_VERSION_LENGTH) return invalid("catalogVersion")
    if (!isUtcInstant(generatedAt)) return invalid("generatedAt")
    if (recipes.size > MAX_RECIPES) return invalid("more than $MAX_RECIPES recipes")
    if (recipes.map { it.id.lowercase(Locale.ROOT) }.toSet().size != recipes.size) return invalid("duplicate recipe id")

    recipes.forEachIndexed { index, dto ->
        dto.problem()?.let { return invalid("recipe[$index]: $it") }
    }
    return CatalogValidation.Valid(RecipeCatalog(contractVersion, catalogVersion, generatedAt, recipes.map { it.toDomain() }))
}

private fun invalid(reason: String) = CatalogValidation.Invalid(reason)

/** The first broken rule of one recipe, or null when it is valid. */
private fun RecipeDto.problem(): String? {
    if (!UUID.matches(id)) return "id"
    if (name.isBlank() || name.length > MAX_NAME_LENGTH) return "name"
    if (preparationMinutes !in PREPARATION_RANGE) return "preparationMinutes"
    if (servings !in SERVINGS_RANGE) return "servings"
    if (dietaryTags.any { tag -> DietaryPreference.entries.none { it.name == tag } }) return "dietaryTags"
    if (dietaryTags.toSet().size != dietaryTags.size) return "duplicate dietaryTags"
    if (ingredients.size !in INGREDIENTS_RANGE) return "ingredient count"
    if (ingredients.all { it.optional }) return "no required ingredient"
    ingredients.forEachIndexed { index, ingredient -> ingredient.problem()?.let { return "ingredient[$index]: $it" } }
    // A matching key may identify only one ingredient of the recipe, or availability would be ambiguous.
    val keys = ingredients.flatMap { (listOf(it.normalizedName) + it.aliases).toSet() }
    if (keys.toSet().size != keys.size) return "ambiguous matching keys"
    if (steps.size !in STEPS_RANGE || steps.any { it.isBlank() }) return "steps"
    return null
}

private fun RecipeIngredientDto.problem(): String? {
    if (name.isBlank()) return "name"
    if (normalizedName.isBlank() || normalizeIngredientName(normalizedName) != normalizedName) return "normalizedName"
    if (aliases.any { it.isBlank() || normalizeIngredientName(it) != it }) return "aliases"
    if (!quantity.isFinite() || quantity <= 0.0) return "quantity"
    if (RecipeUnit.fromWire(unit) == null) return "unit"
    return null
}

/** Only called after [problem] returned null. */
private fun RecipeDto.toDomain(): Recipe =
    Recipe(
        id = id,
        name = name,
        preparationMinutes = preparationMinutes,
        servings = servings,
        dietaryTags = dietaryTags.map { DietaryPreference.valueOf(it) }.toSet(),
        ingredients = ingredients.map { dto ->
            RecipeIngredient(
                name = dto.name,
                normalizedName = dto.normalizedName,
                aliases = dto.aliases,
                quantity = dto.quantity,
                unit = checkNotNull(RecipeUnit.fromWire(dto.unit)),
                optional = dto.optional,
            )
        },
        steps = steps,
    )

/** Strict UTC instant ending in `Z`, with or without milliseconds. */
private fun isUtcInstant(value: String): Boolean {
    if (!UTC_INSTANT.matches(value)) return false
    val pattern = if (value.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
    return try {
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(value) != null
    } catch (_: ParseException) {
        false
    }
}
