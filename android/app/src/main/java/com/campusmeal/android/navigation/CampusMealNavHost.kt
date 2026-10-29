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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.app.CampusMealApplication
import com.campusmeal.android.core.designsystem.NavigationIcons
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.analytics.presentation.AnalystReportRoute
import com.campusmeal.android.feature.analytics.presentation.AnalystReportViewModel
import com.campusmeal.android.feature.auth.AuthViewModel
import com.campusmeal.android.feature.auth.LoginScreen
import com.campusmeal.android.feature.auth.RegistrationScreen
import com.campusmeal.android.feature.context.ContextViewModel
import com.campusmeal.android.feature.context.LocationContextViewModel
import com.campusmeal.android.feature.context.SetContextScreen
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.data.remote.requestedNow
import com.campusmeal.android.feature.context.domain.DietaryPreference
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
import com.campusmeal.android.feature.recipes.presentation.RecipeDetailRoute
import com.campusmeal.android.feature.recipes.presentation.RecipesRoute
import com.campusmeal.android.feature.recipes.presentation.RecipesViewModel
import com.campusmeal.android.feature.restaurants.presentation.RestaurantDetailRoute
import com.campusmeal.android.feature.restaurants.presentation.RestaurantDetailViewModel
import com.campusmeal.android.feature.restaurants.presentation.RestaurantResultsRoute
import com.campusmeal.android.feature.restaurants.presentation.RestaurantResultsViewModel
import com.campusmeal.android.feature.restaurants.presentation.SelectedRoute

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
 *
 * Composed for exactly one [sessionScope]: every account-owned ViewModel is created for it, and the
 * caller discards this graph when the scope changes.
 */
