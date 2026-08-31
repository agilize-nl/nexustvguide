package com.nexustvguide.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexustvguide.app.data.ChannelOrderResolver
import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.ChannelOrderPreferences
import com.nexustvguide.app.data.repository.ChannelOrderRepository
import com.nexustvguide.app.data.repository.GuideRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Collections

data class ChannelOrderItem(
    val channel: ChannelDto,
    val isHidden: Boolean,
    val isGrabbed: Boolean = false
)

sealed class ChannelOrderUiState {
    object Loading : ChannelOrderUiState()
    data class Content(
        val items: List<ChannelOrderItem>,
        val grabbedPosition: Int? = null,
        val totalChannels: Int = items.size
    ) : ChannelOrderUiState()
    data class Error(val message: String) : ChannelOrderUiState()
}

class ChannelOrderViewModel @JvmOverloads constructor(
    application: Application,
    private val guideRepository: GuideRepository = GuideRepository(application),
    private val orderRepository: ChannelOrderRepository = ChannelOrderRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ChannelOrderUiState>(ChannelOrderUiState.Loading)
    val uiState: StateFlow<ChannelOrderUiState> = _uiState

    private var initialSnapshotBeforeGrab: List<ChannelOrderItem>? = null
    private var cachedBackendChannels: List<ChannelDto> = emptyList()

    fun loadChannels(dateStr: String) {
        _uiState.value = ChannelOrderUiState.Loading
        viewModelScope.launch {
            try {
                val backendChannels = guideRepository.getChannelsForOrdering(dateStr)
                if (backendChannels.isEmpty()) {
                    _uiState.value = ChannelOrderUiState.Error("Geen zenders gevonden.")
                    return@launch
                }

                cachedBackendChannels = backendChannels
                val prefs = orderRepository.load()
                val orderedChannels = ChannelOrderResolver.apply(
                    backendChannels,
                    prefs.copy(hiddenIds = emptySet())
                )

                val items = orderedChannels.map { channel ->
                    ChannelOrderItem(
                        channel = channel,
                        isHidden = channel.id in prefs.hiddenIds,
                        isGrabbed = false
                    )
                }

                _uiState.value = ChannelOrderUiState.Content(
                    items = items,
                    grabbedPosition = null,
                    totalChannels = items.size
                )
            } catch (e: Exception) {
                _uiState.value = ChannelOrderUiState.Error("Fout bij laden van zenders: ${e.localizedMessage}")
            }
        }
    }

    fun grabItem(position: Int) {
        val state = _uiState.value as? ChannelOrderUiState.Content ?: return
        if (position !in state.items.indices) return

        initialSnapshotBeforeGrab = state.items.map { it.copy(isGrabbed = false) }

        val updated = state.items.mapIndexed { index, item ->
            item.copy(isGrabbed = (index == position))
        }

        _uiState.value = state.copy(
            items = updated,
            grabbedPosition = position
        )
    }

    fun releaseItem(position: Int) {
        val state = _uiState.value as? ChannelOrderUiState.Content ?: return
        initialSnapshotBeforeGrab = null

        val updated = state.items.map { it.copy(isGrabbed = false) }
        _uiState.value = state.copy(
            items = updated,
            grabbedPosition = null
        )
    }

    fun cancelGrab(): Int? {
        val state = _uiState.value as? ChannelOrderUiState.Content ?: return null
        val snapshot = initialSnapshotBeforeGrab ?: return null
        val originalPos = state.grabbedPosition

        val restored = snapshot.map { it.copy(isGrabbed = false) }
        _uiState.value = state.copy(
            items = restored,
            grabbedPosition = null
        )

        // Sla de herstelde volgorde opnieuw op omdat tussenliggende stappen al geschreven waren
        val currentPrefs = orderRepository.load()
        orderRepository.save(currentPrefs.copy(orderedIds = restored.map { it.channel.id }))

        initialSnapshotBeforeGrab = null
        return originalPos
    }

    fun moveItem(fromPos: Int, toPos: Int) {
        val state = _uiState.value as? ChannelOrderUiState.Content ?: return
        if (fromPos == toPos || fromPos !in state.items.indices || toPos !in state.items.indices) return

        val list = ArrayList(state.items)
        if (fromPos < toPos) {
            for (i in fromPos until toPos) {
                Collections.swap(list, i, i + 1)
            }
        } else {
            for (i in fromPos downTo toPos + 1) {
                Collections.swap(list, i, i - 1)
            }
        }

        val updated = list.mapIndexed { index, item ->
            item.copy(isGrabbed = (index == toPos))
        }

        _uiState.value = state.copy(
            items = updated,
            grabbedPosition = toPos
        )

        // Schrijf direct de nieuwe getoonde volgorde naar orderedIds (opgeschoond van niet-backend id's)
        val currentPrefs = orderRepository.load()
        val newOrderedIds = updated.map { it.channel.id }
        orderRepository.save(currentPrefs.copy(orderedIds = newOrderedIds))
    }

    fun toggleVisibility(channelId: String) {
        val state = _uiState.value as? ChannelOrderUiState.Content ?: return
        val currentPrefs = orderRepository.load()

        val currentlyHidden = currentPrefs.hiddenIds.contains(channelId)
        val newHiddenIds = if (currentlyHidden) {
            currentPrefs.hiddenIds - channelId
        } else {
            currentPrefs.hiddenIds + channelId
        }

        orderRepository.save(currentPrefs.copy(hiddenIds = newHiddenIds))

        val updated = state.items.map { item ->
            if (item.channel.id == channelId) {
                item.copy(isHidden = !currentlyHidden)
            } else {
                item
            }
        }

        _uiState.value = state.copy(items = updated)
    }

    fun resetToDefault() {
        if (cachedBackendChannels.isEmpty()) return
        orderRepository.reset()

        val defaultItems = cachedBackendChannels.map { channel ->
            ChannelOrderItem(
                channel = channel,
                isHidden = false,
                isGrabbed = false
            )
        }

        initialSnapshotBeforeGrab = null
        _uiState.value = ChannelOrderUiState.Content(
            items = defaultItems,
            grabbedPosition = null,
            totalChannels = defaultItems.size
        )
    }
}
