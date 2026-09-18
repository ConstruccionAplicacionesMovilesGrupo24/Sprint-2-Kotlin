package com.campusmeal.android.feature.context

import androidx.lifecycle.ViewModel
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.data.remote.toRestaurantSearchRequestDto
import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.context.domain.MealContext
import com.campusmeal.android.feature.context.domain.MealLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContextForm(
    val availableMinutes: Int = 45,
    val maximumBudget: String = "",
    val dietaryPreferences: Set<DietaryPreference> = emptySet(),
    val includeDelivery: Boolean = false,
)

enum class ContextValidationError {
    AVAILABLE_TIME,
    BUDGET,
    LOCATION,
}

sealed interface ContextUiState {

    data object Initial : ContextUiState

    data class Editing(
        val form: ContextForm,
    ) : ContextUiState

    data class InvalidContext(
        val form: ContextForm,
        val errors: Set<ContextValidationError>,
    ) : ContextUiState

    data class RequestReady(
        val form: ContextForm,
        val request: RestaurantSearchRequestDto,
    ) : ContextUiState
}

class ContextViewModel(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState =
        MutableStateFlow<ContextUiState>(ContextUiState.Initial)

    val uiState: StateFlow<ContextUiState> =
        _uiState.asStateFlow()

    fun onContextOpened() {
        if (_uiState.value == ContextUiState.Initial) {
            _uiState.value = ContextUiState.Editing(
                ContextForm(),
            )
        }
    }

    fun onAvailableMinutesChanged(
        minutes: Int,
    ) {
        updateForm {
            it.copy(
                availableMinutes = minutes,
            )
        }
    }

    fun onMaximumBudgetChanged(
        value: String,
    ) {
        updateForm {
            it.copy(
                maximumBudget = value,
            )
        }
    }

    fun onDietaryPreferenceToggled(
        preference: DietaryPreference,
    ) {
        updateForm { form ->
            val updatedPreferences =
                if (preference in form.dietaryPreferences) {
                    form.dietaryPreferences - preference
                } else {
                    form.dietaryPreferences + preference
                }

            form.copy(
                dietaryPreferences = updatedPreferences,
            )
        }
    }

    fun onIncludeDeliveryChanged(
        includeDelivery: Boolean,
    ) {
        updateForm {
            it.copy(
                includeDelivery = includeDelivery,
            )
        }
    }

    /**
     * Combines user input, resolved location and application time.
     *
     * Returns null when validation fails and exposes the errors through
     * [uiState].
     */
    fun buildSearchRequest(
        location: MealLocation?,
    ): RestaurantSearchRequestDto? {

        val form = currentForm()

        val budget = form.maximumBudget
            .trim()
            .toLongOrNull()

        val errors = buildSet {
            if (form.availableMinutes <= 0) {
                add(
                    ContextValidationError.AVAILABLE_TIME,
                )
            }

            if (budget == null || budget < 0) {
                add(
                    ContextValidationError.BUDGET,
                )
            }

            if (location == null) {
                add(
                    ContextValidationError.LOCATION,
                )
            }
        }

        if (errors.isNotEmpty()) {
            _uiState.value =
                ContextUiState.InvalidContext(
                    form = form,
                    errors = errors,
                )

            return null
        }

        val mealContext = MealContext(
            location = checkNotNull(location),
            availableMinutes = form.availableMinutes,
            maximumBudget = checkNotNull(budget),
            dietaryPreferences = form.dietaryPreferences,
            includeDelivery = form.includeDelivery,
        )

        val request =
            mealContext.toRestaurantSearchRequestDto(
                requestedAtEpochMillis =
                    currentTimeMillis(),
            )

        _uiState.value =
            ContextUiState.RequestReady(
                form = form,
                request = request,
            )

        return request
    }

    private fun currentForm(): ContextForm =
        when (val state = _uiState.value) {
            ContextUiState.Initial ->
                ContextForm()

            is ContextUiState.Editing ->
                state.form

            is ContextUiState.InvalidContext ->
                state.form

            is ContextUiState.RequestReady ->
                state.form
        }

    private fun updateForm(
        update: (ContextForm) -> ContextForm,
    ) {
        _uiState.value =
            ContextUiState.Editing(
                update(currentForm()),
            )
    }
}