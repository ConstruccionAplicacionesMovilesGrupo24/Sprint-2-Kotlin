package com.campusmeal.android.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation contract for the top-level CampusMeal destinations. */
@Serializable
sealed interface CampusMealRoute {

    /** Login. Lives in the signed-out graph, together with [Register]. */
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

    /** Cook / Walk / Order decision flow. */
    @Serializable
    data object Decision : CampusMealRoute

    @Serializable
    data object Profile : CampusMealRoute
}
