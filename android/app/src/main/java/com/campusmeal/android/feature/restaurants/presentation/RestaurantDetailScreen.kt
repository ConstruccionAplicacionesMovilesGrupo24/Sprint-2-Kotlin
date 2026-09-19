package com.campusmeal.android.feature.restaurants.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.restaurants.domain.Meal
import com.campusmeal.android.feature.restaurants.domain.OpeningStatus
import com.campusmeal.android.feature.restaurants.domain.RestaurantDetail
import com.campusmeal.android.feature.restaurants.domain.RouteProviderStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val DetailPadding = 24.dp
private val DetailCardShape = RoundedCornerShape(14.dp)

@Composable
fun RestaurantDetailRoute(
    viewModel: RestaurantDetailViewModel,
    onBack: () -> Unit,
) {
    val state by
    viewModel.uiState.collectAsStateWithLifecycle()

    RestaurantDetailScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantDetailScreen(
    state: RestaurantDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor =
            MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Restaurant",
                        style =
                            MaterialTheme
                                .typography
                                .titleLarge,
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
                        )
                    }
                },
                windowInsets = WindowInsets(0),
                colors =
                    TopAppBarDefaults
                        .topAppBarColors(
                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .background,
                        ),
            )
        },
    ) { innerPadding ->

        when (state) {

            RestaurantDetailUiState.Loading -> {
                LoadingDetail(
                    modifier =
                        Modifier.padding(
                            innerPadding,
                        ),
                )
            }

            is RestaurantDetailUiState.Content -> {
                DetailContent(
                    detail = state.detail,
                    modifier =
                        Modifier.padding(
                            innerPadding,
                        ),
                )
            }

            is RestaurantDetailUiState.Error -> {
                DetailError(
                    message = state.message,
                    onRetry = onRetry,
                    modifier =
                        Modifier.padding(
                            innerPadding,
                        ),
                )
            }

            RestaurantDetailUiState.Unauthorized -> {
                UnauthorizedDetail(
                    onBack = onBack,
                    modifier =
                        Modifier.padding(
                            innerPadding,
                        ),
                )
            }
        }
    }
}

@Composable
private fun LoadingDetail(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(DetailPadding),
        horizontalAlignment =
            Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.Center,
    ) {
        CircularProgressIndicator()

        Spacer(
            modifier =
                Modifier.height(18.dp),
        )

        Text(
            text = "Loading restaurant...",
            style =
                MaterialTheme
                    .typography
                    .titleMedium,
        )
    }
}

@Composable
private fun DetailContent(
    detail: RestaurantDetail,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier =
            modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = DetailPadding,
                end = DetailPadding,
                top = 16.dp,
                bottom = 32.dp,
            ),
        verticalArrangement =
            Arrangement.spacedBy(16.dp),
    ) {

        item {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        6.dp,
                    ),
            ) {

                Text(
                    text =
                        detail.summary.name,
                    style =
                        MaterialTheme
                            .typography
                            .headlineMedium,
                    color =
                        MaterialTheme
                            .campusMealColors
                            .textPrimary,
                )

                Text(
                    text =
                        detail.summary.category,
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,
                    color =
                        MaterialTheme
                            .campusMealColors
                            .textSecondary,
                )

                Text(
                    text =
                        detail.summary
                            .openingStatus
                            .label(),
                    style =
                        MaterialTheme
                            .typography
                            .labelLarge,
                    fontWeight =
                        FontWeight.SemiBold,
                )
            }
        }

        detail.address
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let { address ->

                item {
                    InfoCard(
                        title = "Address",
                        value = address,
                    )
                }
            }

        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        12.dp,
                    ),
            ) {

                MetricCard(
                    modifier =
                        Modifier.weight(1f),
                    title = "Walk",
                    value =
                        detail.summary
                            .walkingMinutes
                            ?.let {
                                "$it min"
                            }
                            ?: "Unavailable",
                )

                MetricCard(
                    modifier =
                        Modifier.weight(1f),
                    title = "Total",
                    value =
                        detail.summary
                            .estimatedTotalMinutes
                            ?.let {
                                "$it min"
                            }
                            ?: "Unavailable",
                )
            }
        }

        if (
            detail.routeProviderStatus !=
            RouteProviderStatus.AVAILABLE
        ) {
            item {
                InfoCard(
                    title =
                        "Limited route information",
                    value =
                        "Walking estimates are currently incomplete. Restaurant and meal information is still available.",
                )
            }
        }

        item {
            Text(
                text = "Available meals",
                style =
                    MaterialTheme
                        .typography
                        .titleLarge,
                color =
                    MaterialTheme
                        .campusMealColors
                        .textPrimary,
            )
        }

        if (detail.meals.isEmpty()) {
            item {
                InfoCard(
                    title =
                        "No meals available",
                    value =
                        "This restaurant currently has no meal details to display.",
                )
            }
        } else {
            items(
                items = detail.meals,
                key = { meal -> meal.id },
            ) { meal ->

                MealCard(
                    meal = meal,
                )
            }
        }

        item {
            Text(
                text =
                    "Last updated ${
                        formatDetailTimestamp(
                            detail.lastUpdatedAtEpochMillis,
                        )
                    }",
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
    }
}

