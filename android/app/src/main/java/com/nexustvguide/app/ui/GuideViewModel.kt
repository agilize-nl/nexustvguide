package com.nexustvguide.app.ui

import android.app.Application
import android.text.SpannedString
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.egeniq.androidtvprogramguide.entity.ProgramGuideChannel
import com.egeniq.androidtvprogramguide.entity.ProgramGuideSchedule
import com.nexustvguide.app.data.model.ProgrammeDto
import com.nexustvguide.app.data.model.SimpleChannel
import com.nexustvguide.app.data.repository.GuideRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.format.DateTimeFormatter

sealed class GuideUiState {
    object Loading : GuideUiState()
    data class Content(
        val date: LocalDate,
        val channels: List<ProgramGuideChannel>,
        val schedulesByChannel: Map<String, List<ProgramGuideSchedule<ProgrammeDto>>>,
        val isStale: Boolean
    ) : GuideUiState()
    data class Error(val message: String) : GuideUiState()
}

class GuideViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GuideRepository(application)
    private val _uiState = MutableStateFlow<GuideUiState>(GuideUiState.Loading)
    val uiState: StateFlow<GuideUiState> = _uiState

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun loadGuideForDate(date: LocalDate, forceLoadingState: Boolean = false) {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (forceLoadingState || currentState !is GuideUiState.Content || currentState.date != date) {
                _uiState.value = GuideUiState.Loading
            }
            val dateStr = date.format(dateFormatter)

            try {
                val guideResponse = repository.getGuideForDate(dateStr)

                if (guideResponse != null && guideResponse.channels.isNotEmpty()) {
                    val channels: List<ProgramGuideChannel> = guideResponse.channels.map {
                        SimpleChannel(
                            id = it.id,
                            name = SpannedString(it.name),
                            imageUrl = it.logoUrl
                        )
                    }

                    val scheduleMap = mutableMapOf<String, MutableList<ProgramGuideSchedule<ProgrammeDto>>>()
                    for (ch in guideResponse.channels) {
                        scheduleMap[ch.id] = mutableListOf()
                    }

                    for (prog in guideResponse.programmes) {
                        try {
                            val startInstant = Instant.parse(prog.start)
                            val endInstant = Instant.parse(prog.end)
                            val scheduleId = prog.id.toLongOrNull() ?: prog.id.hashCode().toLong()

                            val schedule = ProgramGuideSchedule.createScheduleWithProgram(
                                scheduleId,
                                startInstant,
                                endInstant,
                                true,
                                prog.title,
                                prog
                            )

                            scheduleMap[prog.channelId]?.add(schedule)
                        } catch (e: Exception) {
                            Log.w("GuideViewModel", "Error parsing schedule for programme ${prog.id}", e)
                        }
                    }

                    _uiState.value = GuideUiState.Content(
                        date = date,
                        channels = channels,
                        schedulesByChannel = scheduleMap,
                        isStale = guideResponse.meta.stale
                    )

                    // Prefetch adjacent days
                    prefetchAdjacentDays(date)
                } else {
                    _uiState.value = GuideUiState.Error("Geen zenders of programmadata beschikbaar voor $dateStr.")
                }
            } catch (e: Exception) {
                Log.e("GuideViewModel", "Failed to load guide for $dateStr", e)
                _uiState.value = GuideUiState.Error("Fout bij laden van tv-gids: ${e.localizedMessage}")
            }
        }
    }

    private fun prefetchAdjacentDays(centerDate: LocalDate) {
        viewModelScope.launch {
            try {
                val yesterday = centerDate.minusDays(1).format(dateFormatter)
                val tomorrow = centerDate.plusDays(1).format(dateFormatter)
                repository.getGuideForDate(yesterday)
                repository.getGuideForDate(tomorrow)
            } catch (_: Exception) {}
        }
    }
}
