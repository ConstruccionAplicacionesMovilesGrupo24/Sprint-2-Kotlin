package com.campusmeal.android.feature.recipes.presentation

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.inventory.domain.model.CompleteInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.InventoryError
import com.campusmeal.android.feature.inventory.domain.model.InventoryRefreshResult
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import com.campusmeal.android.feature.recipes.data.local.RecipeFilterStore
import com.campusmeal.android.feature.recipes.data.local.RecipeFilters
import com.campusmeal.android.feature.recipes.domain.Bq3Result
import com.campusmeal.android.feature.recipes.domain.CatalogError
import com.campusmeal.android.feature.recipes.domain.CatalogRefreshResult
import com.campusmeal.android.feature.recipes.domain.RecipeEvaluation
import com.campusmeal.android.feature.recipes.domain.RecipeRepository
import com.campusmeal.android.feature.recipes.domain.RecipeUsageRecorder
import com.campusmeal.android.feature.recipes.domain.UsageAdmission
import com.campusmeal.android.feature.recipes.domain.evaluateBq3
import com.campusmeal.android.feature.recipes.domain.model.PREPARATION_MINUTES_RANGE
import com.campusmeal.android.feature.recipes.domain.model.RecipeCatalogSnapshot
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the last refresh of one input did not update it. Its stored copy, if any, is still used. */
sealed interface SourceProblem {
    data object Offline : SourceProblem
    data class Unavailable(val httpCode: Int?) : SourceProblem
    data object CatalogNotSeeded : SourceProblem
    data class Refused(val httpCode: Int) : SourceProblem
    data class InvalidResponse(val reason: String) : SourceProblem
    data object CatalogVersionConflict : SourceProblem
    data object SessionExpired : SourceProblem
}

data class SourceRefresh(val running: Boolean = false, val problem: SourceProblem? = null)

/** The complete-inventory input as shown: its own sync time and age, never the catalog's. */
data class InventorySourceUi(
    val syncedAtEpochMillis: Long,
    val stale: Boolean,
    /** A confirmed write happened after this sync (S3-03 invalidation). */
    val awaitingRefresh: Boolean,
    /** Synchronized on an earlier Bogotá date. */
    val earlierDay: Boolean,
    val refresh: SourceRefresh,
)

/** The catalog input as shown: author's version and production time, and this device's fetch time. */
data class CatalogSourceUi(
    val catalogVersion: String,
    val generatedAt: String,
    val fetchedAtEpochMillis: Long,
    val stale: Boolean,
    val refresh: SourceRefresh,
)

sealed interface RecipesContent {
    /** Room has not answered yet, or the saved filters are still loading. */
    data object Loading : RecipesContent

    /** At least one input was never stored on this device: BQ3 cannot be answered. */
    data class NoLocalData(
        val catalogMissing: Boolean,
        val inventoryMissing: Boolean,
        val catalogProblem: SourceProblem?,
        val inventoryProblem: SourceProblem?,
    ) : RecipesContent

    /** Inputs are complete; the result for the current inputs is being computed. */
    data object Evaluating : RecipesContent

    /** The answer for the current inputs. Empty [matches] is a valid answer. */
    data class Results(
        val matches: List<RecipeEvaluation>,
        val result: Bq3Result,
        val catalogIsEmpty: Boolean,
    ) : RecipesContent
}

data class RecipesUiState(
    val filters: RecipeFilters? = null,
    val inventory: InventorySourceUi? = null,
    val catalog: CatalogSourceUi? = null,
    val content: RecipesContent = RecipesContent.Loading,
) {
    /** Either input is past its TTL, invalidated, from an earlier day or failed to refresh. */
    val basedOnOlderData: Boolean
        get() = inventory?.let { it.stale || it.awaitingRefresh || it.earlierDay || it.refresh.problem != null } == true ||
            catalog?.let { it.stale || it.refresh.problem != null } == true

    val refreshing: Boolean get() = inventory?.refresh?.running == true || catalog?.refresh?.running == true
}

sealed interface RecipeDetailUi {
    data object Loading : RecipeDetailUi

