package com.campusmeal.android.feature.context

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto

private val ResultCardShape = RoundedCornerShape(14.dp)

data class PrototypeRestaurant(
    val name: String,
    val walkingMinutes: Int,
    val minimumPrice: Long,
    val tags: List<String>,
    val deliveryAvailable: Boolean,
)

private val prototypeRestaurants = listOf(
    PrototypeRestaurant(
        name = "Green Bowl",
        walkingMinutes = 8,
        minimumPrice = 18000,
        tags = listOf("VEGETARIAN", "VEGAN"),
        deliveryAvailable = true,
    ),
    PrototypeRestaurant(
        name = "Campus Grill",
        walkingMinutes = 12,
        minimumPrice = 22000,
        tags = emptyList(),
        deliveryAvailable = true,
    ),
    PrototypeRestaurant(
        name = "Fresh Corner",
        walkingMinutes = 6,
        minimumPrice = 16000,
        tags = listOf("VEGETARIAN", "GLUTEN_FREE"),
        deliveryAvailable = false,
    ),
    PrototypeRestaurant(
        name = "Quick Lunch",
        walkingMinutes = 18,
        minimumPrice = 14000,
        tags = listOf("GLUTEN_FREE"),
        deliveryAvailable = true,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantResultsPrototypeScreen(
    request: RestaurantSearchRequestDto?,
    onBack: () -> Unit,
    onChooseRestaurant: (String) -> Unit,
) {
    val results = if (request == null) {
        emptyList()
    } else {
        prototypeRestaurants.filter { restaurant ->

            val withinBudget =
                restaurant.minimumPrice <= request.maximumBudget

            val withinTime =
                restaurant.walkingMinutes <= request.availableMinutes

            val matchesDiet =
                request.dietaryPreferences.isEmpty() ||
                        request.dietaryPreferences.all {
                            it in restaurant.tags
                        }

            val matchesDelivery =
                !request.includeDelivery ||
                        restaurant.deliveryAvailable

            withinBudget &&
                    withinTime &&
                    matchesDiet &&
                    matchesDelivery
        }
    }

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
                    TextButton(onClick = onBack) {
                        Text("‹")
                    }
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->

        if (results.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No restaurants match this context",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.campusMealColors.textPrimary,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Try increasing your available time or budget.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )

                Spacer(Modifier.height(24.dp))

                Button(onClick = onBack) {
                    Text("Change context")
                }
            }

            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 24.dp,
                vertical = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "${results.size} restaurants match your context",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }

            items(results) { restaurant ->
                RestaurantResultCard(
                    restaurant = restaurant,
                    onChoose = {
                        onChooseRestaurant(restaurant.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun RestaurantResultCard(
    restaurant: PrototypeRestaurant,
    onChoose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ResultCardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = restaurant.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.campusMealColors.textPrimary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${restaurant.walkingMinutes} min walk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )

                Text(
                    text = "From $${restaurant.minimumPrice}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }

            if (restaurant.tags.isNotEmpty()) {
                Text(
                    text = restaurant.tags
                        .joinToString(" · ") {
                            it.replace("_", " ")
                                .lowercase()
                                .replaceFirstChar(Char::uppercase)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }

            Text(
                text = if (restaurant.deliveryAvailable) {
                    "Delivery available"
                } else {
                    "Walk-in only"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )

            Button(
                onClick = onChoose,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("View option")
            }
        }
    }
}