@Composable
fun CampusMealNavHost(
    navController: NavHostController,
    sessionScope: AccountSessionScope,
    online: Boolean,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    startDestination: CampusMealRoute = CampusMealRoute.Home,
) {
    val container = (LocalContext.current.applicationContext as CampusMealApplication).container

    /*
     * The last validated context. In memory it may hold a device fix; on disk only its non-precise part
     * is kept per account (ContextPreferencesStore: time, budget, diet, delivery and a manual campus,
     * never coordinates). After a restart a manual-campus context is restored in full; one built from
     * a device fix restores only the form, and the location is asked again.
     */
    var currentContext by remember { mutableStateOf<RestaurantSearchRequestDto?>(null) }
    val contextStore = container.contextPreferencesStore
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(sessionScope) {
        if (currentContext == null) {
            contextStore.read(sessionScope)?.toRequest()?.let { restored ->
                if (currentContext == null) currentContext = restored
            }
        }
    }

    /*
     * The route estimate of the restaurant last opened from the results, with the request that produced
     * it. Memory only, like the context: it dies with the process and with this graph (scope change).
     * The detail screen uses it only while it still matches the current context and restaurant.
     */
    var selectedRoute by remember { mutableStateOf<SelectedRoute?>(null) }

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
                    viewModel = viewModel(factory = HomeViewModel.factory(container, sessionScope)),
                    context = currentContext?.toContextSummary(),
                    online = online,
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
                    onOpenRecipes = { navController.navigate(CampusMealRoute.Recipes) },
                )
            }

            composable<CampusMealRoute.Inventory> {
                InventoryRoute(
                    viewModel = viewModel(factory = InventoryViewModel.factory(container, sessionScope)),
                    online = online,
                    onOpenRecipes = { navController.navigate(CampusMealRoute.Recipes) },
                )
            }

            /*
             * BQ3: one visit = one RecipesViewModel on this entry; the detail shares it, so the evaluation,
             * memo and usage event are not repeated by opening a recipe.
             */
            composable<CampusMealRoute.Recipes> {
                RecipesRoute(
                    viewModel = viewModel(
                        factory = RecipesViewModel.factory(
                            container = container,
                            scope = sessionScope,
                            contextDiet = currentContext?.dietaryPreferences.orEmpty()
                                .mapNotNull { name -> DietaryPreference.entries.firstOrNull { it.name == name } }
                                .toSet(),
                        ),
                    ),
                    online = online,
                    onBack = { navController.popBackStack() },
                    onOpenRecipe = { recipeId -> navController.navigate(CampusMealRoute.RecipeDetail(recipeId = recipeId)) },
                )
            }

            composable<CampusMealRoute.RecipeDetail> { entry ->
                val route = entry.toRoute<CampusMealRoute.RecipeDetail>()
                val visitEntry = remember(entry) { navController.getBackStackEntry<CampusMealRoute.Recipes>() }
                RecipeDetailRoute(
                    viewModel = viewModel(
                        viewModelStoreOwner = visitEntry,
                        factory = RecipesViewModel.factory(container, sessionScope, contextDiet = emptySet()),
                    ),
                    recipeId = route.recipeId,
                    onBack = { navController.popBackStack() },
                )
            }

            /*
             * BQ4: contextual information + location.
             */
            composable<CampusMealRoute.Context> {
                val locationViewModel: LocationContextViewModel =
                    viewModel(factory = LocationContextViewModel.factory(container))
                val contextViewModel: ContextViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { ContextViewModel(savedForm = { contextStore.read(sessionScope)?.form }) }
                    },
                )

                SetContextScreen(
                    contextViewModel = contextViewModel,
                    locationViewModel = locationViewModel,
                    onBack = {
                        decideAfterContext = false
                        navController.popBackStack()
                    },
                    onSearchReady = { request ->
                        currentContext = request
                        coroutineScope.launch { contextStore.write(sessionScope, request) }
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
                val resultsViewModel: RestaurantResultsViewModel =
                    viewModel(factory = RestaurantResultsViewModel.factory(container, sessionScope))
                RestaurantResultsRoute(
                    viewModel = resultsViewModel,
                    request = currentContext,
                    onBack = { navController.popBackStack() },
                    onChangeContext = { navController.navigate(CampusMealRoute.Context) },
                    onRestaurantSelected = { restaurantId ->
                        selectedRoute = resultsViewModel.routeFor(restaurantId)
                        navController.navigate(CampusMealRoute.RestaurantDetail(restaurantId = restaurantId))
                    },
                    onCompareOptions = { openTab(TopLevel.DECIDE) },
                )
            }

            composable<CampusMealRoute.RestaurantDetail> { entry ->
                val route = entry.toRoute<CampusMealRoute.RestaurantDetail>()
                // Only an estimate from the current search context, for this restaurant and session.
                val estimate = selectedRoute
                    ?.takeIf {
                        it.scope == sessionScope && it.request == currentContext && it.estimate.restaurantId == route.restaurantId
                    }
                    ?.estimate
                RestaurantDetailRoute(
                    viewModel = viewModel(
                        factory = RestaurantDetailViewModel.factory(
                            container = container,
                            scope = sessionScope,
                            restaurantId = route.restaurantId,
                            routeEstimate = estimate,
                        ),
                    ),
                    onBack = { navController.popBackStack() },
                    // The existing search refresh: the same context evaluated now, back on the results.
                    onRefreshRoute = currentContext?.let { request ->
                        {
                            selectedRoute = null
                            currentContext = request.requestedNow()
                            navController.popBackStack()
                        }
                    },
                )
            }

            /*
             * BQ5 + BQ8: backend comparison of the current context, with impression/selection events.
             */
            composable<CampusMealRoute.Decision> {
                DecisionRoute(
                    viewModel = viewModel(factory = DecisionViewModel.factory(container, sessionScope)),
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
                    viewModel = viewModel(factory = ProfileViewModel.factory(container, sessionScope)),
                    context = currentContext?.toContextSummary(),
                    online = online,
                    onChangeContext = { navController.navigate(CampusMealRoute.Context) },
                    onOpenAnalystReport = { navController.navigate(CampusMealRoute.AnalystReport) },
                    onLogout = onLogout,
                )
            }

            composable<CampusMealRoute.AnalystReport> {
                AnalystReportRoute(
                    viewModel = viewModel(factory = AnalystReportViewModel.factory(container, sessionScope)),
                    online = online,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
