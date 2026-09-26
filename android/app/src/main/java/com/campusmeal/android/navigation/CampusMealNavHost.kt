package com.campusmeal.android.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.app.CampusMealApplication
import com.campusmeal.android.core.designsystem.NavigationIcons
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.feature.auth.AuthViewModel
import com.campusmeal.android.feature.auth.LoginScreen
import com.campusmeal.android.feature.auth.RegistrationScreen
import com.campusmeal.android.feature.context.ContextViewModel
import com.campusmeal.android.feature.context.LocationContextViewModel
import com.campusmeal.android.feature.context.SetContextScreen
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.data.remote.requestedNow
import com.campusmeal.android.feature.decision.data.mapper.toDecisionContext
import com.campusmeal.android.feature.decision.presentation.DecisionRoute
import com.campusmeal.android.feature.decision.presentation.DecisionViewModel
import com.campusmeal.android.feature.home.HomeRoute
import com.campusmeal.android.feature.home.HomeViewModel
import com.campusmeal.android.feature.home.toContextSummary
import com.campusmeal.android.feature.inventory.presentation.InventoryRoute
import com.campusmeal.android.feature.inventory.presentation.InventoryViewModel
import com.campusmeal.android.feature.profile.presentation.ProfileRoute
import com.campusmeal.android.feature.profile.presentation.ProfileViewModel
import com.campusmeal.android.feature.restaurants.presentation.RestaurantDetailRoute
import com.campusmeal.android.feature.restaurants.presentation.RestaurantDetailViewModel
import com.campusmeal.android.feature.restaurants.presentation.RestaurantResultsRoute
import com.campusmeal.android.feature.restaurants.presentation.RestaurantResultsViewModel

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

/** The four bottom-bar destinations, as in the CampusMeal design. */
private enum class TopLevel(val label: String, val icon: ImageVector, val route: CampusMealRoute) {
    HOME("Home", NavigationIcons.Home, CampusMealRoute.Home),
    INVENTORY("Inventory", NavigationIcons.Inventory, CampusMealRoute.Inventory),
    DECIDE("Decide", NavigationIcons.Decide, CampusMealRoute.Decision),
    PROFILE("Profile", NavigationIcons.Profile, CampusMealRoute.Profile),
}

/**
 * Protected application graph with the bottom navigation bar. The bar is shown on the four
 * top-level destinations; the context form, results and detail are pushed on top without it.
 */
@Composable
fun CampusMealNavHost(
    navController: NavHostController,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    startDestination: CampusMealRoute = CampusMealRoute.Home,
) {
    val container = (LocalContext.current.applicationContext as CampusMealApplication).container

    /*
     * The last validated context. Kept only in memory and never persisted, so precise location
     * information is not stored locally; it is lost when the process ends.
     */
    var currentContext by remember { mutableStateOf<RestaurantSearchRequestDto?>(null) }

    // True when the user asked to decide before having a context: the form then leads to Decision.
    var decideAfterContext by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val selectedTab = TopLevel.entries.firstOrNull { tab ->
        destination?.hasRoute(tab.route::class) == true
    }

    fun openTab(tab: TopLevel) {
        // Home is the root: go back to it and drop whatever was pushed on top, never restore it.
        if (tab == TopLevel.HOME && navController.popBackStack<CampusMealRoute.Home>(inclusive = false)) return
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun decide() {
        if (currentContext == null) {
            decideAfterContext = true
            navController.navigate(CampusMealRoute.Context)
        } else {
            openTab(TopLevel.DECIDE)
        }
    }

    Scaffold(
        modifier = modifier,
        // CampusMealApp's Scaffold already applies the system insets.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (selectedTab != null) {
                NavigationBar(
                    containerColor = MaterialTheme.campusMealColors.surface,
                    windowInsets = WindowInsets(0),
                ) {
                    TopLevel.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { if (tab == TopLevel.DECIDE) decide() else openTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {

            composable<CampusMealRoute.Home> {
                HomeRoute(
                    viewModel = viewModel(factory = HomeViewModel.factory(container)),
                    context = currentContext?.toContextSummary(),
                    onChangeContext = { navController.navigate(CampusMealRoute.Context) },
                    onCompare = ::decide,
                    onFindRestaurants = {
                        val request = currentContext
                        if (request == null) {
                            navController.navigate(CampusMealRoute.Context)
                        } else {
                            // Re-evaluate opening hours and routes at the current time.
                            currentContext = request.requestedNow()
                            navController.navigate(CampusMealRoute.Restaurants)
                        }
                    },
                    onOpenInventory = { openTab(TopLevel.INVENTORY) },
                )
            }

            composable<CampusMealRoute.Inventory> {
                InventoryRoute(
                    viewModel = viewModel(factory = InventoryViewModel.factory(container)),
                )
            }

            /*
             * BQ4: contextual information + location.
             */
            composable<CampusMealRoute.Context> {
                val locationViewModel: LocationContextViewModel =
                    viewModel(factory = LocationContextViewModel.factory(container))
                val contextViewModel: ContextViewModel = viewModel()

                SetContextScreen(
                    contextViewModel = contextViewModel,
                    locationViewModel = locationViewModel,
                    onBack = {
                        decideAfterContext = false
                        navController.popBackStack()
                    },
                    onSearchReady = { request ->
                        currentContext = request
                        if (decideAfterContext) {
                            decideAfterContext = false
                            navController.popBackStack<CampusMealRoute.Context>(inclusive = true)
                            openTab(TopLevel.DECIDE)
                        } else {
                            navController.navigate(CampusMealRoute.Restaurants)
                        }
                    },
                )
            }

            /*
             * Issue #6: real restaurant results using RestaurantRepository.
             */
            composable<CampusMealRoute.Restaurants> {
                RestaurantResultsRoute(
                    viewModel = viewModel(factory = RestaurantResultsViewModel.factory(container)),
                    request = currentContext,
                    onBack = { navController.popBackStack() },
                    onChangeContext = { navController.navigate(CampusMealRoute.Context) },
                    onRestaurantSelected = { restaurantId ->
                        navController.navigate(CampusMealRoute.RestaurantDetail(restaurantId = restaurantId))
                    },
                    onCompareOptions = { openTab(TopLevel.DECIDE) },
                )
            }

            composable<CampusMealRoute.RestaurantDetail> { entry ->
                val route = entry.toRoute<CampusMealRoute.RestaurantDetail>()
                RestaurantDetailRoute(
                    viewModel = viewModel(
                        factory = RestaurantDetailViewModel.factory(container = container, restaurantId = route.restaurantId),
                    ),
                    onBack = { navController.popBackStack() },
                )
            }

            /*
             * BQ5 + BQ8: backend comparison of the current context, with impression/selection events.
             */
            composable<CampusMealRoute.Decision> {
                DecisionRoute(
                    viewModel = viewModel(factory = DecisionViewModel.factory(container)),
                    context = remember(currentContext) { currentContext?.toDecisionContext() },
                    onBack = { navController.popBackStack() },
                    onSetContext = {
                        decideAfterContext = true
                        navController.navigate(CampusMealRoute.Context)
                    },
                )
            }

            composable<CampusMealRoute.Profile> {
                ProfileRoute(
                    viewModel = viewModel(factory = ProfileViewModel.factory(container)),
                    context = currentContext?.toContextSummary(),
                    onChangeContext = { navController.navigate(CampusMealRoute.Context) },
                    onLogout = onLogout,
                )
            }
        }
    }
}
