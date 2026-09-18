package com.campusmeal.android.feature.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.R
import com.campusmeal.android.core.designsystem.CampusMealTheme
import com.campusmeal.android.core.designsystem.campusMealColors

private val PagePadding = 24.dp
private val FieldShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(10.dp)
private val ButtonHeight = 52.dp

/** Screen 01 of the CampusMeal UI file. */
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onCreateAccount: () -> Unit,
    showSessionExpired: Boolean,
    modifier: Modifier = Modifier,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(showSessionExpired) { if (showSessionExpired) viewModel.showSessionExpired() }

    LoginContent(
        form = form,
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onLogIn = viewModel::logIn,
        onCreateAccount = onCreateAccount,
        modifier = modifier,
    )
}

@Composable
fun LoginContent(
    form: AuthForm,
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogIn: () -> Unit,
    onCreateAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = (state as? AuthUiState.Validating)?.errors.orEmpty()
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = PagePadding),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(40.dp))
            AppMark()
            Spacer(Modifier.height(20.dp))
            ScreenTitle(stringResource(R.string.auth_login_title))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            Spacer(Modifier.height(24.dp))
            AuthMessage(state)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledField(
                    label = R.string.auth_field_email,
                    value = form.email,
                    onValueChange = onEmailChange,
                    error = errors[AuthField.EMAIL],
                    keyboardType = KeyboardType.Email,
                )
                LabeledField(
                    label = R.string.auth_field_password,
                    value = form.password,
                    onValueChange = onPasswordChange,
                    error = errors[AuthField.PASSWORD],
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                )
            }
            Spacer(Modifier.height(28.dp))
            PrimaryButton(R.string.auth_login_action, loading = state == AuthUiState.Loading, onClick = onLogIn)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.auth_login_no_account),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            TextButton(onClick = onCreateAccount) {
                Text(
                    text = stringResource(R.string.auth_login_create_account),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** Screen 02 of the CampusMeal UI file. */
@Composable
fun RegistrationScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RegistrationContent(
        form = form,
        state = state,
        onFullNameChange = viewModel::onFullNameChange,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onAcceptedTermsChange = viewModel::onAcceptedTermsChange,
        onRegister = viewModel::register,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun RegistrationContent(
    form: AuthForm,
    state: AuthUiState,
    onFullNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onAcceptedTermsChange: (Boolean) -> Unit,
    onRegister: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = (state as? AuthUiState.Validating)?.errors.orEmpty()
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = PagePadding),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                TextButton(onClick = onBack) {
                    Text(
                        text = stringResource(R.string.context_back),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.campusMealColors.textPrimary,
                    )
                }
                Text(
                    text = stringResource(R.string.auth_register_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.campusMealColors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.auth_register_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            AuthMessage(state)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabeledField(
                    label = R.string.auth_field_full_name,
                    value = form.fullName,
                    onValueChange = onFullNameChange,
                    error = errors[AuthField.FULL_NAME],
                    keyboardType = KeyboardType.Text,
                )
                LabeledField(
                    label = R.string.auth_field_email,
                    value = form.email,
                    onValueChange = onEmailChange,
                    error = errors[AuthField.EMAIL],
                    keyboardType = KeyboardType.Email,
                )
                LabeledField(
                    label = R.string.auth_field_password,
                    value = form.password,
                    onValueChange = onPasswordChange,
                    error = errors[AuthField.PASSWORD],
                    keyboardType = KeyboardType.Password,
                    hint = R.string.auth_password_hint,
                )
                LabeledField(
                    label = R.string.auth_field_confirm_password,
                    value = form.confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    error = errors[AuthField.CONFIRM_PASSWORD],
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                )
                TermsCheckbox(form.acceptedTerms, errors[AuthField.TERMS], onAcceptedTermsChange)
            }
            Spacer(Modifier.height(24.dp))
        }
        PrimaryButton(R.string.auth_register_action, loading = state == AuthUiState.Loading, onClick = onRegister)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Screen 20 of the CampusMeal UI file: a modal over the last screen. It cannot be dismissed — the
 * session is already gone, so logging in again is the only way forward.
 */
@Composable
fun SessionExpiredDialog(onLogIn: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ExpiredMark()
                Text(
                    text = stringResource(R.string.auth_expired_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.campusMealColors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.auth_expired_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                PrimaryButton(R.string.auth_login_action, loading = false, onClick = onLogIn)
            }
        }
    }
}

/** Field and label announced together, with the error as supporting text so TalkBack reads it. */
@Composable
private fun LabeledField(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    error: FieldError?,
    keyboardType: KeyboardType,
    imeAction: ImeAction = ImeAction.Next,
    @StringRes hint: Int? = null,
) {
    val isPassword = keyboardType == KeyboardType.Password
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.campusMealColors.textPrimary,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            isError = error != null,
            shape = FieldShape,
            textStyle = MaterialTheme.typography.bodyLarge,
            // The visible label above is not linked to the field, so the field carries it too.
            placeholder = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
            supportingText = when {
                error != null -> {
                    { Text(stringResource(error.message())) }
                }
                hint != null -> {
                    { Text(stringResource(hint)) }
                }
                else -> null
            },
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                errorContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedPlaceholderColor = MaterialTheme.campusMealColors.textSecondary.copy(alpha = 0.5f),
                focusedPlaceholderColor = MaterialTheme.campusMealColors.textSecondary.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TermsCheckbox(checked: Boolean, error: FieldError?, onCheckedChange: (Boolean) -> Unit) {
    Column {
        // The whole row toggles, so the label is part of the checkbox for TalkBack and for touch.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.campusMealColors.textPrimary,
                ),
            )
            Text(
                text = stringResource(R.string.auth_terms),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textPrimary,
            )
        }
        if (error != null) {
            Text(
                text = stringResource(error.message()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * Authentication-level messages. Connectivity and credential problems use different copy and tone,
 * and the banner is a live region so TalkBack announces it without moving focus.
 */
@Composable
private fun AuthMessage(state: AuthUiState) {
    val (message, warning) = when (state) {
        AuthUiState.InvalidCredentials -> R.string.auth_error_invalid_credentials to false
        is AuthUiState.RegistrationError -> when (state.problem) {
            RegistrationProblem.EMAIL_ALREADY_REGISTERED -> R.string.auth_error_email_taken
            RegistrationProblem.REJECTED_INPUT -> R.string.auth_error_rejected_input
        } to false
        AuthUiState.ConnectionError -> R.string.auth_error_connection to true
        AuthUiState.SessionExpired -> R.string.auth_error_session_expired to true
        else -> return
    }
    val colors = MaterialTheme.campusMealColors
    Surface(
        color = if (warning) colors.warningBackground else colors.urgentBackground,
        shape = FieldShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun PrimaryButton(@StringRes text: Int, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        shape = ButtonShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(text), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ScreenTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.campusMealColors.textPrimary,
        modifier = Modifier.semantics { heading() },
    )
}

/** The orange "CM" tile from screen 01. */
@Composable
private fun AppMark() {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.auth_app_mark),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** The power mark from screen 20. */
@Composable
private fun ExpiredMark() {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(32.dp)) {
            val stroke = 2.5.dp.toPx()
            drawArc(
                color = primary,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawLine(
                color = primary,
                start = Offset(size.width / 2f, size.height * 0.2f),
                end = Offset(size.width / 2f, size.height * 0.5f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@StringRes
private fun FieldError.message(): Int = when (this) {
    FieldError.REQUIRED -> R.string.auth_field_error_required
    FieldError.INVALID_EMAIL -> R.string.auth_field_error_email
    FieldError.PASSWORD_TOO_SHORT -> R.string.auth_field_error_password_short
    FieldError.PASSWORD_NEEDS_LETTER_AND_DIGIT -> R.string.auth_field_error_password_mix
    FieldError.PASSWORDS_DO_NOT_MATCH -> R.string.auth_field_error_mismatch
    FieldError.TERMS_NOT_ACCEPTED -> R.string.auth_field_error_terms
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F5F4, heightDp = 844)
@Composable
private fun LoginPreview() {
    CampusMealTheme {
        LoginContent(AuthForm(email = "jp.bedoya@uniandes.edu.co"), AuthUiState.Initial, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F5F4, heightDp = 844)
@Composable
private fun RegistrationErrorsPreview() {
    CampusMealTheme {
        RegistrationContent(
            form = AuthForm(email = "jp.bedoya", password = "short"),
            state = AuthUiState.Validating(
                mapOf(
                    AuthField.FULL_NAME to FieldError.REQUIRED,
                    AuthField.EMAIL to FieldError.INVALID_EMAIL,
                    AuthField.PASSWORD to FieldError.PASSWORD_TOO_SHORT,
                    AuthField.CONFIRM_PASSWORD to FieldError.REQUIRED,
                    AuthField.TERMS to FieldError.TERMS_NOT_ACCEPTED,
                ),
            ),
            onFullNameChange = {},
            onEmailChange = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onAcceptedTermsChange = {},
            onRegister = {},
            onBack = {},
        )
    }
}
