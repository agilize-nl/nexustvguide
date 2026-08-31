package com.nexustvguide.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.data.api.GuideApiService
import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.ChannelOrderPreferences
import com.nexustvguide.app.data.model.GuideResponseDto
import com.nexustvguide.app.data.repository.ChannelOrderRepository
import com.nexustvguide.app.data.repository.GuideRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChannelOrderViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var application: Application
    private lateinit var context: Context
    private lateinit var orderRepository: ChannelOrderRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        context = application
        context.getSharedPreferences("channel_order", Context.MODE_PRIVATE).edit().clear().commit()
        orderRepository = ChannelOrderRepository(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeGuideApiService(
        var channelsList: List<ChannelDto> = listOf(
            ChannelDto("npo1", "npo1", "NPO 1", null, true, "npo-1", 1),
            ChannelDto("npo2", "npo2", "NPO 2", null, true, "npo-2", 2),
            ChannelDto("npo3", "npo3", "NPO 3", null, true, "npo-3", 3)
        )
    ) : GuideApiService {
        override suspend fun getChannels(): List<ChannelDto> = channelsList

        override suspend fun getGuideForDate(date: String, ifNoneMatch: String?): Response<GuideResponseDto> {
            return Response.error(404, "Not found".toResponseBody())
        }

        override suspend fun getGuideForRange(from: String, to: String, ifNoneMatch: String?): Response<GuideResponseDto> {
            return Response.error(404, "Not found".toResponseBody())
        }
    }

    @Test
    fun `loadChannels loads and formats items correctly`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val guideRepo = GuideRepository(context, fakeApi, testDispatcher)
        val viewModel = ChannelOrderViewModel(application, guideRepo, orderRepository)

        viewModel.loadChannels("2026-08-31")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ChannelOrderUiState.Content)
        val content = state as ChannelOrderUiState.Content
        assertEquals(3, content.items.size)
        assertEquals(listOf("npo1", "npo2", "npo3"), content.items.map { it.channel.id })
    }

    @Test
    fun `visibility toggle updates hiddenIds without modifying orderedIds`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val guideRepo = GuideRepository(context, fakeApi, testDispatcher)
        val viewModel = ChannelOrderViewModel(application, guideRepo, orderRepository)

        viewModel.loadChannels("2026-08-31")
        advanceUntilIdle()

        // Toggle npo2 hidden
        viewModel.toggleVisibility("npo2")
        advanceUntilIdle()

        val prefs = orderRepository.load()
        assertTrue(prefs.hiddenIds.contains("npo2"))
        assertTrue(prefs.orderedIds.isEmpty()) // orderedIds is left untouched!

        val state = viewModel.uiState.value as ChannelOrderUiState.Content
        val npo2Item = state.items.find { it.channel.id == "npo2" }
        assertTrue(npo2Item?.isHidden == true)

        // Toggle npo2 visible again
        viewModel.toggleVisibility("npo2")
        advanceUntilIdle()

        val prefsAfter = orderRepository.load()
        assertFalse(prefsAfter.hiddenIds.contains("npo2"))
        assertTrue(prefsAfter.orderedIds.isEmpty())
    }

    @Test
    fun `moveItem updates items order and immediately persists orderedIds`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val guideRepo = GuideRepository(context, fakeApi, testDispatcher)
        val viewModel = ChannelOrderViewModel(application, guideRepo, orderRepository)

        viewModel.loadChannels("2026-08-31")
        advanceUntilIdle()

        // Move item from index 2 (npo3) to index 0
        viewModel.grabItem(2)
        viewModel.moveItem(2, 0)
        advanceUntilIdle()

        val state = viewModel.uiState.value as ChannelOrderUiState.Content
        assertEquals(listOf("npo3", "npo1", "npo2"), state.items.map { it.channel.id })

        val prefs = orderRepository.load()
        assertEquals(listOf("npo3", "npo1", "npo2"), prefs.orderedIds)
    }

    @Test
    fun `cancelGrab restores and persists original order`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val guideRepo = GuideRepository(context, fakeApi, testDispatcher)
        val viewModel = ChannelOrderViewModel(application, guideRepo, orderRepository)

        viewModel.loadChannels("2026-08-31")
        advanceUntilIdle()

        viewModel.grabItem(0)
        viewModel.moveItem(0, 2)
        advanceUntilIdle()

        val movedState = viewModel.uiState.value as ChannelOrderUiState.Content
        assertEquals(listOf("npo2", "npo3", "npo1"), movedState.items.map { it.channel.id })

        viewModel.cancelGrab()
        advanceUntilIdle()

        val restoredState = viewModel.uiState.value as ChannelOrderUiState.Content
        assertEquals(listOf("npo1", "npo2", "npo3"), restoredState.items.map { it.channel.id })

        val prefs = orderRepository.load()
        assertEquals(listOf("npo1", "npo2", "npo3"), prefs.orderedIds)
    }

    @Test
    fun `resetToDefault clears preferences and restores default order`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val guideRepo = GuideRepository(context, fakeApi, testDispatcher)
        val viewModel = ChannelOrderViewModel(application, guideRepo, orderRepository)

        // Set initial modified preferences
        orderRepository.save(
            ChannelOrderPreferences(
                orderedIds = listOf("npo3", "npo2", "npo1"),
                hiddenIds = setOf("npo2")
            )
        )

        viewModel.loadChannels("2026-08-31")
        advanceUntilIdle()

        viewModel.resetToDefault()
        advanceUntilIdle()

        val prefs = orderRepository.load()
        assertTrue(prefs.orderedIds.isEmpty())
        assertTrue(prefs.hiddenIds.isEmpty())

        val state = viewModel.uiState.value as ChannelOrderUiState.Content
        assertEquals(listOf("npo1", "npo2", "npo3"), state.items.map { it.channel.id })
        assertTrue(state.items.none { it.isHidden })
    }

    @Test
    fun `empty channels result in Error state and does not overwrite existing preferences`() = testScope.runTest {
        val fakeApi = FakeGuideApiService(channelsList = emptyList())
        val guideRepo = GuideRepository(context, fakeApi, testDispatcher)
        val viewModel = ChannelOrderViewModel(application, guideRepo, orderRepository)

        val existingPrefs = ChannelOrderPreferences(orderedIds = listOf("preserved_channel"))
        orderRepository.save(existingPrefs)

        viewModel.loadChannels("2026-08-31")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ChannelOrderUiState.Error)

        val prefs = orderRepository.load()
        assertEquals(existingPrefs.orderedIds, prefs.orderedIds)
    }

    @Test
    fun `default constructor can be instantiated with only Application`() {
        val vm = ChannelOrderViewModel(application)
        assertTrue(vm != null)
    }

}
