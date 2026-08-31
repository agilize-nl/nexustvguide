package com.nexustvguide.app.ui

import android.app.Application
import android.text.SpannedString
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.egeniq.androidtvprogramguide.entity.ProgramGuideChannel
import com.egeniq.androidtvprogramguide.entity.ProgramGuideSchedule
import com.nexustvguide.app.data.ChannelOrderResolver
import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.ChannelOrderPreferences
import com.nexustvguide.app.data.model.GuideResponseDto
import com.nexustvguide.app.data.model.ProgrammeDto
import com.nexustvguide.app.data.model.SimpleChannel
import com.nexustvguide.app.data.repository.ChannelOrderRepository
import com.nexustvguide.app.data.repository.GuideRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.format.DateTimeFormatter

data class PreparedGuide(
    val date: LocalDate,
    val backendChannels: List<ChannelDto>,
    val schedulesByChannel: Map<String, List<ProgramGuideSchedule<ProgrammeDto>>>,
    val isStale: Boolean
)

sealed class GuideUiState {
    object Loading : GuideUiState()
    data class Content(
        val date: LocalDate,
        val channels: List<ProgramGuideChannel>,
        val schedulesByChannel: Map<String, List<ProgramGuideSchedule<ProgrammeDto>>>,
        val isStale: Boolean
    ) : GuideUiState()
    data class AllChannelsHidden(val date: LocalDate) : GuideUiState()
    data class Error(val message: String) : GuideUiState()
}

class GuideViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: GuideRepository = GuideRepository(application),
    private val orderRepository: ChannelOrderRepository = ChannelOrderRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<GuideUiState>(GuideUiState.Loading)
    val uiState: StateFlow<GuideUiState> = _uiState

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private var currentLoadJob: Job? = null
    private var lastPreparedGuide: PreparedGuide? = null
    private var currentPrefs: ChannelOrderPreferences = orderRepository.load()
    private var activeDate: LocalDate? = null

    init {
        viewModelScope.launch {
            orderRepository.observe().collect { prefs ->
                currentPrefs = prefs
                val prepared = lastPreparedGuide
                if (prepared != null && prepared.date == activeDate) {
                    _uiState.value = projectGuide(prepared, prefs)
                }
            }
        }
    }

    fun loadGuideForDate(date: LocalDate, forceLoadingState: Boolean = false) {
        activeDate = date
        currentLoadJob?.cancel()

        val currentState = _uiState.value
        val shouldShowLoading = forceLoadingState ||
                (currentState !is GuideUiState.Content && currentState !is GuideUiState.AllChannelsHidden) ||
                (currentState is GuideUiState.Content && currentState.date != date) ||
                (currentState is GuideUiState.AllChannelsHidden && currentState.date != date)

        if (shouldShowLoading) {
            _uiState.value = GuideUiState.Loading
        }

        val dateStr = date.format(dateFormatter)

        currentLoadJob = viewModelScope.launch {
            try {
                val guideResponse = repository.getGuideForDate(dateStr)

                if (guideResponse != null && guideResponse.channels.isNotEmpty()) {
                    val prepared = prepareGuide(guideResponse, date)
                    lastPreparedGuide = prepared

                    if (activeDate == date) {
                        _uiState.value = projectGuide(prepared, currentPrefs)
                    }

                    prefetchAdjacentDays(date)
                } else {
                    if (activeDate == date) {
                        _uiState.value = GuideUiState.Error("Geen zenders of programmadata beschikbaar voor $dateStr.")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (activeDate == date) {
                    Log.e("GuideViewModel", "Failed to load guide for $dateStr", e)
                    _uiState.value = GuideUiState.Error("Fout bij laden van tv-gids: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun prepareGuide(guideResponse: GuideResponseDto, date: LocalDate): PreparedGuide {
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

        return PreparedGuide(
            date = date,
            backendChannels = guideResponse.channels,
            schedulesByChannel = scheduleMap,
            isStale = guideResponse.meta.stale
        )
    }

    private fun projectGuide(prepared: PreparedGuide, prefs: ChannelOrderPreferences): GuideUiState {
        val orderedChannels = ChannelOrderResolver.apply(prepared.backendChannels, prefs)
        if (orderedChannels.isEmpty()) {
            return GuideUiState.AllChannelsHidden(prepared.date)
        }

        val mappedChannels: List<ProgramGuideChannel> = orderedChannels.map {
            SimpleChannel(
                id = it.id,
                name = SpannedString(it.name),
                imageUrl = it.logoUrl
            )
        }

        val visibleChannelIds = orderedChannels.map { it.id }.toSet()
        val filteredSchedules = prepared.schedulesByChannel.filterKeys { it in visibleChannelIds }

        return GuideUiState.Content(
            date = prepared.date,
            channels = mappedChannels,
            schedulesByChannel = filteredSchedules,
            isStale = prepared.isStale
        )
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
