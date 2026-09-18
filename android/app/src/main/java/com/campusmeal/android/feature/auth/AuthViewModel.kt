package com.campusmeal.android.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.feature.auth.domain.AuthRepository
import com.campusmeal.android.feature.auth.domain.AuthResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthField { FULL_NAME, EMAIL, PASSWORD, CONFIRM_PASSWORD, TERMS }

enum class FieldError {
    REQUIRED,
    INVALID_EMAIL,
    PASSWORD_TOO_SHORT,
    PASSWORD_NEEDS_LETTER_AND_DIGIT,
    PASSWORDS_DO_NOT_MATCH,
    TERMS_NOT_ACCEPTED,
}

/** What the user typed. Held in memory only: passwords are never persisted and are cleared on success. */
data class AuthForm(
    val fullName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val acceptedTerms: Boolean = false,
) {
    override fun toString(): String = "AuthForm(email=$email, password=<redacted>)"
}

enum class RegistrationProblem { EMAIL_ALREADY_REGISTERED, REJECTED_INPUT }

sealed interface AuthUiState {

    data object Initial : AuthUiState

    /** The last submit failed client-side validation; each field shows its own error. */
    data class Validating(val errors: Map<AuthField, FieldError>) : AuthUiState

    data object Loading : AuthUiState

    /** The session exists; protected navigation takes over from here. */
    data object Authenticated : AuthUiState

    data object InvalidCredentials : AuthUiState

    data class RegistrationError(val problem: RegistrationProblem) : AuthUiState

    /** No connection or the service is down — never reported as a credential problem. */
    data object ConnectionError : AuthUiState

    /** Login was opened from the Session Expired screen. */
    data object SessionExpired : AuthUiState
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _form = MutableStateFlow(AuthForm())
    val form: StateFlow<AuthForm> = _form.asStateFlow()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Initial)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onFullNameChange(value: String) = edit(AuthField.FULL_NAME) { copy(fullName = value) }
    fun onEmailChange(value: String) = edit(AuthField.EMAIL) { copy(email = value) }
    fun onPasswordChange(value: String) = edit(AuthField.PASSWORD) { copy(password = value) }
    fun onConfirmPasswordChange(value: String) = edit(AuthField.CONFIRM_PASSWORD) { copy(confirmPassword = value) }
    fun onAcceptedTermsChange(value: Boolean) = edit(AuthField.TERMS) { copy(acceptedTerms = value) }

    fun showSessionExpired() {
        if (_uiState.value == AuthUiState.Initial) _uiState.value = AuthUiState.SessionExpired
    }

    fun logIn() {
        val form = _form.value
        submit(validateLogin(form)) { repository.login(form.email.trim(), form.password) }
    }

    fun register() {
        val form = _form.value
        submit(validateRegistration(form)) {
            repository.register(form.fullName.trim(), form.email.trim(), form.password)
        }
    }

    private fun submit(errors: Map<AuthField, FieldError>, call: suspend () -> AuthResult) {
        if (_uiState.value == AuthUiState.Loading) return
        if (errors.isNotEmpty()) {
            _uiState.value = AuthUiState.Validating(errors)
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = call()
            _uiState.value = when (result) {
                AuthResult.Success -> {
                    _form.update { it.copy(password = "", confirmPassword = "") }
                    AuthUiState.Authenticated
                }
                AuthResult.InvalidCredentials -> AuthUiState.InvalidCredentials
                AuthResult.EmailAlreadyRegistered ->
                    AuthUiState.RegistrationError(RegistrationProblem.EMAIL_ALREADY_REGISTERED)
                AuthResult.RejectedInput -> AuthUiState.RegistrationError(RegistrationProblem.REJECTED_INPUT)
                is AuthResult.Unavailable -> AuthUiState.ConnectionError
            }
        }
    }

    /** Editing a field clears its error and any authentication message from the last submit. */
    private fun edit(field: AuthField, change: AuthForm.() -> AuthForm) {
        _form.update(change)
        _uiState.update { state ->
            when (state) {
                is AuthUiState.Validating -> (state.errors - field)
                    .takeIf { it.isNotEmpty() }
                    ?.let(AuthUiState::Validating)
                    ?: AuthUiState.Initial
                AuthUiState.Loading, AuthUiState.Authenticated -> state
                else -> AuthUiState.Initial
            }
        }
    }

    companion object {
        private val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private const val MIN_PASSWORD_LENGTH = 8

        /** Login only checks presence and format; strength rules apply to new passwords only. */
        fun validateLogin(form: AuthForm): Map<AuthField, FieldError> = buildMap {
            emailError(form.email)?.let { put(AuthField.EMAIL, it) }
            if (form.password.isEmpty()) put(AuthField.PASSWORD, FieldError.REQUIRED)
        }

        fun validateRegistration(form: AuthForm): Map<AuthField, FieldError> = buildMap {
            if (form.fullName.isBlank()) put(AuthField.FULL_NAME, FieldError.REQUIRED)
            emailError(form.email)?.let { put(AuthField.EMAIL, it) }
            passwordError(form.password)?.let { put(AuthField.PASSWORD, it) }
            when {
                form.confirmPassword.isEmpty() -> put(AuthField.CONFIRM_PASSWORD, FieldError.REQUIRED)
                form.confirmPassword != form.password ->
                    put(AuthField.CONFIRM_PASSWORD, FieldError.PASSWORDS_DO_NOT_MATCH)
            }
            if (!form.acceptedTerms) put(AuthField.TERMS, FieldError.TERMS_NOT_ACCEPTED)
        }

        private fun emailError(email: String): FieldError? = when {
            email.isBlank() -> FieldError.REQUIRED
            !EMAIL_PATTERN.matches(email.trim()) -> FieldError.INVALID_EMAIL
            else -> null
        }

        private fun passwordError(password: String): FieldError? = when {
            password.isEmpty() -> FieldError.REQUIRED
            password.length < MIN_PASSWORD_LENGTH -> FieldError.PASSWORD_TOO_SHORT
            password.none(Char::isLetter) || password.none(Char::isDigit) ->
                FieldError.PASSWORD_NEEDS_LETTER_AND_DIGIT
            else -> null
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(container.authRepository) }
        }
    }
}
