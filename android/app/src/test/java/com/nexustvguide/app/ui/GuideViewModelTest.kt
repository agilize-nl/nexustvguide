package com.nexustvguide.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.data.api.GuideApiService
import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.ChannelOrderPreferences
import com.nexustvguide.app.data.model.GuideMetaDto
import com.nexustvguide.app.data.model.GuideResponseDto
import com.nexustvguide.app.data.model.ProgrammeDto
import com.nexustvguide.app.data.repository.ChannelOrderRepository
import com.nexustvguide.app.data.repository.GuideRepository
import com.nexustvguide.app.data.repository.RemoteGuideRepository
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.threeten.bp.LocalDate
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GuideViewModelTest {

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
        context.getSharedPreferences("guide_etags", Context.MODE_PRIVATE).edit().clear().commit()
        orderRepository = ChannelOrderRepository(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createGuideResponse(
        date: String,
        channels: List<ChannelDto> = listOf(
            ChannelDto("npo1", "npo1", "NPO 1", null, true, "npo-1", 1),
            ChannelDto("npo2", "npo2", "NPO 2", null, true, "npo-2", 2)
        )
    ): GuideResponseDto {
        return GuideResponseDto(
            meta = GuideMetaDto(
                timeZone = "Europe/Amsterdam",
                date = date,
                from = "${date}T00:00:00Z",
                to = "${date}T23:59:59Z",
                lastSuccessfulRefresh = "2026-08-31T00:00:00Z",
                stale = false
            ),
            channels = channels,
            programmes = listOf(
                ProgrammeDto(
                    id = "prog1",
                    channelId = "npo1",
                    title = "Journaal",
                    description = "Nieuws",
                    start = "${date}T20:00:00Z",
                    end = "${date}T20:30:00Z",
                    genre = "Nieuws",
                    ageRating = null,
                    imageUrl = null,
                    isLive = true,
                    isPremiere = false,
                    isRerun = false
                )
            )
        )
    }

    private class FakeGuideApiService : GuideApiService {
        var networkCallCount = 0
        var responsesByDate = mutableMapOf<String, GuideResponseDto?>()

        override suspend fun getChannels(): List<ChannelDto> = emptyList()

        override suspend fun getGuideForDate(date: String, ifNoneMatch: String?): Response<GuideResponseDto> {
            networkCallCount++
            val guide = responsesByDate[date]
            return if (guide != null) {
                Response.success(guide)
            } else {
                Response.error(404, "Not found".toResponseBody())
            }
        }

        override suspend fun getGuideForRange(from: String, to: String, ifNoneMatch: String?): Response<GuideResponseDto> {
            return Response.error(404, "Not found".toResponseBody())
        }
    }

    @Test
    fun `loadGuideForDate emits Content state with channels in order`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val date = LocalDate.of(2026, 8, 31)
        val dateStr = "2026-08-31"
        fakeApi.responsesByDate[dateStr] = createGuideResponse(dateStr)

        val guideRepo = RemoteGuideRepository(context, fakeApi, testDispatcher)
        val viewModel = GuideViewModel(application, guideRepo, orderRepository)

        viewModel.loadGuideForDate(date)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is GuideUiState.Content)
        val content = state as GuideUiState.Content
        assertEquals(date, content.date)
        assertEquals(listOf("npo1", "npo2"), content.channels.map { it.id })
        assertEquals(1, content.schedulesByChannel["npo1"]?.size)
    }

    @Test
    fun `preference change updates Content state without additional network call`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val date = LocalDate.of(2026, 8, 31)
        val dateStr = "2026-08-31"
        fakeApi.responsesByDate[dateStr] = createGuideResponse(dateStr)

        val guideRepo = RemoteGuideRepository(context, fakeApi, testDispatcher)
        val viewModel = GuideViewModel(application, guideRepo, orderRepository)

        viewModel.loadGuideForDate(date)
        advanceUntilIdle()

        val callsBefore = fakeApi.networkCallCount
        assertTrue(callsBefore >= 1)
        val initialContent = viewModel.uiState.value as GuideUiState.Content
        assertEquals(listOf("npo1", "npo2"), initialContent.channels.map { it.id })

        // Change order preference: npo2 before npo1
        orderRepository.save(ChannelOrderPreferences(orderedIds = listOf("npo2", "npo1")))
        advanceUntilIdle()

        // Verify that networkCallCount is unchanged (no new network call)
        assertEquals(callsBefore, fakeApi.networkCallCount)
        val updatedContent = viewModel.uiState.value as GuideUiState.Content
        assertEquals(listOf("npo2", "npo1"), updatedContent.channels.map { it.id })
    }

    @Test
    fun `hiding all channels results in AllChannelsHidden state instead of Error`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val date = LocalDate.of(2026, 8, 31)
        val dateStr = "2026-08-31"
        fakeApi.responsesByDate[dateStr] = createGuideResponse(dateStr)

        val guideRepo = RemoteGuideRepository(context, fakeApi, testDispatcher)
        val viewModel = GuideViewModel(application, guideRepo, orderRepository)

        viewModel.loadGuideForDate(date)
        advanceUntilIdle()

        orderRepository.save(ChannelOrderPreferences(hiddenIds = setOf("npo1", "npo2")))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is GuideUiState.AllChannelsHidden)
        assertEquals(date, (state as GuideUiState.AllChannelsHidden).date)
    }

    @Test
    fun `backend delivering empty channels results in Error state`() = testScope.runTest {
        val fakeApi = FakeGuideApiService()
        val date = LocalDate.of(2026, 8, 31)
        val dateStr = "2026-08-31"
        fakeApi.responsesByDate[dateStr] = createGuideResponse(dateStr, channels = emptyList())

        val guideRepo = RemoteGuideRepository(context, fakeApi, testDispatcher)
        val viewModel = GuideViewModel(application, guideRepo, orderRepository)

        viewModel.loadGuideForDate(date)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is GuideUiState.Error)
    }

    @Test
    fun `repository propagates a cancelled request instead of returning a stale snapshot`() = testScope.runTest {
        val cancelledApi = object : GuideApiService {
            override suspend fun getChannels(): List<ChannelDto> = emptyList()

            override suspend fun getGuideForDate(
                date: String,
                ifNoneMatch: String?
            ): Response<GuideResponseDto> {
                throw CancellationException("Request replaced by a newer guide date")
            }

            override suspend fun getGuideForRange(
                from: String,
                to: String,
                ifNoneMatch: String?
            ): Response<GuideResponseDto> = Response.error(404, "Not found".toResponseBody())
        }
        val repository = RemoteGuideRepository(context, cancelledApi, testDispatcher)

        try {
            repository.getGuideForDate("2026-09-01")
            fail("A cancelled request must not return a cached guide")
        } catch (_: CancellationException) {
            // Expected: a newer date request owns the UI state.
        }
    }

    @Test
    fun `default constructor can be instantiated with only Application`() {
        val vm = GuideViewModel(application)
        assertTrue(vm != null)
    }

}
