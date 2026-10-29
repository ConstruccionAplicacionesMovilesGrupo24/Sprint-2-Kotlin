package com.campusmeal.android.feature.recipes.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.designsystem.SlowResponseNotice
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.inventory.presentation.formatQuantity
import com.campusmeal.android.feature.recipes.data.local.RecipeFilters
import com.campusmeal.android.feature.recipes.domain.IngredientCheck
import com.campusmeal.android.feature.recipes.domain.IngredientStatus
import com.campusmeal.android.feature.recipes.domain.RecipeEvaluation
import com.campusmeal.android.feature.recipes.domain.model.PREPARATION_MINUTES_RANGE
import kotlin.math.roundToInt

private val PagePadding = 22.dp
private val CardShape = RoundedCornerShape(14.dp)
private const val MINUTES_STEP = 5

@Composable
fun RecipesRoute(
    viewModel: RecipesViewModel,
    online: Boolean,
    onBack: () -> Unit,
    onOpenRecipe: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel) {
        viewModel.onScreenStarted()
        onStopOrDispose { }
    }
    ReconnectEffect(online, viewModel::onReconnected)

    // FEATURE_USED(RECIPES) once per visit, when the main content (results or a valid empty answer) shows.
    val mainContentShown = state.content is RecipesContent.Results
    LaunchedEffect(mainContentShown) { if (mainContentShown) viewModel.onMainContentShown() }

    RecipesScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refreshNow,
        onToggleDiet = viewModel::toggleDiet,
        onMaxMinutesChange = viewModel::setMaxPreparationMinutes,
        onOpenRecipe = onOpenRecipe,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    state: RecipesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleDiet: (DietaryPreference) -> Unit,
    onMaxMinutesChange: (Int) -> Unit,
    onOpenRecipe: (String) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RecipesTopBar(title = "Recipes", onBack = onBack) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Recipes you can cook with your inventory, diet and time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }
            state.filters?.let { filters ->
                item { FiltersCard(filters, onToggleDiet, onMaxMinutesChange) }
            }
            item { SourcesCard(state, onRefresh) }

            when (val content = state.content) {
                RecipesContent.Loading, RecipesContent.Evaluating -> item { Progress("Checking recipes…") }
                is RecipesContent.NoLocalData -> item { NoLocalDataCard(content, onRefresh) }
                is RecipesContent.Results -> {
                    item {
                        Text(
                            text = when {
                                content.catalogIsEmpty -> "The recipe catalog has no recipes yet."
                                content.matches.size == 1 -> "1 recipe matches"
                                else -> "${content.matches.size} recipes match"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.campusMealColors.textPrimary,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    if (content.matches.isEmpty() && !content.catalogIsEmpty) {
                        item { NoMatchCard(content, state.filters) }
                    }
                    items(content.matches, key = { it.recipe.id }) { evaluation ->
                        RecipeCard(evaluation, onOpen = { onOpenRecipe(evaluation.recipe.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipesTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Text("‹", style = MaterialTheme.typography.headlineSmall)
            }
        },
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersCard(
    filters: RecipeFilters,
    onToggleDiet: (DietaryPreference) -> Unit,
    onMaxMinutesChange: (Int) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.campusMealColors.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diet", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DietaryPreference.entries.forEach { preference ->
                    FilterChip(
                        selected = preference in filters.dietaryPreferences,
                        onClick = { onToggleDiet(preference) },
                        label = { Text(preference.label()) },
                    )
                }
            }
            if (filters.dietaryPreferences.isEmpty()) {
                Text("No dietary restriction", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.campusMealColors.textSecondary)
            }
            Text(
                "Maximum preparation time: ${filters.maxPreparationMinutes} min",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Slider(
                value = filters.maxPreparationMinutes.toFloat(),
                onValueChange = { value ->
                    onMaxMinutesChange(((value / MINUTES_STEP).roundToInt() * MINUTES_STEP).coerceIn(PREPARATION_MINUTES_RANGE))
                },
                valueRange = PREPARATION_MINUTES_RANGE.first.toFloat()..PREPARATION_MINUTES_RANGE.last.toFloat(),
                steps = (PREPARATION_MINUTES_RANGE.last - PREPARATION_MINUTES_RANGE.first) / MINUTES_STEP - 1,
                modifier = Modifier.semantics {
                    contentDescription = "Maximum preparation time"
                    stateDescription = "${filters.maxPreparationMinutes} minutes"
                },
            )
        }
    }
}

/** Each input with its own source time: catalog freshness never certifies the inventory, or vice versa. */
@Composable
private fun SourcesCard(state: RecipesUiState, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.basedOnOlderData) {
            Text(
                text = "Based on data as of the times below.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.campusMealColors.warningForeground,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SourceLine(inventoryLabel(state.inventory), warn = state.inventory.needsAttention())
                SourceLine(catalogLabel(state.catalog), warn = state.catalog.needsAttention())
            }
            TextButton(onClick = onRefresh, enabled = !state.refreshing) { Text("Refresh") }
        }
        if (state.refreshing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                SourceLine("Updating from CampusMeal…", warn = false)
            }
            SlowResponseNotice(active = true)
        }
    }
}

@Composable
private fun SourceLine(text: String, warn: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.campusMealColors.warningForeground else MaterialTheme.campusMealColors.textSecondary,
    )
}

