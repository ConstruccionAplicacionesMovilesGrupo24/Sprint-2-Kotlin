package com.campusmeal.android.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.campusmeal.android.feature.auth.SessionExpiredDialog
import com.campusmeal.android.feature.auth.domain.SessionStatus
import com.campusmeal.android.navigation.AuthNavHost
import com.campusmeal.android.navigation.CampusMealNavHost
import kotlinx.coroutines.launch

/**
 * Protected navigation: the main graph is only composed while a session exists, so no protected
 * screen can be reached — not even through the back stack — without one. Signing out discards the
 * main graph entirely.
 */
@Composable
fun CampusMealApp(
    modifier: Modifier = Modifier,
    container: AppContainer = (LocalContext.current.applicationContext as CampusMealApplication).container,
) {
    val authRepository = container.authRepository
    val status by authRepository.sessionStatus.collectAsStateWithLifecycle(initialValue = null)
    var loginAfterExpiry by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(status) { if (status == SessionStatus.AUTHENTICATED) loginAfterExpiry = false }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        val content = Modifier.padding(innerPadding)
        when (status) {
            // First frame, before the session status is known: show nothing rather than flash Login.
            null -> Unit

            SessionStatus.SIGNED_OUT -> AuthNavHost(
                navController = rememberNavController(),
                container = container,
                showSessionExpired = loginAfterExpiry,
                modifier = content,
            )

            SessionStatus.AUTHENTICATED, SessionStatus.EXPIRED -> {
                CampusMealNavHost(
                    navController = rememberNavController(),
                    onLogout = { scope.launch { authRepository.logout() } },
                    modifier = content,
                )
                if (status == SessionStatus.EXPIRED) {
                    SessionExpiredDialog(
                        onLogIn = {
                            loginAfterExpiry = true
                            authRepository.acknowledgeExpiredSession()
                        },
                    )
                }
            }
        }
    }
}
