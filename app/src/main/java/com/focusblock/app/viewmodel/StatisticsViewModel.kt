package com.focusblock.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.database.entity.BlockLog
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.utils.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

enum class StatsPeriod(val label: String, val days: Int) {
    TODAY("Today", 1),
    WEEK("This Week", 7),
    MONTH("This Month", 30)
}

data class StatisticsUiState(
    val selectedPeriod: StatsPeriod = StatsPeriod.TODAY,
    val totalBlocks: Int = 0,
    val savedTimeMillis: Long = 0,
    val mostBlockedApps: List<Pair<String, Int>> = emptyList(),
    val recentBlocks: List<BlockLog> = emptyList(),
    val dailyBlocks: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = false
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val application: Application,
    private val repository: FocusBlockRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    fun setPeriod(period: StatsPeriod) {
        _uiState.update { it.copy(selectedPeriod = period) }
        loadStats()
    }

    private fun loadStats() {
        val period = _uiState.value.selectedPeriod
        val startTime = getStartTime(period)

        viewModelScope.launch {
            // Load block logs for period
            repository.getBlockLogsSince(startTime).collect { logs ->
                val totalBlocks = logs.size

                // Calculate saved time (estimate 5 minutes saved per block)
                val savedTime = totalBlocks * 5 * 60 * 1000L

                // Group by package name for most blocked
                val mostBlocked = logs
                    .groupBy { it.packageName }
                    .mapValues { it.value.size }
                    .entries
                    .sortedByDescending { it.value }
                    .map { it.key to it.value }

                // Group by day for chart
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val dailyBlocks = logs
                    .groupBy { dateFormat.format(Date(it.timestamp)) }
                    .mapValues { it.value.size }
                    .toSortedMap()

                _uiState.update {
                    it.copy(
                        totalBlocks = totalBlocks,
                        savedTimeMillis = savedTime,
                        mostBlockedApps = mostBlocked,
                        recentBlocks = logs.sortedByDescending { log -> log.timestamp },
                        dailyBlocks = dailyBlocks,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun getStartTime(period: StatsPeriod): Long {
        return when (period) {
            StatsPeriod.TODAY -> TimeUtils.getStartOfDay()
            StatsPeriod.WEEK -> TimeUtils.getStartOfWeek()
            StatsPeriod.MONTH -> TimeUtils.getStartOfMonth()
        }
    }
}
