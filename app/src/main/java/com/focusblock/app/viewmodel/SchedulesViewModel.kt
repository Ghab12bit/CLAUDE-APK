package com.focusblock.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.database.entity.Schedule
import com.focusblock.app.database.entity.ScheduleIconType
import com.focusblock.app.database.repository.FocusBlockRepository
import com.focusblock.app.utils.AppUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SchedulesUiState(
    val schedules: List<Schedule> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class SchedulesViewModel @Inject constructor(
    private val application: Application,
    private val repository: FocusBlockRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SchedulesUiState())
    val uiState: StateFlow<SchedulesUiState> = _uiState.asStateFlow()

    private val installedApps = MutableStateFlow<List<AppUtils.AppInfo>>(emptyList())

    init {
        loadSchedules()
        loadInstalledApps()
    }

    private fun loadSchedules() {
        viewModelScope.launch {
            repository.getAllSchedules().collect { schedules ->
                _uiState.update { it.copy(schedules = schedules, isLoading = false) }
            }
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = AppUtils.getInstalledApps(application, includeSystemApps = false)
            installedApps.value = apps
        }
    }

    fun getInstalledApps(): List<AppUtils.AppInfo> = installedApps.value

    fun addSchedule(schedule: Schedule) {
        viewModelScope.launch {
            repository.insertSchedule(schedule)
        }
    }

    fun updateSchedule(schedule: Schedule) {
        viewModelScope.launch {
            repository.updateSchedule(schedule)
        }
    }

    fun deleteSchedule(schedule: Schedule) {
        viewModelScope.launch {
            repository.deleteSchedule(schedule)
        }
    }

    fun toggleSchedule(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.setScheduleEnabled(id, enabled)
        }
    }

    fun createFromTemplate(type: ScheduleIconType) {
        viewModelScope.launch {
            val (name, startTime, endTime, days) = when (type) {
                ScheduleIconType.WORK -> listOf("Work Focus", 9 * 60, 17 * 60, "1,2,3,4,5")
                ScheduleIconType.SLEEP -> listOf("Sleep Hygiene", 22 * 60, 7 * 60, "1,2,3,4,5,6,7")
                ScheduleIconType.STUDY -> listOf("Study Time", 14 * 60, 18 * 60, "1,2,3,4,5")
                ScheduleIconType.FAMILY -> listOf("Family Time", 18 * 60, 21 * 60, "1,2,3,4,5,6,7")
                ScheduleIconType.SOCIAL -> listOf("Social Media Break", 0, 24 * 60, "1,2,3,4,5,6,7")
                ScheduleIconType.DETOX -> listOf("Digital Detox", 0, 24 * 60, "6,7")
                else -> listOf("Custom", 9 * 60, 17 * 60, "1,2,3,4,5")
            }

            // Get suggested apps to block
            val suggestedApps = installedApps.value
                .filter { AppUtils.SUGGESTED_APPS_TO_BLOCK.contains(it.packageName) }
                .map { it.packageName }

            val schedule = Schedule(
                name = name as String,
                iconType = type,
                startTimeMinutes = startTime as Int,
                endTimeMinutes = endTime as Int,
                daysOfWeek = days as String,
                blockedPackages = suggestedApps.joinToString(","),
                isEnabled = false // Let user enable after reviewing
            )

            repository.insertSchedule(schedule)
        }
    }
}
