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
import com.campusmeal.android.feature.analytics.data.PartnerHealthFilters
import com.campusmeal.android.feature.analytics.data.PartnerHealthRepository
import com.campusmeal.android.feature.analytics.data.PartnerHealthResult
import com.campusmeal.android.feature.analytics.data.ReportSource
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PartnerHealthUiState(
    val filters: PartnerHealthFilters,
    /** Null while loading. */
    val result: PartnerHealthResult? = null,
    val loading: Boolean = false,
    val validationError: String? = null,
    /** Set after a 403: the account lost analyst access, so no further report is requested on this screen. */
    val accessRemoved: Boolean = false,
)

/**
 * BQ13 screen state (S4-04). Bound to one session [scope]. A filter change cancels the previous request, and
 * an answer for other filters or another session is dropped.
 */
class PartnerHealthViewModel(
    private val repository: PartnerHealthRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(PartnerHealthUiState(filters = defaultFilters(clock())))
    val state: StateFlow<PartnerHealthUiState> = _state.asStateFlow()

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
            return
        }
        changeFilters(_state.value.filters.copy(from = from.trim(), to = to.trim()))
    }

    /** Moves the whole range by its own length (for example the previous or next 7 days). */
    fun shift(direction: Int) {
        val filters = _state.value.filters
        val length = (BogotaCalendar.daysBetween(filters.from, filters.to) ?: return) + 1
        val from = addDays(filters.from, direction * length)
        val to = addDays(filters.to, direction * length)
        val error = validate(from, to)
        if (error != null) {
            _state.update { it.copy(validationError = error) }
            return
        }
        changeFilters(filters.copy(from = from, to = to))
    }

    fun setIncludeSynthetic(include: Boolean) {
        if (include != _state.value.filters.includeSynthetic) changeFilters(_state.value.filters.copy(includeSynthetic = include))
    }

    fun refresh() = load(forceRefresh = true)

    fun onReconnected() {
        val shown = _state.value.result
        if (shown !is PartnerHealthResult.Content || shown.source != ReportSource.NETWORK) load(forceRefresh = false)
    }

    private fun changeFilters(filters: PartnerHealthFilters) {
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
                    it.copy(result = result, loading = false, accessRemoved = it.accessRemoved || result == PartnerHealthResult.Forbidden)
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
        const val MAX_RANGE_DAYS = 92

        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { PartnerHealthViewModel(container.partnerHealthRepository, container.sessionCoordinator, scope) }
        }

        /** The 7 Bogotá days ending today: the same length as the menu-freshness window. */
        fun defaultFilters(nowEpochMillis: Long): PartnerHealthFilters {
            val to = BogotaCalendar.today(nowEpochMillis)
            return PartnerHealthFilters(from = addDays(to, -6), to = to, includeSynthetic = false)
        }

        fun addDays(isoDate: String, days: Int): String {
            val calendar = Calendar.getInstance(BogotaCalendar.ZONE, Locale.US).apply {
                clear()
                set(isoDate.substring(0, 4).toInt(), isoDate.substring(5, 7).toInt() - 1, isoDate.substring(8, 10).toInt(), 12, 0)
                add(Calendar.DAY_OF_MONTH, days)
            }
            return BogotaCalendar.dateOf(calendar.timeInMillis)
        }
    }
}
