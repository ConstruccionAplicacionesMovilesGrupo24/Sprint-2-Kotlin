package com.campusmeal.android.feature.profile.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.feature.home.ContextSummary
import com.campusmeal.android.feature.home.cop
import com.campusmeal.android.feature.profile.data.CurrentUserResult
import com.campusmeal.android.feature.profile.data.ProfileSource

private val CardShape = RoundedCornerShape(14.dp)

/**
 * Account data from `/auth/me` (read only: the API has no profile update endpoint), the context in
 * use, logout, and the attribution the walking-route data requires.
 */
@Composable
fun ProfileRoute(
    viewModel: ProfileViewModel,
    context: ContextSummary?,
    online: Boolean,
    onChangeContext: () -> Unit,
    onLogout: () -> Unit,
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ReconnectEffect(online, viewModel::onReconnected)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        when (val result = user) {
            null -> CircularProgressIndicator()
            is CurrentUserResult.Success -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.source == ProfileSource.CACHE) {
                    Text(
                        "Offline · profile updated ${formatSyncedAt(result.fetchedAtEpochMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.campusMealColors.textSecondary,
                    )
                }
                ProfileHeader(result)
            }
            CurrentUserResult.Unauthorized -> Text("Your session is no longer valid. Log in again.")
            CurrentUserResult.Unavailable -> Column {
                Text("Your profile is not available offline yet. Connect to load it.")
                TextButton(onClick = viewModel::load) { Text("Try again") }
            }
        }

        Section("Current context", action = if (context == null) "Set" else "Change", onAction = onChangeContext) {
            if (context == null) {
                Text("No context yet.", color = MaterialTheme.campusMealColors.textSecondary)
            } else {
                Info("Campus", context.place)
                Info("Time available", "${context.availableMinutes} minutes")
                Info("Budget", cop(context.maximumBudget))
            }
        }

        Section("About") {
            Info("Version", BuildConfig.VERSION_NAME)
            Text(
                "Walking times: Valhalla routing engine · map data © OpenStreetMap contributors (ODbL).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
        }

        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(11.dp)) {
            Text("Log out")
        }
    }
}

/** Avatar, name, email and role. The role only changes presentation; the backend stays authoritative. */
@Composable
private fun ProfileHeader(result: CurrentUserResult.Success) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(initials(result.user.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(result.user.name, style = MaterialTheme.typography.titleMedium)
            Text(result.user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.campusMealColors.textSecondary)
            Text(
                if (result.user.role == "ANALYST") "Analyst account" else "Student account",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Section(title: String, action: String? = null, onAction: () -> Unit = {}, content: @Composable () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.campusMealColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (action != null) TextButton(onClick = onAction) { Text(action) }
            }
            content()
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, color = MaterialTheme.campusMealColors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