    /** An input is missing, so this recipe cannot be shown against the inventory. */
    data object Unavailable : RecipeDetailUi

    /** The current catalog version no longer contains the recipe. */
    data class Removed(val catalogVersion: String?) : RecipeDetailUi

    data class Available(val evaluation: RecipeEvaluation, val catalogVersion: String) : RecipeDetailUi
}

/**
 * One Recipes visit (list plus the details opened from it), bound to the session [scope] it was created for.
 *
 * Catalog and complete inventory are observed from Room independently and refreshed in parallel, each
 * with its own status. BQ3 is re-evaluated on `Dispatchers.Default` whenever an input changes: catalog
 * version, inventory revision (sync or confirmed-write invalidation), filters or the Bogotá date.
 * `mapLatest` cancels a superseded evaluation, the evaluator checks for cancellation inside its loops, and
 * a result is only shown when its key equals the current inputs' key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipesViewModel(
    private val inventoryRepository: InventoryRepository,
    private val recipeRepository: RecipeRepository,
    private val filterStore: RecipeFilterStore,
    private val usageRecorder: RecipeUsageRecorder,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val cachePolicy: CachePolicy,
    /** Dietary preferences of the last validated meal context (memory only), used when nothing is saved. */
    private val contextDiet: Set<DietaryPreference>,
    private val savedState: SavedStateHandle,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val today = MutableStateFlow(BogotaCalendar.today(currentTimeMillis()))
    private val filters = MutableStateFlow<RecipeFilters?>(null)
    private val catalogRefresh = MutableStateFlow(SourceRefresh())
    private val inventoryRefresh = MutableStateFlow(SourceRefresh())

    /** Room answers, wrapped so "not answered yet" (null) differs from "nothing stored" (Stored(null)). */
    private val catalog = recipeRepository.observeCatalog(scope).map { Stored(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val inventory = inventoryRepository.observeCompleteInventory(scope).map { Stored(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Last five results of this session's visit (plan §5.4: BQ3 memo). Accessed on the main thread only. */
    private val memo = object : LinkedHashMap<EvaluationKey, Bq3Result>(MEMO_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EvaluationKey, Bq3Result>?) = size > MEMO_SIZE
    }

    private val evaluation: StateFlow<Evaluated?> =
        combine(catalog, inventory, filters, today) { catalog, inventory, filters, today ->
            val catalogSnapshot = catalog?.value
            val inventorySnapshot = inventory?.value
            if (catalogSnapshot == null || inventorySnapshot == null || filters == null) {
                null
            } else {
                EvaluationInput(keyOf(catalogSnapshot, inventorySnapshot, filters, today), catalogSnapshot, inventorySnapshot, filters, today)
            }
        }
            .distinctUntilChangedBy { it?.key }
            .mapLatest { input -> input?.let { Evaluated(it.key, evaluate(it)) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<RecipesUiState> =
        combine(
            combine(catalog, inventory, filters, today, ::Inputs),
            catalogRefresh,
            inventoryRefresh,
            evaluation,
        ) { inputs, catalogStatus, inventoryStatus, evaluated -> inputs.toUiState(catalogStatus, inventoryStatus, evaluated) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RecipesUiState())

    private var catalogJob: Job? = null
    private var inventoryJob: Job? = null
    private var filterSaveJob: Job? = null

    /** One id per visit, kept across configuration changes and process recreation. */
    private val visitId: String = savedState[VISIT_ID] ?: UUID.randomUUID().toString().also { savedState[VISIT_ID] = it }

    init {
        viewModelScope.launch {
            val saved = filterStore.read(scope)
            if (coordinator.isCurrent(scope)) {
                filters.value = saved ?: RecipeFilters(contextDiet, RecipeFilters.DEFAULT_MAX_PREPARATION_MINUTES)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(BogotaCalendar.millisUntilNextMidnight(currentTimeMillis()) + MIDNIGHT_MARGIN_MILLIS)
                onDateMaybeChanged()
            }
        }
    }

    /** The list became visible (entry or return to the foreground): re-check the date, refresh what is due. */
    fun onScreenStarted() {
        onDateMaybeChanged()
        refreshCatalog(force = false)
        refreshInventory(force = false)
    }

    /** Explicit "Refresh" / "Try again": both inputs, in parallel. */
    fun refreshNow() {
        refreshCatalog(force = true)
        refreshInventory(force = true)
    }

    /** Connectivity returned while visible: retry a failed input once, refresh a due one. */
    fun onReconnected() {
        refreshCatalog(force = catalogRefresh.value.problem != null)
        refreshInventory(force = inventoryRefresh.value.problem != null)
    }

    fun toggleDiet(preference: DietaryPreference) {
        val current = filters.value ?: return
        val diet = if (preference in current.dietaryPreferences) current.dietaryPreferences - preference else current.dietaryPreferences + preference
        updateFilters(current.copy(dietaryPreferences = diet))
    }

    /** Values outside 5–180 are never evaluated or stored. */
    fun setMaxPreparationMinutes(minutes: Int) {
        val current = filters.value ?: return
        if (minutes !in PREPARATION_MINUTES_RANGE || minutes == current.maxPreparationMinutes) return
        updateFilters(current.copy(maxPreparationMinutes = minutes))
    }

    /**
     * The list's main content (results or a valid empty answer) is on screen. Offers the BQ3 usage
     * interaction to the S3-09 outbox once per visit; recomposition, filter edits, refreshes and detail
     * navigation never repeat it. The visit id is the event's `clientEventId`, so even a second admission
     * of the same visit (for example after process recreation) stores one event.
     */
    fun onMainContentShown() {
        if (savedState.get<Boolean>(USAGE_OFFERED) == true) return
        savedState[USAGE_OFFERED] = true
        val occurredAt = currentTimeMillis()
        viewModelScope.launch {
            // A short local Room write: leaving the screen right away must not lose a visit that was shown.
            val admission = withContext(NonCancellable) { usageRecorder.admitRecipesUsed(scope, visitId, occurredAt) }
            when (admission) {
                UsageAdmission.Admitted -> if (BuildConfig.DEBUG) Log.d(USAGE_LOG_TAG, "FEATURE_USED(RECIPES) admitted to the outbox")
                // Never retried or presented as recorded: the outbox's own policy decided (session ended, full).
                is UsageAdmission.Rejected -> Log.i(USAGE_LOG_TAG, "FEATURE_USED(RECIPES) not admitted: ${admission.reason}")
            }
        }
    }

    /** The recipe as it is in the **current** catalog and evaluation; never an older version's copy. */
    fun detail(state: RecipesUiState, recipeId: String): RecipeDetailUi = when (val content = state.content) {
        RecipesContent.Loading, RecipesContent.Evaluating -> RecipeDetailUi.Loading
        is RecipesContent.NoLocalData -> if (content.catalogMissing) RecipeDetailUi.Unavailable else removedOrUnavailable(state, recipeId)
        is RecipesContent.Results -> content.result.evaluations[recipeId]
            ?.let { RecipeDetailUi.Available(it, checkNotNull(state.catalog).catalogVersion) }
            ?: RecipeDetailUi.Removed(state.catalog?.catalogVersion)
    }

    private fun removedOrUnavailable(state: RecipesUiState, recipeId: String): RecipeDetailUi {
        val recipes = catalog.value?.value?.catalog?.recipes.orEmpty()
        return if (recipes.none { it.id == recipeId }) RecipeDetailUi.Removed(state.catalog?.catalogVersion) else RecipeDetailUi.Unavailable
    }

    private fun updateFilters(next: RecipeFilters) {
        filters.value = next
        filterSaveJob?.cancel()
        filterSaveJob = viewModelScope.launch {
            delay(FILTER_SAVE_DEBOUNCE_MILLIS)
            filterStore.write(scope, next)
        }
    }

    private fun refreshCatalog(force: Boolean) {
        if (catalogJob?.isActive == true) return
        catalogJob = viewModelScope.launch {
            catalogRefresh.update { it.copy(running = true) }
            try {
                val result = recipeRepository.refreshCatalog(scope, force)
                if (coordinator.isCurrent(scope)) catalogRefresh.value = SourceRefresh(problem = result.toProblem())
            } finally {
                catalogRefresh.update { it.copy(running = false) }
            }
        }
    }

    private fun refreshInventory(force: Boolean) {
        if (inventoryJob?.isActive == true) return
        inventoryJob = viewModelScope.launch {
            inventoryRefresh.update { it.copy(running = true) }
            try {
                val result = inventoryRepository.refreshCompleteInventory(scope, force)
                if (coordinator.isCurrent(scope)) inventoryRefresh.value = SourceRefresh(problem = result.toProblem())
            } finally {
                inventoryRefresh.update { it.copy(running = false) }
            }
        }
    }

    private fun onDateMaybeChanged() {
        val now = BogotaCalendar.today(currentTimeMillis())
        if (now != today.value) today.value = now
    }

    /** Memo hit, or the evaluator on `Dispatchers.Default` with cooperative cancellation. */
    private suspend fun evaluate(input: EvaluationInput): Bq3Result {
        memo[input.key]?.let { return it }
        val result = withContext(Dispatchers.Default) {
            val job = coroutineContext.job
            evaluateBq3(
                recipes = input.catalog.catalog.recipes,
                inventory = input.inventory.items,
                selectedDiet = input.filters.dietaryPreferences,
                maxPreparationMinutes = input.filters.maxPreparationMinutes,
                todayIsoDate = input.today,
                checkCancelled = { job.ensureActive() },
            )
        }
        memo[input.key] = result
        return result
    }

    private fun keyOf(catalog: RecipeCatalogSnapshot, inventory: CompleteInventorySnapshot, filters: RecipeFilters, today: String) =
        EvaluationKey(
            accountId = scope.accountId,
            generation = scope.generation,
            inventoryRevision = inventory.revision,
            contractVersion = catalog.catalog.contractVersion,
            catalogVersion = catalog.catalog.catalogVersion,
            diet = filters.dietaryPreferences.map { it.name }.sorted().joinToString(","),
            maxMinutes = filters.maxPreparationMinutes,
            todayIsoDate = today,
        )

    private fun Inputs.toUiState(catalogStatus: SourceRefresh, inventoryStatus: SourceRefresh, evaluated: Evaluated?): RecipesUiState {
        val now = currentTimeMillis()
        val catalogSnapshot = catalog?.value
        val inventorySnapshot = inventory?.value
        val inventoryUi = inventorySnapshot?.let { snap ->
            InventorySourceUi(
                syncedAtEpochMillis = snap.syncedAtEpochMillis,
                stale = Freshness.synced(snap.syncedAtEpochMillis, snap.invalidatedAtEpochMillis, cachePolicy.inventory, now) is Freshness.Stale,
                awaitingRefresh = snap.invalidatedAtEpochMillis != null,
                earlierDay = BogotaCalendar.dateOf(snap.syncedAtEpochMillis) != today,
                refresh = inventoryStatus,
            )
        }
        val catalogUi = catalogSnapshot?.let { snap ->
            CatalogSourceUi(
                catalogVersion = snap.catalog.catalogVersion,
                generatedAt = snap.catalog.generatedAt,
                fetchedAtEpochMillis = snap.fetchedAtEpochMillis,
                stale = Freshness.synced(snap.fetchedAtEpochMillis, cachePolicy.recipeCatalog, now) is Freshness.Stale,
                refresh = catalogStatus,
            )
        }
        val content = when {
            catalog == null || inventory == null || filters == null -> RecipesContent.Loading
            catalogSnapshot == null || inventorySnapshot == null -> RecipesContent.NoLocalData(
                catalogMissing = catalogSnapshot == null,
                inventoryMissing = inventorySnapshot == null,
                catalogProblem = catalogStatus.problem,
                inventoryProblem = inventoryStatus.problem,
            )
            // Publish only the result computed for exactly these inputs.
            evaluated == null || evaluated.key != keyOf(catalogSnapshot, inventorySnapshot, filters, today) -> RecipesContent.Evaluating
            else -> RecipesContent.Results(
                matches = evaluated.result.matchIds.map { checkNotNull(evaluated.result.evaluations[it]) },
                result = evaluated.result,
                catalogIsEmpty = catalogSnapshot.catalog.recipes.isEmpty(),
            )
        }
        return RecipesUiState(filters = filters, inventory = inventoryUi, catalog = catalogUi, content = content)
    }

    private fun CatalogRefreshResult.toProblem(): SourceProblem? = when (this) {
        CatalogRefreshResult.Replaced,
        CatalogRefreshResult.Unchanged,
        CatalogRefreshResult.AlreadyCurrent,
        CatalogRefreshResult.SessionEnded -> null
        CatalogRefreshResult.Unauthorized -> SourceProblem.SessionExpired
        is CatalogRefreshResult.Failed -> when (val error = error) {
            CatalogError.Unreachable -> SourceProblem.Offline
            CatalogError.NotAvailableYet -> SourceProblem.CatalogNotSeeded
            is CatalogError.Server -> SourceProblem.Unavailable(error.httpCode)
            is CatalogError.Http -> SourceProblem.Refused(error.httpCode)
            is CatalogError.InvalidResponse -> SourceProblem.InvalidResponse(error.reason)
            CatalogError.SameVersionChanged -> SourceProblem.CatalogVersionConflict
        }
    }

    private fun InventoryRefreshResult.toProblem(): SourceProblem? = when (this) {
        InventoryRefreshResult.Refreshed,
        InventoryRefreshResult.AlreadyCurrent,
        InventoryRefreshResult.SessionEnded -> null
        InventoryRefreshResult.Unauthorized -> SourceProblem.SessionExpired
        is InventoryRefreshResult.Failed -> when (val error = error) {
            is InventoryError.BackendUnavailable -> if (error.httpCode == null) SourceProblem.Offline else SourceProblem.Unavailable(error.httpCode)
            is InventoryError.Http -> SourceProblem.Refused(error.code)
            InventoryError.InvalidResponse -> SourceProblem.InvalidResponse("inventory")
        }
    }

    private data class Stored<T>(val value: T?)

    private data class Inputs(
        val catalog: Stored<RecipeCatalogSnapshot>?,
        val inventory: Stored<CompleteInventorySnapshot>?,
        val filters: RecipeFilters?,
        val today: String,
    )

    /** Everything a BQ3 result depends on (plan §5.4). A new session generation never reuses a result. */
    private data class EvaluationKey(
        val accountId: String,
        val generation: Long,
        val inventoryRevision: String,
        val contractVersion: Int,
        val catalogVersion: String,
        val diet: String,
        val maxMinutes: Int,
        val todayIsoDate: String,
    )

    private class EvaluationInput(
        val key: EvaluationKey,
        val catalog: RecipeCatalogSnapshot,
        val inventory: CompleteInventorySnapshot,
        val filters: RecipeFilters,
        val today: String,
    )

    private class Evaluated(val key: EvaluationKey, val result: Bq3Result)

    companion object {
        private const val MEMO_SIZE = 5
        private const val FILTER_SAVE_DEBOUNCE_MILLIS = 500L
        private const val MIDNIGHT_MARGIN_MILLIS = 1_000L
        private const val VISIT_ID = "recipes.visitId"
        private const val USAGE_OFFERED = "recipes.usageOffered"
        private const val USAGE_LOG_TAG = "CampusMealUsage"

        fun factory(
            container: AppContainer,
            scope: AccountSessionScope,
            contextDiet: Set<DietaryPreference>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecipesViewModel(
                    inventoryRepository = container.inventoryRepository,
                    recipeRepository = container.recipeRepository,
                    filterStore = container.recipeFilterStore,
                    usageRecorder = container.recipeUsageRecorder,
                    coordinator = container.sessionCoordinator,
                    scope = scope,
                    cachePolicy = container.cachePolicy,
                    contextDiet = contextDiet,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}
