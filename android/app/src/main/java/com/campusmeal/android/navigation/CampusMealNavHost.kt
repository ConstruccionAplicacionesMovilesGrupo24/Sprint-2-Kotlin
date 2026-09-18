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
import com.campusmeal.android.feature.context.LocationContextScreen
import com.campusmeal.android.feature.context.LocationContextViewModel
import com.campusmeal.android.feature.inventory.presentation.InventoryRoute
import com.campusmeal.android.feature.inventory.presentation.InventoryViewModel

/**
 * The signed-out graph: Login and Registration. A successful login or registration changes the
 * session status, and `CampusMealApp` swaps this graph for the protected one.
 */
@Composable
fun AuthNavHost(
    navController: NavHostController,
    container: AppContainer,
    showSessionExpired: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = CampusMealRoute.Auth, modifier = modifier) {
        composable<CampusMealRoute.Auth> {
            LoginScreen(
                viewModel = viewModel(factory = AuthViewModel.factory(container)),
                onCreateAccount = { navController.navigate(CampusMealRoute.Register) },
                showSessionExpired = showSessionExpired,
            )
        }
        composable<CampusMealRoute.Register> {
            RegistrationScreen(
                viewModel = viewModel(factory = AuthViewModel.factory(container)),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * The protected graph. `CampusMealApp` only composes it while a session exists. Feature screens
 * replace the placeholders as they are implemented.
 */
@Composable
fun CampusMealNavHost(
    navController: NavHostController,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    startDestination: CampusMealRoute = CampusMealRoute.Home,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<CampusMealRoute.Home> {
            HomePlaceholder(onOpenInventory = {
                navController.navigate(CampusMealRoute.Inventory)
            },
                onOpenContext = { navController.navigate(CampusMealRoute.Context) },
                onLogout = onLogout)
        }
        composable<CampusMealRoute.Inventory> {
            val container =
                (LocalContext.current.applicationContext as CampusMealApplication).container

            InventoryRoute(
                viewModel = viewModel(
                    factory = InventoryViewModel.factory(container),
                ),
            )
        }
        // Hosts the BQ4 location section on its own until the Set Context screen exists.
        composable<CampusMealRoute.Context> {
            val container = (LocalContext.current.applicationContext as CampusMealApplication).container
            LocationContextScreen(
                viewModel = viewModel(factory = LocationContextViewModel.factory(container)),
                onBack = { navController.popBackStack() },
            )
        }
        composable<CampusMealRoute.Restaurants> { RoutePlaceholder("Restaurants") }
        composable<CampusMealRoute.Decision> { RoutePlaceholder("Decision") }
        composable<CampusMealRoute.Profile> { RoutePlaceholder("Profile") }
    }
}

/** Temporary entry point so the BQ4 context flow can be reached and validated by hand. */
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
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.foundation_ready),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )

            Button(onClick = onOpenInventory) {
                Text("Inventory")
            }

            Button(onClick = onOpenContext) {
                Text(stringResource(R.string.context_open_action))
            }

            // Temporary, until the Profile screen hosts it.
            TextButton(onClick = onLogout) {
                Text(stringResource(R.string.auth_logout_action))
            }
        }
    }
}

@Composable
private fun RoutePlaceholder(routeName: String) {
    PlaceholderMessage(stringResource(R.string.route_placeholder, routeName))
}

@Composable
private fun PlaceholderMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
    }
}
