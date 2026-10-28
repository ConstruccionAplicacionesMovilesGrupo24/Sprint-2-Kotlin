package com.campusmeal.android.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.R
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.designsystem.SlowResponseNotice
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.feature.auth.domain.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tokens exist but the account is not identified yet, so no account-owned data is shown. Each trigger
 * (explicit retry, returning to the foreground, connectivity coming back) makes at most one
 * `GET auth/me` request; there is no polling. A successful identification changes the session status
 * and the app leaves this screen by itself.
 */
class AccountIdentificationViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _identifying = MutableStateFlow(false)
    val identifying: StateFlow<Boolean> = _identifying.asStateFlow()

    private var job: Job? = null

    fun retry() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _identifying.value = true
            try {
                repository.identifyAccount()
            } finally {
                _identifying.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountIdentificationViewModel(container.authRepository) }
        }
    }
}

@Composable
fun AccountNotIdentifiedScreen(
    viewModel: AccountIdentificationViewModel,
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val identifying by viewModel.identifying.collectAsStateWithLifecycle()

    // Each return to the foreground (including the first composition) is one attempt.
    LifecycleStartEffect(viewModel) {
        viewModel.retry()
        onStopOrDispose { }
    }
    ReconnectEffect(online, viewModel::retry)

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.auth_identify_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(if (identifying) R.string.auth_identify_progress else R.string.auth_identify_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (identifying) CircularProgressIndicator()
        SlowResponseNotice(active = identifying, textAlign = TextAlign.Center)
        Button(
            onClick = viewModel::retry,
            enabled = !identifying,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(11.dp),
        ) {
            Text(stringResource(R.string.auth_identify_retry))
        }
        OutlinedButton(
            onClick = viewModel::logout,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(11.dp),
        ) {
            Text(stringResource(R.string.auth_identify_logout))
        }
    }
}
