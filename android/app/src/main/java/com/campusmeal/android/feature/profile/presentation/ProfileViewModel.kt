package com.campusmeal.android.feature.profile.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.feature.profile.data.CurrentUserResult
import com.campusmeal.android.feature.profile.data.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(private val repository: ProfileRepository) : ViewModel() {

    /** Null while loading. */
    private val _user = MutableStateFlow<CurrentUserResult?>(null)
    val user: StateFlow<CurrentUserResult?> = _user.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _user.value = null
            _user.value = repository.currentUser()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(container.profileRepository) }
        }
    }
}