@Composable
private fun MealCard(
    meal: Meal,
) {
    Surface(
        modifier =
            Modifier.fillMaxWidth(),
        shape = DetailCardShape,
        border =
            BorderStroke(
                width = 1.dp,
                color =
                    MaterialTheme
                        .colorScheme
                        .outlineVariant,
            ),
        color =
            MaterialTheme
                .colorScheme
                .surface,
    ) {
        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(
                    8.dp,
                ),
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
            ) {

                Text(
                    text = meal.name,
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,
                    color =
                        MaterialTheme
                            .campusMealColors
                            .textPrimary,
                )

                Text(
                    text =
                        meal.price.toCop(),
                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,
                    fontWeight =
                        FontWeight.SemiBold,
                )
            }

            if (
                meal.dietaryTags.isNotEmpty()
            ) {
                Text(
                    text =
                        meal.dietaryTags
                            .joinToString(
                                " · ",
                            ) {
                                it.label()
                            },
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
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = DetailCardShape,
        border =
            BorderStroke(
                1.dp,
                MaterialTheme
                    .colorScheme
                    .outlineVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier.padding(16.dp),
        ) {
            Text(
                text = title,
                style =
                    MaterialTheme
                        .typography
                        .labelMedium,
                color =
                    MaterialTheme
                        .campusMealColors
                        .textSecondary,
            )

            Spacer(
                modifier =
                    Modifier.height(4.dp),
            )

            Text(
                text = value,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
                fontWeight =
                    FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    value: String,
) {
    Surface(
        modifier =
            Modifier.fillMaxWidth(),
        shape = DetailCardShape,
        color =
            MaterialTheme
                .colorScheme
                .surfaceVariant,
    ) {
        Column(
            modifier =
                Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(
                    6.dp,
                ),
        ) {
            Text(
                text = title,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
            )

            Text(
                text = value,
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                color =
                    MaterialTheme
                        .campusMealColors
                        .textSecondary,
            )
        }
    }
}

@Composable
private fun DetailError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(DetailPadding),
        horizontalAlignment =
            Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.Center,
    ) {

        Text(
            text =
                "Could not load restaurant",
            style =
                MaterialTheme
                    .typography
                    .headlineSmall,
        )

        Spacer(
            modifier =
                Modifier.height(8.dp),
        )

        Text(
            text = message,
            style =
                MaterialTheme
                    .typography
                    .bodyMedium,
        )

        Spacer(
            modifier =
                Modifier.height(24.dp),
        )

        Button(
            onClick = onRetry,
        ) {
            Text("Try again")
        }
    }
}

@Composable
private fun UnauthorizedDetail(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(DetailPadding),
        horizontalAlignment =
            Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.Center,
    ) {

        Text(
            text = "Session expired",
            style =
                MaterialTheme
                    .typography
                    .headlineSmall,
        )

        Spacer(
            modifier =
                Modifier.height(8.dp),
        )

        Text(
            text =
                "Please sign in again to continue.",
            style =
                MaterialTheme
                    .typography
                    .bodyMedium,
        )

        Spacer(
            modifier =
                Modifier.height(24.dp),
        )

        Button(
            onClick = onBack,
        ) {
            Text("Go back")
        }
    }
}

private fun OpeningStatus.label(): String =
    when (this) {
        OpeningStatus.OPEN ->
            "Open"

        OpeningStatus.CLOSING_SOON ->
            "Closing soon"

        OpeningStatus.CLOSED ->
            "Closed"
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

private fun Long.toCop(): String =
    "$" +
            "%,d".format(
                Locale.US,
                this,
            ).replace(",", ".")

private fun formatDetailTimestamp(
    epochMillis: Long,
): String =
    DateFormat
        .getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault(),
        )
        .format(
            Date(epochMillis),
        )