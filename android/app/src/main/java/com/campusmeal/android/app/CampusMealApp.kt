package com.campusmeal.android.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.campusmeal.android.feature.auth.AccountIdentificationViewModel
import com.campusmeal.android.feature.auth.AccountNotIdentifiedScreen
import com.campusmeal.android.feature.auth.SessionExpiredDialog
import com.campusmeal.android.feature.auth.domain.SessionStatus
import com.campusmeal.android.navigation.AuthNavHost
import com.campusmeal.android.navigation.CampusMealNavHost
import kotlinx.coroutines.launch

/**
 * Protected navigation: the main graph is only composed while an identified session exists, so no
 * protected screen can be reached — not even through the back stack — without one.
 *
 * The main graph is keyed by the session scope (account id + generation). Any session change discards
 * it, its navigation state and its ViewModels, even when the new session is also authenticated.
 * An expired session shows the Session Expired modal without the main graph behind it, so nothing of
 * the ended session keeps running or stays readable.
 */
@Composable
fun CampusMealApp(
    modifier: Modifier = Modifier,
    container: AppContainer = (LocalContext.current.applicationContext as CampusMealApplication).container,
) {
    val authRepository = container.authRepository
    val status by authRepository.sessionStatus.collectAsStateWithLifecycle(initialValue = null)
    val online by container.connectivityObserver.isOnline.collectAsStateWithLifecycle(initialValue = true)
    var loginAfterExpiry by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sessionStores: SessionViewModelStores = viewModel()

    LaunchedEffect(status) {
        if (status is SessionStatus.Authenticated) loginAfterExpiry = false
        // Leaving the authenticated graph clears its ViewModels right away, not at the next login.
        if (status != null && status !is SessionStatus.Authenticated) sessionStores.clear()
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        val content = Modifier.padding(innerPadding)
        when (val current = status) {
            // First frame, before the session status is known: show nothing rather than flash Login.
            null -> Unit

            SessionStatus.SignedOut -> AuthNavHost(
                navController = rememberNavController(),
                container = container,
                showSessionExpired = loginAfterExpiry,
                modifier = content,
            )

            SessionStatus.AccountNotIdentified -> AccountNotIdentifiedScreen(
                viewModel = viewModel(factory = AccountIdentificationViewModel.factory(container)),
                online = online,
                modifier = content,
            )

            is SessionStatus.Authenticated -> key(current.scope) {
                val owner = remember(current.scope) { sessionStores.ownerFor(current.scope) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    CampusMealNavHost(
                        navController = rememberNavController(),
                        sessionScope = current.scope,
                        online = online,
                        onLogout = { scope.launch { authRepository.logout() } },
                        modifier = content,
                    )
                }
            }

            SessionStatus.Expired -> SessionExpiredDialog(
                onLogIn = {
                    loginAfterExpiry = true
                    authRepository.acknowledgeExpiredSession()
                },
            )
        }
    }
}
