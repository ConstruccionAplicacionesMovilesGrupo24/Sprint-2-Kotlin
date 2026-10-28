package com.campusmeal.android.feature.profile.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.profile.data.CurrentUserResult
import com.campusmeal.android.feature.profile.data.ProfileRepository
import com.campusmeal.android.feature.profile.data.ProfileSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bound to the session [scope] it was created for; results of another scope are never shown. */
class ProfileViewModel(
    private val repository: ProfileRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
) : ViewModel() {

    /** Null while loading. */
    private val _user = MutableStateFlow<CurrentUserResult?>(null)
    val user: StateFlow<CurrentUserResult?> = _user.asStateFlow()

    private var loading: Job? = null

    fun load() {
        // One request at a time: a reconnect while loading does not start another.
        if (loading?.isActive == true) return
        loading = viewModelScope.launch {
            if (_user.value !is CurrentUserResult.Success) _user.value = null
            val result = repository.currentUser(scope)
            if (coordinator.isCurrent(scope)) _user.value = result
        }
    }

    /** Connectivity came back: refresh only what is not already current from the network. */
    fun onReconnected() {
        val shown = _user.value
        if (shown !is CurrentUserResult.Success || shown.source != ProfileSource.NETWORK) load()
    }

    companion object {
        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(container.profileRepository, container.sessionCoordinator, scope) }
        }
    }
}