private fun InventorySourceUi?.needsAttention() =
    this == null || stale || awaitingRefresh || earlierDay || refresh.problem != null

private fun CatalogSourceUi?.needsAttention() = this == null || stale || refresh.problem != null

private fun inventoryLabel(source: InventorySourceUi?): String {
    source ?: return "Inventory: not synced on this device yet"
    val base = "Inventory updated ${formatSyncedAt(source.syncedAtEpochMillis)}"
    return when {
        source.refresh.problem != null -> "$base · couldn't refresh (${source.refresh.problem.describe()})"
        source.awaitingRefresh -> "$base · your last change is not reflected yet"
        source.stale || source.earlierDay -> "$base · may be out of date"
        else -> base
    }
}

private fun catalogLabel(source: CatalogSourceUi?): String {
    source ?: return "Recipe catalog: not on this device yet"
    val base = "Catalog ${source.catalogVersion} · fetched ${formatSyncedAt(source.fetchedAtEpochMillis)}"
    return when {
        source.refresh.problem != null -> "$base · couldn't refresh (${source.refresh.problem.describe()})"
        source.stale -> "$base · older than 24 h"
        else -> base
    }
}

internal fun SourceProblem.describe(): String = when (this) {
    SourceProblem.Offline -> "offline or unreachable"
    is SourceProblem.Unavailable -> if (httpCode == null) "server unavailable" else "server unavailable, HTTP $httpCode"
    SourceProblem.CatalogNotSeeded -> "catalog not available on the server yet"
    is SourceProblem.Refused -> "refused, HTTP $httpCode"
    is SourceProblem.InvalidResponse -> "unexpected response"
    SourceProblem.CatalogVersionConflict -> "server sent different content for the same version"
    SourceProblem.SessionExpired -> "session expired"
}

@Composable
private fun NoLocalDataCard(content: RecipesContent.NoLocalData, onRetry: () -> Unit) {
    val missing = buildList {
        if (content.catalogMissing) add("the recipe catalog" + (content.catalogProblem?.let { " (${it.describe()})" } ?: ""))
        if (content.inventoryMissing) add("your complete inventory" + (content.inventoryProblem?.let { " (${it.describe()})" } ?: ""))
    }
    InfoCard(
        title = "Recipes can't be answered yet",
        message = "This device does not have ${missing.joinToString(" and ")}. Connect and try again to synchronize it.",
        action = "Try again",
        onAction = onRetry,
    )
}

@Composable
private fun NoMatchCard(content: RecipesContent.Results, filters: RecipeFilters?) {
    val summary = content.result.summary
    val reasons = buildList {
        if (summary.missingIngredient > 0) add("${summary.missingIngredient} miss an ingredient")
        if (summary.insufficientQuantity > 0) add("${summary.insufficientQuantity} need more of an ingredient")
        if (summary.unitNotComparable > 0) add("${summary.unitNotComparable} have an ingredient in a unit that can't be compared")
        if (summary.dietMismatch > 0) add("${summary.dietMismatch} don't fit your diet")
        if (summary.tooLong > 0) add("${summary.tooLong} take longer than ${filters?.maxPreparationMinutes ?: "the maximum"} min")
    }
    InfoCard(
        title = "No recipe matches right now",
        message = "Of ${content.result.evaluations.size} recipes: ${reasons.joinToString("; ")}. Only unexpired items with quantity count.",
    )
}

@Composable
private fun RecipeCard(evaluation: RecipeEvaluation, onOpen: () -> Unit) {
    val recipe = evaluation.recipe
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.campusMealColors.textPrimary)
            Text(
                text = "${recipe.preparationMinutes} min · ${servingsLabel(recipe.servings)}" +
                    recipe.dietaryTags.sortedBy { it.ordinal }.joinToString("") { " · ${it.label()}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            evaluation.ingredients.filterNot { it.ingredient.optional }.forEach { check ->
                Text("${check.ingredient.name}: ${check.availabilityText()}", style = MaterialTheme.typography.bodyMedium)
            }
            evaluation.priorityDate?.let {
                Text(
                    text = "Uses items expiring from ${BogotaCalendar.formatShortDate(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.warningForeground,
                )
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("View recipe") }
        }
    }
}

