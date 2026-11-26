package com.campusmeal.android.feature.bq10

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SupplyGapUiState(
    val filters: SupplyGapFilters,
    val result: Bq10Result<SupplyGapResponseDto>? = null,
    val loading: Boolean = false,
    val initialized: Boolean = false,
    val validation: String? = null,
)

class SupplyGapViewModel(
    private val repository: Bq10Repository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
) : ViewModel() {
    private val today = BogotaCalendar.today(System.currentTimeMillis())
    private val first = Calendar.getInstance(BogotaCalendar.ZONE).apply { add(Calendar.DAY_OF_MONTH, -29) }
    private val mutableState = MutableStateFlow(SupplyGapUiState(SupplyGapFilters("bogota-centro", BogotaCalendar.dateOf(first.timeInMillis), today)))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    private var online = false

    fun connectionChanged(value: Boolean) {
        val wasOnline = online
        online = value
        if (!state.value.initialized) {
            if (job?.isActive == true) return
            job = viewModelScope.launch {
                try {
                    val saved = repository.savedReport(scope)
                    if (!coordinator.isCurrent(scope)) return@launch
                    mutableState.value = state.value.copy(filters = saved ?: state.value.filters, initialized = true)
                    load(force = false)
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    mutableState.value = state.value.copy(initialized = true, validation = "Could not restore filters.")
                    load(force = false)
                }
            }
        } else if (wasOnline != value) load(force = value)
    }

    fun apply(filters: SupplyGapFilters) {
        if (!state.value.initialized || state.value.result?.accessDenied == true) return
        val days = BogotaCalendar.daysBetween(filters.from, filters.to)
        if (filters.zone !in supplyZones || !BogotaCalendar.isIsoDate(filters.from) || !BogotaCalendar.isIsoDate(filters.to) || days == null || days !in 0..91) {
            mutableState.value = state.value.copy(validation = "Use valid YYYY-MM-DD dates in a range of 1–92 days.")
            return
        }
        val changed = state.value.filters != filters
        mutableState.value = state.value.copy(filters = filters, result = if (changed) null else state.value.result, validation = null)
        load(force = !changed, persist = true)
    }

    fun refresh() { if (state.value.initialized) load(force = true) }

    private fun load(force: Boolean, persist: Boolean = false) {
        job?.cancel()
        val filters = state.value.filters
        val id = ++revision
        mutableState.value = state.value.copy(loading = true)
        job = viewModelScope.launch {
            val result = try {
                if (persist) repository.saveReport(scope, filters)
                repository.report(scope, filters, force, online)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { Bq10Result<SupplyGapResponseDto>(error = "Could not read or save local data.") }
            if (coordinator.isCurrent(scope) && id == revision && state.value.filters == filters) {
                val previous = state.value.result?.content
                val displayed = if (!result.accessDenied && result.content == null && result.error != null && previous != null) result.copy(content = previous.copy(stale = true)) else result
                mutableState.value = state.value.copy(result = displayed, loading = false)
            }
        }
    }
    companion object {
        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { SupplyGapViewModel(container.bq10Repository, container.sessionCoordinator, scope) }
        }
    }
}

data class AlternativesUiState(
    val category: String? = null,
    val result: Bq10Result<AlternativeMealsResponseDto>? = null,
    val loading: Boolean = false,
    val initialized: Boolean = false,
)

