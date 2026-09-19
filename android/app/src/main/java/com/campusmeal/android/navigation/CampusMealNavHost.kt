package com.campusmeal.android.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.campusmeal.android.R
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.app.CampusMealApplication
import com.campusmeal.android.feature.auth.AuthViewModel
import com.campusmeal.android.feature.auth.LoginScreen
import com.campusmeal.android.feature.auth.RegistrationScreen
import com.campusmeal.android.feature.context.ContextViewModel
import com.campusmeal.android.feature.context.LocationContextViewModel
import com.campusmeal.android.feature.context.SetContextScreen
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.inventory.presentation.InventoryRoute
import com.campusmeal.android.feature.inventory.presentation.InventoryViewModel
import com.campusmeal.android.feature.restaurants.presentation.RestaurantResultsRoute
import com.campusmeal.android.feature.restaurants.presentation.RestaurantResultsViewModel
import androidx.navigation.toRoute
import com.campusmeal.android.feature.restaurants.presentation.RestaurantDetailRoute
import com.campusmeal.android.feature.restaurants.presentation.RestaurantDetailViewModel

/**
 * Signed-out graph.
 */
@Composable
fun AuthNavHost(
    navController: NavHostController,
    container: AppContainer,
    showSessionExpired: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = CampusMealRoute.Auth,
        modifier = modifier,
    ) {
        composable<CampusMealRoute.Auth> {
            LoginScreen(
                viewModel = viewModel(
                    factory = AuthViewModel.factory(container),
                ),
                onCreateAccount = {
                    navController.navigate(
                        CampusMealRoute.Register,
                    )
                },
                showSessionExpired = showSessionExpired,
            )
        }

        composable<CampusMealRoute.Register> {
            RegistrationScreen(
                viewModel = viewModel(
                    factory = AuthViewModel.factory(container),
                ),
                onBack = {
                    navController.popBackStack()
                },
            )
        }
    }
}

/**
 * Protected application graph.
 */
@Composable
fun CampusMealNavHost(
    navController: NavHostController,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    startDestination: CampusMealRoute = CampusMealRoute.Home,
) {

    /*
     * Holds the last validated contextual search.
     *
     * It is kept only in memory and is not persisted, so precise
     * location information is not stored locally.
     */
    var lastRestaurantSearchRequest by remember {
        mutableStateOf<RestaurantSearchRequestDto?>(null)
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {

        composable<CampusMealRoute.Home> {
            HomePlaceholder(
                onOpenInventory = {
                    navController.navigate(
                        CampusMealRoute.Inventory,
                    )
                },
                onOpenContext = {
                    navController.navigate(
                        CampusMealRoute.Context,
                    )
                },
                onLogout = onLogout,
            )
        }

        composable<CampusMealRoute.Inventory> {

            val container =
                (
                        LocalContext.current.applicationContext
                                as CampusMealApplication
                        ).container

            InventoryRoute(
                viewModel = viewModel(
                    factory =
                        InventoryViewModel.factory(
                            container,
                        ),
                ),
            )
        }

        /*
         * BQ4:
         * contextual information + location.
         */
        composable<CampusMealRoute.Context> {

            val container =
                (
                        LocalContext.current.applicationContext
                                as CampusMealApplication
                        ).container

            val locationViewModel:
                    LocationContextViewModel =
                viewModel(
                    factory =
                        LocationContextViewModel
                            .factory(container),
                )

            val contextViewModel:
                    ContextViewModel =
                viewModel()

            SetContextScreen(
                contextViewModel =
                    contextViewModel,
                locationViewModel =
                    locationViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onSearchReady = { request ->

                    lastRestaurantSearchRequest =
                        request

                    navController.navigate(
                        CampusMealRoute.Restaurants,
                    )
                },
            )
        }

        /*
         * Issue #6:
         * real restaurant results using RestaurantRepository.
         */
        composable<CampusMealRoute.Restaurants> {

            val container =
                (
                        LocalContext.current.applicationContext
                                as CampusMealApplication
                        ).container

            val resultsViewModel:
                    RestaurantResultsViewModel =
                viewModel(
                    factory =
                        RestaurantResultsViewModel
                            .factory(container),
                )

            RestaurantResultsRoute(
                viewModel = resultsViewModel,
                request =
                    lastRestaurantSearchRequest,
                onBack = {
                    navController.popBackStack()
                },
                onChangeContext = {
                    /*
                     * Restaurants are reached directly from Context,
                     * so returning one destination brings the user
                     * back to the context form.
                     */
                    navController.popBackStack()
                },
                onRestaurantSelected = { restaurantId ->
                    navController.navigate(
                        CampusMealRoute.RestaurantDetail(
                            restaurantId = restaurantId,
                        ),
                    )
                },
            )
        }
        composable<CampusMealRoute.RestaurantDetail> { backStackEntry ->

            val route =
                backStackEntry
                    .toRoute<
                            CampusMealRoute.RestaurantDetail
                            >()

            val container =
                (
                        LocalContext.current.applicationContext
                                as CampusMealApplication
                        ).container

            val detailViewModel:
                    RestaurantDetailViewModel =
                viewModel(
                    factory =
                        RestaurantDetailViewModel
                            .factory(
                                container = container,
                                restaurantId =
                                    route.restaurantId,
                            ),
                )

            RestaurantDetailRoute(
                viewModel = detailViewModel,
                onBack = {
                    navController.popBackStack()
                },
            )
        }

        composable<CampusMealRoute.Decision> {
            RoutePlaceholder(
                "Decision",
            )
        }

        composable<CampusMealRoute.Profile> {
            RoutePlaceholder(
                "Profile",
            )
        }
    }
}

/**
 * Temporary Home while the final navigation UI is not implemented.
 */
@Composable
private fun HomePlaceholder(
    onOpenInventory: () -> Unit,
    onOpenContext: () -> Unit,
    onLogout: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment =
            Alignment.Center,
    ) {
        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.spacedBy(
                    16.dp,
                ),
        ) {

            Text(
                text =
                    stringResource(
                        R.string.foundation_ready,
                    ),
                style =
                    MaterialTheme
                        .typography
                        .headlineMedium,
                textAlign =
                    TextAlign.Center,
            )

            Button(
                onClick =
                    onOpenInventory,
            ) {
                Text(
                    "Inventory",
                )
            }

            Button(
                onClick =
                    onOpenContext,
            ) {
                Text(
                    stringResource(
                        R.string.context_open_action,
                    ),
                )
            }

            TextButton(
                onClick =
                    onLogout,
            ) {
                Text(
                    stringResource(
                        R.string.auth_logout_action,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RoutePlaceholder(
    routeName: String,
) {
    PlaceholderMessage(
        stringResource(
            R.string.route_placeholder,
            routeName,
        ),
    )
}

@Composable
private fun PlaceholderMessage(
    text: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment =
            Alignment.Center,
    ) {
        Text(
            text = text,
            style =
                MaterialTheme
                    .typography
                    .headlineMedium,
            textAlign =
                TextAlign.Center,
        )
    }
}