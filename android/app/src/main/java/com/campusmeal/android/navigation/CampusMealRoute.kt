package com.campusmeal.android.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface CampusMealRoute {

    @Serializable
    data object Auth : CampusMealRoute

    @Serializable
    data object Register : CampusMealRoute

    @Serializable
    data object Home : CampusMealRoute

    @Serializable
    data object Inventory : CampusMealRoute

    @Serializable
    data object Context : CampusMealRoute

    @Serializable
    data object Restaurants : CampusMealRoute

    @Serializable
    data class RestaurantDetail(
        val restaurantId: String,
    ) : CampusMealRoute

    /** BQ3: recipes that fit the complete inventory, diet and preparation time. */
    @Serializable
    data object Recipes : CampusMealRoute

    @Serializable
    data class RecipeDetail(
        val recipeId: String,
    ) : CampusMealRoute

    /** Cook / Walk / Order decision flow. */
    @Serializable
    data object Decision : CampusMealRoute

    @Serializable
    data object Profile : CampusMealRoute
}