/** Detail of one recipe, resolved against the current catalog and evaluation of this visit. */
@Composable
fun RecipeDetailRoute(viewModel: RecipesViewModel, recipeId: String, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecipeDetailScreen(state = state, detail = viewModel.detail(state, recipeId), onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(state: RecipesUiState, detail: RecipeDetailUi, onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RecipesTopBar(title = "Recipe", onBack = onBack) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (detail) {
                RecipeDetailUi.Loading -> item { Progress("Loading recipe…") }
                RecipeDetailUi.Unavailable -> item {
                    InfoCard(
                        title = "This recipe can't be checked now",
                        message = "The recipe catalog or your complete inventory is not on this device. Go back and synchronize.",
                        action = "Back",
                        onAction = onBack,
                    )
                }
                is RecipeDetailUi.Removed -> item {
                    InfoCard(
                        title = "Recipe no longer available",
                        message = "It is not part of the current catalog" + (detail.catalogVersion?.let { " ($it)" } ?: "") + ".",
                        action = "Back",
                        onAction = onBack,
                    )
                }
                is RecipeDetailUi.Available -> recipeDetail(detail, state)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recipeDetail(detail: RecipeDetailUi.Available, state: RecipesUiState) {
    val evaluation = detail.evaluation
    val recipe = evaluation.recipe
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(
                text = "${recipe.preparationMinutes} min preparation · ${servingsLabel(recipe.servings)} (quantities are for the whole recipe)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            Text(
                text = if (recipe.dietaryTags.isEmpty()) "No dietary tags" else recipe.dietaryTags.sortedBy { it.ordinal }.joinToString(" · ") { it.label() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
        }
    }
    item {
        val reasons = buildList {
            if (!evaluation.requiredIngredientsOk) add("some required ingredients are missing or not enough")
            if (!evaluation.dietOk) add("it doesn't fit your selected diet")
            if (!evaluation.timeOk) add("it takes longer than ${state.filters?.maxPreparationMinutes} min")
        }
        InfoCard(
            title = if (evaluation.matches) "Matches your inventory, diet and time" else "Doesn't match right now",
            message = if (evaluation.matches) {
                "Checked against catalog ${detail.catalogVersion} and your inventory as of ${state.inventory?.let { formatSyncedAt(it.syncedAtEpochMillis) }}."
            } else {
                reasons.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
            },
        )
    }
    item { Text("Ingredients", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
    items(evaluation.ingredients, key = { "ingredient:${it.ingredient.normalizedName}" }) { check ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${check.ingredient.name} · ${formatQuantity(check.ingredient.quantity)} ${check.ingredient.unit.wire}" +
                    if (check.ingredient.optional) " · optional" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (check.ingredient.optional) {
                    "Optional: it never blocks the recipe. " + check.availabilityText()
                } else {
                    check.availabilityText()
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (check.status == IngredientStatus.AVAILABLE || check.ingredient.optional) {
                    MaterialTheme.campusMealColors.textSecondary
                } else {
                    MaterialTheme.campusMealColors.warningForeground
                },
            )
        }
    }
    item { Text("Steps", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
    itemsIndexed(recipe.steps) { index, step ->
        Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun IngredientCheck.availabilityText(): String = when (status) {
    IngredientStatus.AVAILABLE -> "you have ${formatQuantity(availableBase)} $baseUnit (needs ${formatQuantity(requiredBase)} $baseUnit)"
    IngredientStatus.INSUFFICIENT -> "only ${formatQuantity(availableBase)} $baseUnit; needs ${formatQuantity(requiredBase)} $baseUnit"
    IngredientStatus.UNIT_NOT_COMPARABLE -> "in your inventory, but in a unit that can't be compared with $baseUnit"
    IngredientStatus.MISSING -> "not in your usable inventory"
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.campusMealColors.textSecondary)
    }
}

@Composable
private fun InfoCard(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.campusMealColors.textSecondary)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun servingsLabel(servings: Int) = if (servings == 1) "1 serving" else "$servings servings"

private fun DietaryPreference.label(): String = when (this) {
    DietaryPreference.VEGETARIAN -> "Vegetarian"
    DietaryPreference.VEGAN -> "Vegan"
    DietaryPreference.GLUTEN_FREE -> "Gluten-free"
}
