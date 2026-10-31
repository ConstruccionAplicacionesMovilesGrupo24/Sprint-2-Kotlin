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
import com.campusmeal.android.feature.analytics.data.ReportSource
import com.campusmeal.android.feature.analytics.data.ZoneDemandFilters
import com.campusmeal.android.feature.analytics.data.ZoneDemandRepository
import com.campusmeal.android.feature.analytics.data.ZoneDemandResult
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [result] null while loading. */
data class ZoneDemandUiState(
    val filters: ZoneDemandFilters,
    val result: ZoneDemandResult? = null,
    val loading: Boolean = false,
    val validationError: String? = null,
)
/**
 * BQ9 screen state (S3-08). Bound to one session [scope]. Changing the month cancels the request of
 * the previous month, and an answer that is no longer for the shown month is dropped.
 */
class ZoneDemandReportViewModel(
    private val repository: ZoneDemandRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    
    private val initialFilters = defaultFilters(currentTimeMillis())

    private val _uiState =
        MutableStateFlow(
            ZoneDemandUiState(
                filters = initialFilters,
            ),
        )

    val uiState: StateFlow<ZoneDemandUiState> =
        _uiState.asStateFlow()

    private var loadingJob: Job? = null

   init {
        viewModelScope.launch {
            val saved = repository.savedFilters(scope)

            if (saved != null) {
                _uiState.value =
                    _uiState.value.copy(filters = saved)
            }

            load(forceRefresh = false)
        }
    }
    
    fun changeZone(zone: String) {
        val filters =
            _uiState.value.filters.copy(
                zone = zone,
            )

        _uiState.value =
            _uiState.value.copy(
                filters = filters,
                result = null,
                validationError = null,
            )

        viewModelScope.launch {
            repository.saveFilters(scope, filters)
        }

        load(forceRefresh = false)
    }
    
    fun applyDates(
        from: String,
        to: String,
    ) {
        val days = BogotaCalendar.daysBetween(from, to)

        if (
            !BogotaCalendar.isIsoDate(from) ||
            !BogotaCalendar.isIsoDate(to) ||
            days == null ||
            days !in 0..91
        ) {
            _uiState.value =
                _uiState.value.copy(
                    validationError =
                        "Use YYYY-MM-DD. The range must be between 1 and 92 days.",
                )
            return
        }

        val filters =
            _uiState.value.filters.copy(
                from = from,
                to = to,
            )

        _uiState.value =
            _uiState.value.copy(
                filters = filters,
                result = null,
                validationError = null,
            )

        viewModelScope.launch {
            repository.saveFilters(scope, filters)
        }

        load(forceRefresh = false)
    }

    fun refresh() {
    load(forceRefresh = true)
    }

    fun onReconnected() {
        val shown = _uiState.value.result

        if (
            shown !is ZoneDemandResult.Content ||
            shown.source != ReportSource.NETWORK
        ) {
            load(forceRefresh = false)
        }
    }

    private fun load(forceRefresh: Boolean) {
        loadingJob?.cancel()

        val filters = _uiState.value.filters

        _uiState.value =
            _uiState.value.copy(
                loading = true,
                validationError = null,
            )

        loadingJob =
            viewModelScope.launch {
                val result =
                    repository.report(
                        scope = scope,
                        zone = filters.zone,
                        from = filters.from,
                        to = filters.to,
                        forceRefresh = forceRefresh,
                    )

                if (
                    coordinator.isCurrent(scope) &&
                    _uiState.value.filters == filters
                ) {
                    _uiState.value =
                        _uiState.value.copy(
                            result = result,
                            loading = false,
                        )
                }
            }
    }

    companion object {
        fun factory(
            container: AppContainer,
            scope: AccountSessionScope,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ZoneDemandReportViewModel(
                        repository = container.zoneDemandRepository,
                        coordinator = container.sessionCoordinator,
                        scope = scope,
                    )
                }
            }
        private fun defaultFilters(
            nowEpochMillis: Long,
        ): ZoneDemandFilters {
            val calendar =
                Calendar.getInstance(BogotaCalendar.ZONE).apply {
                    timeInMillis = nowEpochMillis
                    add(Calendar.DAY_OF_MONTH, -29)
                }

            return ZoneDemandFilters(
                zone = "bogota-centro",
                from = BogotaCalendar.dateOf(calendar.timeInMillis),
                to = BogotaCalendar.today(nowEpochMillis),
            )
        }

    }
}