class AlternativeMealsViewModel(
    private val repository: Bq10Repository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AlternativesUiState())
    val state = mutableState.asStateFlow()
    private var request: RestaurantSearchRequestDto? = null
    private var online = false
    private var job: Job? = null
    private var revision = 0L

    fun updateContext(context: RestaurantSearchRequestDto?, connected: Boolean) {
        val changed = request != context
        val connectionChanged = online != connected
        request = context
        online = connected
        if (changed || !state.value.initialized) {
            job?.cancel()
            val id = ++revision
            mutableState.value = AlternativesUiState()
            job = viewModelScope.launch {
                val saved = try { repository.savedCategory(scope) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (!coordinator.isCurrent(scope) || id != revision) return@launch
                val category = context?.foodCategory?.takeIf { it in mealCategories } ?: saved?.takeIf { it in mealCategories }
                mutableState.value = AlternativesUiState(category = category, initialized = true)
                if (category != null && context != null) load(force = false)
            }
        } else if (connectionChanged) load(force = connected)
    }

    fun choose(category: String) {
        if (!state.value.initialized || category !in mealCategories || state.value.result?.accessDenied == true) return
        val changed = state.value.category != category
        mutableState.value = state.value.copy(category = category, result = if (changed) null else state.value.result)
        load(force = !changed, persist = true)
    }
    fun refresh() { load(force = true) }

    private fun load(force: Boolean, persist: Boolean = false) {
        val context = request ?: return
        val category = state.value.category ?: return
        val query = context.copy(foodCategory = category)
        job?.cancel()
        val id = ++revision
        mutableState.value = state.value.copy(loading = true)
        job = viewModelScope.launch {
            val result = try {
                if (persist) repository.saveAlternative(scope, query)
                repository.alternatives(scope, query, force, online)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { Bq10Result<AlternativeMealsResponseDto>(error = "Could not read or save local data.") }
            if (coordinator.isCurrent(scope) && id == revision && request == context && state.value.category == category) {
                val previous = state.value.result?.content
                val displayed = if (!result.accessDenied && result.content == null && result.error != null && previous != null) result.copy(content = previous.copy(stale = true)) else result
                mutableState.value = state.value.copy(result = displayed, loading = false)
            }
        }
    }
    companion object {
        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { AlternativeMealsViewModel(container.bq10Repository, container.sessionCoordinator, scope) }
        }
    }
}

@Composable
fun SupplyGapReportRoute(viewModel: SupplyGapViewModel, online: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(online) { viewModel.connectionChanged(online) }
    SupplyGapReportScreen(state, online, onBack, viewModel::apply, viewModel::refresh)
}

@Composable
fun AlternativeMealsRoute(viewModel: AlternativeMealsViewModel, request: RestaurantSearchRequestDto?, online: Boolean, onBack: () -> Unit, onChangeContext: () -> Unit, onOpenRestaurant: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(request, online) { viewModel.updateContext(request, online) }
    AlternativeMealsScreen(state, request, online, onBack, onChangeContext, viewModel::choose, viewModel::refresh, onOpenRestaurant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bq10Page(title: String, onBack: () -> Unit, body: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) } }, windowInsets = WindowInsets(0))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { body() }
    }
}

@Composable
private fun Choice(label: String, current: String?, options: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: ${current ?: "Choose"} ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { expanded = false; onChange(item) }) }
        }
    }
}

