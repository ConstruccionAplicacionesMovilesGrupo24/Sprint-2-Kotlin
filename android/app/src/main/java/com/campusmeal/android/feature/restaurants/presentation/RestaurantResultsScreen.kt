package com.campusmeal.android.feature.restaurants.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.restaurants.domain.OpeningStatus
import com.campusmeal.android.feature.restaurants.domain.RestaurantResults
import com.campusmeal.android.feature.restaurants.domain.RestaurantSummary
import com.campusmeal.android.feature.restaurants.domain.RouteProviderStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val PagePadding = 24.dp
private val ResultCardShape = RoundedCornerShape(14.dp)

@Composable
fun RestaurantResultsRoute(
    viewModel: RestaurantResultsViewModel,
    request: RestaurantSearchRequestDto?,
    onBack: () -> Unit,
    onChangeContext: () -> Unit,
    onRestaurantSelected: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(request) {
        if (request != null) {
            viewModel.search(request)
        }
    }

    RestaurantResultsScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onChangeContext = onChangeContext,
        onRestaurantSelected = onRestaurantSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantResultsScreen(
    state: RestaurantResultsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onChangeContext: () -> Unit,
    onRestaurantSelected: (String) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Meal options",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                    ) {
                        Text(
                            text = "‹",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {

            when (state) {

                RestaurantResultsUiState.Initial,
                RestaurantResultsUiState.Loading -> {
                    LoadingContent()
                }

                RestaurantResultsUiState.NoResults -> {
                    EmptyResultsContent(
                        onChangeContext = onChangeContext,
                    )
                }

                is RestaurantResultsUiState.Content -> {
                    ResultsContent(
                        results = state.results,
                        onRetry = onRetry,
                        onChangeContext = onChangeContext,
                        onRestaurantSelected = onRestaurantSelected,
                    )
                }

                is RestaurantResultsUiState.RoutesUnavailableWithPartialResults -> {
                    ResultsContent(
                        results = state.results,
                        showRoutesUnavailable = true,
                        onRetry = onRetry,
                        onChangeContext = onChangeContext,
                        onRestaurantSelected = onRestaurantSelected,
                    )
                }

                is RestaurantResultsUiState.OfflineWithCache -> {
                    ResultsContent(
                        results = state.results,
                        showCacheMessage = true,
                        onRetry = onRetry,
                        onChangeContext = onChangeContext,
                        onRestaurantSelected = onRestaurantSelected,
                    )
                }

                is RestaurantResultsUiState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = onRetry,
                        onChangeContext = onChangeContext,
                    )
                }

                RestaurantResultsUiState.Unauthorized -> {
                    UnauthorizedContent(
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()

        Spacer(
            modifier = Modifier.height(20.dp),
        )

        Text(
            text = "Finding the best meal options...",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.campusMealColors.textPrimary,
        )

        Spacer(
            modifier = Modifier.height(6.dp),
        )

        Text(
            text = "Checking restaurants, prices and walking times.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
    }
}

@Composable
private fun EmptyResultsContent(
    onChangeContext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No restaurants match your context",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.campusMealColors.textPrimary,
        )

        Spacer(
            modifier = Modifier.height(8.dp),
        )

        Text(
            text = "Try increasing your available time or budget, or changing your dietary preferences.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
        )

        Spacer(
            modifier = Modifier.height(24.dp),
        )

        Button(
            onClick = onChangeContext,
        ) {
            Text("Change context")
        }
    }
}

@Composable
private fun ResultsContent(
    results: RestaurantResults,
    showRoutesUnavailable: Boolean = false,
    showCacheMessage: Boolean = false,
    onRetry: () -> Unit,
    onChangeContext: () -> Unit,
    onRestaurantSelected: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PagePadding,
            end = PagePadding,
            top = 16.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        item {
            Text(
                text =
                    "${results.restaurants.size} restaurants match your context",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
        }

        if (showRoutesUnavailable) {
            item {
                InformationBanner(
                    title = "Route information is limited",
                    message = when (results.routeProviderStatus) {
                        RouteProviderStatus.PARTIAL ->
                            "Some walking estimates are unavailable. Restaurant information is still shown."

                        RouteProviderStatus.UNAVAILABLE ->
                            "Walking routes are temporarily unavailable. You can still review restaurant information."

                        RouteProviderStatus.AVAILABLE ->
                            "Route information is available."
                    },
                    actionLabel = "Retry",
                    onAction = onRetry,
                )
            }
        }

        if (showCacheMessage) {
            item {
                InformationBanner(
                    title = "Showing saved results",
                    message =
                        "You're offline. Last updated ${formatTimestamp(results.lastUpdatedAtEpochMillis)}.",
                    actionLabel = "Retry",
                    onAction = onRetry,
                )
            }
        }

        items(
            items = results.restaurants,
            key = { restaurant -> restaurant.id },
        ) { restaurant ->

            RestaurantResultCard(
                restaurant = restaurant,
                onClick = {
                    onRestaurantSelected(
                        restaurant.id,
                    )
                },
            )
        }

        item {
            Spacer(
                modifier = Modifier.height(4.dp),
            )

            OutlinedButton(
                onClick = onChangeContext,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Change search context")
            }
        }
    }
}

@Composable
private fun RestaurantResultCard(
    restaurant: RestaurantSummary,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ResultCardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = restaurant.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.campusMealColors.textPrimary,
                    )

                    Text(
                        text = restaurant.category,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.campusMealColors.textSecondary,
                    )
                }

                Text(
                    text = restaurant.openingStatus.label(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when (restaurant.openingStatus) {
                        OpeningStatus.OPEN ->
                            MaterialTheme.campusMealColors.positiveForeground

                        OpeningStatus.CLOSING_SOON ->
                            MaterialTheme.campusMealColors.warningForeground

                        OpeningStatus.CLOSED ->
                            MaterialTheme.colorScheme.error
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {

                ResultMetric(
                    label = "Walk",
                    value = restaurant.walkingMinutes
                        ?.let { "$it min" }
                        ?: "Unavailable",
                )

                ResultMetric(
                    label = "Total",
                    value = restaurant.estimatedTotalMinutes
                        ?.let { "$it min" }
                        ?: "Unavailable",
                )

                ResultMetric(
                    label = "From",
                    value = restaurant.minimumMealPrice.toCop(),
                )
            }

            if (restaurant.averageRating != null) {
                Text(
                    text = "★ %.1f".format(
                        Locale.US,
                        restaurant.averageRating,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }

            if (restaurant.dietaryTags.isNotEmpty()) {
                Text(
                    text = restaurant.dietaryTags
                        .joinToString(" · ") {
                            it.label()
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }

            restaurant.recommendationReason
                ?.takeIf { it.isNotBlank() }
                ?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.campusMealColors.textPrimary,
                    )
                }

            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("View restaurant")
            }
        }
    }
}

@Composable
private fun ResultMetric(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.campusMealColors.textSecondary,
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.campusMealColors.textPrimary,
        )
    }
}

@Composable
private fun InformationBanner(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.campusMealColors.textPrimary,
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
            )

            TextButton(
                onClick = onAction,
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onChangeContext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.campusMealColors.textPrimary,
        )

        Spacer(
            modifier = Modifier.height(8.dp),
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
        )

        Spacer(
            modifier = Modifier.height(24.dp),
        )

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Try again")
        }

        Spacer(
            modifier = Modifier.height(8.dp),
        )

        OutlinedButton(
            onClick = onChangeContext,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Change context")
        }
    }
}

@Composable
private fun UnauthorizedContent(
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Session expired",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.campusMealColors.textPrimary,
        )

        Spacer(
            modifier = Modifier.height(8.dp),
        )

        Text(
            text = "Please sign in again to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
        )

        Spacer(
            modifier = Modifier.height(24.dp),
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
    "$" + "%,d".format(
        Locale.US,
        this,
    ).replace(",", ".")

private fun formatTimestamp(
    epochMillis: Long,
): String =
    DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault(),
    ).format(
        Date(epochMillis),
    )