package com.campusmeal.android.feature.recipes.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header

/**
 * BQ3 catalog (S3-04, CampusMealBack `docs/SPRINT3_BQ3.md`). Any authenticated role may read it; it
 * takes no query parameters. [authorization] is the full `Bearer` header value.
 */
interface RecipeApi {

    @GET("recipes")
    suspend fun getCatalog(
        @Header("Authorization") authorization: String,
    ): RecipeCatalogResponseDto
}

/**
 * Contract version 1. Every field is required except `aliases`, so a missing field (including a missing
 * `recipes` array) fails decoding instead of becoming an empty catalog. Unknown fields are ignored by
 * the shared Json configuration. Also the stored form of the catalog (`recipe_catalog_recipes`).
 */
@Serializable
data class RecipeCatalogResponseDto(
    val contractVersion: Int,
    val catalogVersion: String,
    val generatedAt: String,
    val recipes: List<RecipeDto>,
)

@Serializable
data class RecipeDto(
    val id: String,
    val name: String,
    val preparationMinutes: Int,
    val servings: Int,
    val dietaryTags: List<String>,
    val ingredients: List<RecipeIngredientDto>,
    val steps: List<String>,
)

@Serializable
data class RecipeIngredientDto(
    val name: String,
    val normalizedName: String,
    val aliases: List<String> = emptyList(),
    val quantity: Double,
    val unit: String,
    val optional: Boolean,
)