@Composable
private fun <T> ResultNotice(result: Bq10Result<T>?, loading: Boolean, online: Boolean) {
    if (!online) Text("Offline", color = MaterialTheme.colorScheme.error)
    if (loading) CircularProgressIndicator()
    result?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    result?.content?.let { content ->
        val source = when { content.stale || !online -> "Historical snapshot — not current availability"; content.cached -> "Saved snapshot"; else -> "Server response" }
        Text("$source · Saved ${DateFormat.getDateTimeInstance().format(Date(content.fetchedAt))}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SupplyGapReportScreen(state: SupplyGapUiState, online: Boolean, onBack: () -> Unit, onApply: (SupplyGapFilters) -> Unit, onRefresh: () -> Unit) {
    var from by rememberSaveable(state.filters.from) { mutableStateOf(state.filters.from) }
    var to by rememberSaveable(state.filters.to) { mutableStateOf(state.filters.to) }
    val enabled = state.initialized && state.result?.accessDenied != true
    Bq10Page("Supply gaps (BQ10)", onBack) {
        Text("Restaurant category demand and current supply by campus zone.")
        Choice("Zone", state.filters.zone, supplyZones, enabled) { onApply(state.filters.copy(zone = it)) }
        OutlinedTextField(from, { from = it }, label = { Text("From (YYYY-MM-DD)") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(to, { to = it }, label = { Text("To (YYYY-MM-DD)") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Synthetic demand")
            Switch(checked = state.filters.synthetic, onCheckedChange = { onApply(state.filters.copy(synthetic = it)) }, enabled = enabled)
        }
        Button(onClick = { onApply(state.filters.copy(from = from, to = to)) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Apply dates") }
        OutlinedButton(onClick = onRefresh, enabled = state.initialized && online && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
        state.validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ResultNotice(state.result, state.loading, online)
        state.result?.content?.data?.let { report ->
            Text(if (report.synthetic) "SYNTHETIC DEMAND · Shared catalog" else "REAL RECORDED DEMAND", style = MaterialTheme.typography.titleSmall)
            Text("Demand: ${report.from} – ${report.to} (Bogotá)")
            Text("Supply as of: ${report.asOf}\nPolicy v${report.policyVersion} · Mapped restaurants: ${report.mappedRestaurants}")
            Text("Categorized searches: ${report.categorizedSearchIntents?.toString() ?: "Unavailable"}")
            if (report.demandSuppressed) Text("Demand metrics are protected by the minimum-user rule.")
            report.rows.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.category.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
                        Text(when (row.status) { "GAP" -> "Supply gap detected"; "NO_GAP" -> "No gap under current policy"; "NO_OBSERVATIONS" -> "No observed searches"; "SUPPRESSED" -> "Demand suppressed"; "UNKNOWN_SUPPLY" -> "Supply coverage unknown"; else -> row.status })
                        Text("Searches: ${row.searchIntents ?: "—"} · Users: ${row.distinctSearchers ?: "—"}")
                        Text("Demand share: ${row.demandShare?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "Unavailable"}")
                        Text("Available restaurants: ${row.availableRestaurants?.toString() ?: "Unknown"}")
                    }
                }
            }
            Text("Limitations", style = MaterialTheme.typography.titleMedium)
            report.limitations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun AlternativeMealsScreen(state: AlternativesUiState, request: RestaurantSearchRequestDto?, online: Boolean, onBack: () -> Unit, onChangeContext: () -> Unit, onChoose: (String) -> Unit, onRefresh: () -> Unit, onOpenRestaurant: (String) -> Unit) {
    Bq10Page("Alternative meals", onBack) {
        if (request == null) {
            Text("Set your meal context before looking for alternatives.")
            Button(onClick = onChangeContext) { Text("Set context") }
        } else {
            Text("Explore other categories without relaxing your meal constraints.")
            Text("Budget: COP ${request.maximumBudget} · Time: ${request.availableMinutes} min")
            Text("Diet: ${request.dietaryPreferences.joinToString().ifEmpty { "Any" }}")
            Choice("Original category", state.category, mealCategories, state.initialized && state.result?.accessDenied != true, onChoose)
            OutlinedButton(onClick = onRefresh, enabled = online && state.category != null && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Refresh availability") }
            TextButton(onClick = onChangeContext) { Text("Change context") }
            ResultNotice(state.result, state.loading, online)
            state.result?.content?.let { content ->
                val response = content.data
                Text("Requested category: ${response.requestedCategory} · Routes: ${response.routeProviderStatus}")
                Text("Availability checked: ${response.asOf}")
                when (response.status) {
                    "NO_COMPATIBLE_ALTERNATIVE" -> Text("No compatible alternatives in this snapshot.")
                    "REACHABILITY_UNCONFIRMED" -> Text("Walking time could not be confirmed. No unverified option is recommended.")
                }
                response.alternatives.forEach { option ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(option.meal.name ?: "Meal", style = MaterialTheme.typography.titleMedium)
                            Text(option.restaurant.name ?: "Restaurant")
                            Text("${option.substituteCategory} · COP ${option.meal.price ?: "—"} · ${option.restaurant.estimatedTotalMinutes ?: "—"} min")
                            Text("Budget: ${option.budgetCompatible} · Diet: ${option.dietCompatible} · Time: ${option.timeCompatible}")
                            Text(option.explanation, style = MaterialTheme.typography.bodySmall)
                            val restaurantId = option.restaurant.id
                            if (restaurantId != null) TextButton(onClick = { onOpenRestaurant(restaurantId) }, enabled = online && !content.stale) { Text("Restaurant details") }
                        }
                    }
                }
            }
        }
    }
}
