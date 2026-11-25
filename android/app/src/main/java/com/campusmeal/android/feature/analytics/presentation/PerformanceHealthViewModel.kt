package com.campusmeal.android.feature.analytics.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.analytics.data.PerformanceFilters
import com.campusmeal.android.feature.analytics.data.PerformanceHealthRepository
import com.campusmeal.android.feature.analytics.data.PerformanceHealthResult
import com.campusmeal.android.feature.analytics.data.ReportSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PerformanceHealthUiState(
    val filters: PerformanceFilters,
    val result: PerformanceHealthResult? = null,
    val loading: Boolean = false,
    val validationError: String? = null,
    /** Set after a 403: no further report is requested on this screen. */
    val accessRemoved: Boolean = false,
)

/**
 * BQ1 screen state (S4-10). Bound to one session [scope]. A filter change cancels the previous request, and
 * an answer for other filters or another session is dropped.
 */
class PerformanceHealthViewModel(
    private val repository: PerformanceHealthRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(PerformanceHealthUiState(filters = defaultFilters(clock())))
    val state: StateFlow<PerformanceHealthUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repository.savedFilters(scope)?.let { saved -> _state.update { it.copy(filters = saved) } }
            load(forceRefresh = false)
        }
    }

    fun applyDates(from: String, to: String) {
        val error = validate(from.trim(), to.trim())
        if (error != null) {
            _state.update { it.copy(validationError = error) }
        } else {
            changeFilters(_state.value.filters.copy(from = from.trim(), to = to.trim()))
        }
    }

    fun setIncludeSynthetic(include: Boolean) {
        if (include != _state.value.filters.includeSynthetic) changeFilters(_state.value.filters.copy(includeSynthetic = include))
    }

    fun refresh() = load(forceRefresh = true)

    fun onReconnected() {
        val shown = _state.value.result
        if (shown !is PerformanceHealthResult.Content || shown.source != ReportSource.NETWORK) load(forceRefresh = false)
    }

    private fun changeFilters(filters: PerformanceFilters) {
        if (_state.value.accessRemoved) return
        _state.update { it.copy(filters = filters, result = null, validationError = null) }
        viewModelScope.launch { repository.saveFilters(scope, filters) }
        load(forceRefresh = false)
    }

    private fun load(forceRefresh: Boolean) {
        if (_state.value.accessRemoved) return
        loadJob?.cancel()
        val filters = _state.value.filters
        _state.update { it.copy(loading = true, validationError = null) }
        loadJob = viewModelScope.launch {
            val result = repository.report(scope, filters, forceRefresh)
            if (coordinator.isCurrent(scope) && _state.value.filters == filters) {
                _state.update {
                    it.copy(result = result, loading = false, accessRemoved = it.accessRemoved || result == PerformanceHealthResult.Forbidden)
                }
            }
        }
    }

    private fun validate(from: String, to: String): String? {
        val days = BogotaCalendar.daysBetween(from, to)
        return when {
            !BogotaCalendar.isIsoDate(from) || !BogotaCalendar.isIsoDate(to) -> "Use dates as YYYY-MM-DD."
            days == null || days < 0 -> "The start date must be on or before the end date."
            days > MAX_RANGE_DAYS - 1 -> "The range can cover at most $MAX_RANGE_DAYS days."
            (BogotaCalendar.daysBetween(BogotaCalendar.today(clock()), to) ?: 0) > 0 -> "The end date cannot be after today (Bogotá)."
            else -> null
        }
    }

    companion object {
        private const val MAX_RANGE_DAYS = 92

        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { PerformanceHealthViewModel(container.performanceHealthRepository, container.sessionCoordinator, scope) }
        }

        /** The 30 Bogotá days ending today. */
        private fun defaultFilters(nowEpochMillis: Long): PerformanceFilters {
            val to = BogotaCalendar.today(nowEpochMillis)
            return PerformanceFilters(from = PartnerHealthViewModel.addDays(to, -29), to = to, includeSynthetic = false)
        }
    }
}
