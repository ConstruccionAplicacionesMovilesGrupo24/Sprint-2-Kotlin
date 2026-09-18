package com.campusmeal.android.feature.context

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.domain.DietaryPreference

private val PagePadding = 24.dp
private val CardShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(10.dp)
private val ButtonHeight = 52.dp

@Composable
fun SetContextScreen(
    contextViewModel: ContextViewModel,
    locationViewModel: LocationContextViewModel,
    onBack: () -> Unit,
    onSearchReady: (RestaurantSearchRequestDto) -> Unit,
) {
    val contextState by contextViewModel.uiState.collectAsStateWithLifecycle()
    val mealLocation by locationViewModel.mealLocation.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        contextViewModel.onContextOpened()
    }

    val form = contextState.form()
    val errors =
        (contextState as? ContextUiState.InvalidContext)
            ?.errors
            .orEmpty()

    SetContextContent(
        form = form,
        errors = errors,
        locationViewModel = locationViewModel,
        onBack = onBack,
        onAvailableMinutesChanged =
            contextViewModel::onAvailableMinutesChanged,
        onMaximumBudgetChanged =
            contextViewModel::onMaximumBudgetChanged,
        onDietaryPreferenceToggled =
            contextViewModel::onDietaryPreferenceToggled,
        onIncludeDeliveryChanged =
            contextViewModel::onIncludeDeliveryChanged,
        onSearch = {
            val request =
                contextViewModel.buildSearchRequest(
                    location = mealLocation,
                )

            if (request != null) {
                /*
                 * Once the request has been constructed, the exact device
                 * coordinates are no longer needed in memory.
                 */
                locationViewModel.clearPreciseLocation()

                onSearchReady(request)
            }
        },
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
)
@Composable
private fun SetContextContent(
    form: ContextForm,
    errors: Set<ContextValidationError>,
    locationViewModel: LocationContextViewModel,
    onBack: () -> Unit,
    onAvailableMinutesChanged: (Int) -> Unit,
    onMaximumBudgetChanged: (String) -> Unit,
    onDietaryPreferenceToggled: (DietaryPreference) -> Unit,
    onIncludeDeliveryChanged: (Boolean) -> Unit,
    onSearch: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Set context",
                        style = MaterialTheme.typography.titleLarge,
                        color =
                            MaterialTheme
                                .campusMealColors
                                .textPrimary,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                    ) {
                        Text(
                            text = "‹",
                            style =
                                MaterialTheme
                                    .typography
                                    .headlineSmall,
                            color =
                                MaterialTheme
                                    .campusMealColors
                                    .textPrimary,
                        )
                    }
                },
                windowInsets = WindowInsets(0),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .background,
                    ),
            )
        },
    ) { innerPadding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement =
                Arrangement.spacedBy(24.dp),
        ) {

            /*
             * LocationContextSection already contains the permission,
             * granted, denied and manual-campus states.
             */
            item {
                LocationContextSection(
                    viewModel = locationViewModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = PagePadding,
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp),
                ) {
                    SectionTitle(
                        text = "Available lunch time",
                    )

                    Text(
                        text = "How much time do you have?",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .campusMealColors
                                .textSecondary,
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(10.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp),
                    ) {
                        listOf(
                            15,
                            30,
                            45,
                            60,
                        ).forEach { minutes ->

                            FilterChip(
                                selected =
                                    form.availableMinutes ==
                                            minutes,
                                onClick = {
                                    onAvailableMinutesChanged(
                                        minutes,
                                    )
                                },
                                label = {
                                    Text(
                                        "$minutes min",
                                    )
                                },
                                shape = CircleShape,
                            )
                        }
                    }

                    if (
                        ContextValidationError.AVAILABLE_TIME
                        in errors
                    ) {
                        ValidationText(
                            "Choose a valid lunch time.",
                        )
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = PagePadding,
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(10.dp),
                ) {
                    SectionTitle(
                        text = "Maximum budget",
                    )

                    Text(
                        text =
                            "How much do you want to spend?",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .campusMealColors
                                .textSecondary,
                    )

                    OutlinedTextField(
                        value = form.maximumBudget,
                        onValueChange = { value ->
                            /*
                             * Blank is allowed while editing.
                             * Everything else must consist of digits.
                             */
                            if (
                                value.isEmpty() ||
                                value.all(Char::isDigit)
                            ) {
                                onMaximumBudgetChanged(
                                    value,
                                )
                            }
                        },
                        label = {
                            Text(
                                "Budget in COP",
                            )
                        },
                        placeholder = {
                            Text(
                                "20000",
                            )
                        },
                        singleLine = true,
                        isError =
                            ContextValidationError.BUDGET
                                    in errors,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    KeyboardType.Number,
                            ),
                        modifier =
                            Modifier.fillMaxWidth(),
                    )

                    if (
                        ContextValidationError.BUDGET
                        in errors
                    ) {
                        ValidationText(
                            "Enter a valid non-negative budget.",
                        )
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = PagePadding,
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp),
                ) {
                    SectionTitle(
                        text = "Dietary preferences",
                    )

                    Text(
                        text =
                            "We'll only show meals that match your preferences.",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .campusMealColors
                                .textSecondary,
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(10.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp),
                    ) {
                        DietaryPreference.entries
                            .forEach { preference ->

                                FilterChip(
                                    selected =
                                        preference in
                                                form.dietaryPreferences,
                                    onClick = {
                                        onDietaryPreferenceToggled(
                                            preference,
                                        )
                                    },
                                    label = {
                                        Text(
                                            preference.label(),
                                        )
                                    },
                                    shape = CircleShape,
                                    colors =
                                        FilterChipDefaults
                                            .filterChipColors(
                                                selectedContainerColor =
                                                    MaterialTheme
                                                        .colorScheme
                                                        .primaryContainer,
                                            ),
                                )
                            }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = PagePadding,
                        ),
                ) {
                    SectionTitle(
                        text = "Delivery",
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp),
                    )

                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        color =
                            MaterialTheme
                                .colorScheme
                                .surface,
                        shape = CardShape,
                        border = BorderStroke(
                            width = 1.dp,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment =
                                Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier =
                                    Modifier.weight(1f),
                                verticalArrangement =
                                    Arrangement.spacedBy(
                                        3.dp,
                                    ),
                            ) {
                                Text(
                                    text =
                                        "Include delivery",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodyLarge,
                                    color =
                                        MaterialTheme
                                            .campusMealColors
                                            .textPrimary,
                                )

                                Text(
                                    text =
                                        "Include restaurants that can deliver to you.",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        MaterialTheme
                                            .campusMealColors
                                            .textSecondary,
                                )
                            }

                            Switch(
                                checked =
                                    form.includeDelivery,
                                onCheckedChange =
                                    onIncludeDeliveryChanged,
                            )
                        }
                    }
                }
            }

            if (
                ContextValidationError.LOCATION
                in errors
            ) {
                item {
                    ValidationText(
                        text =
                            "Enable approximate location or choose a campus.",
                        modifier = Modifier.padding(
                            horizontal = PagePadding,
                        ),
                    )
                }
            }

            item {
                Button(
                    onClick = onSearch,
                    shape = ButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = PagePadding,
                            end = PagePadding,
                            bottom = 28.dp,
                        )
                        .height(ButtonHeight),
                ) {
                    Text(
                        text = "Find meals",
                        style =
                            MaterialTheme
                                .typography
                                .labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color =
            MaterialTheme
                .campusMealColors
                .textPrimary,
    )
}

@Composable
private fun ValidationText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun DietaryPreference.label(): String =
    when (this) {
        DietaryPreference.VEGETARIAN ->
            "Vegetarian"

        DietaryPreference.VEGAN ->
            "Vegan"

        DietaryPreference.GLUTEN_FREE ->
            "Gluten-free"
    }

private fun ContextUiState.form(): ContextForm =
    when (this) {
        ContextUiState.Initial ->
            ContextForm()

        is ContextUiState.Editing ->
            form

        is ContextUiState.InvalidContext ->
            form

        is ContextUiState.RequestReady ->
            form
    }