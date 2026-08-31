package com.nexustvguide.app.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.data.model.AppUpdateDto
import com.nexustvguide.app.data.repository.UpdateRepository
import com.nexustvguide.app.ui.update.UpdateNavigationEvent
import com.nexustvguide.app.ui.update.UpdateUiState
import com.nexustvguide.app.ui.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var application: Application
    private lateinit var fakeApiService: FakeUpdateApiService
    private lateinit var repository: UpdateRepository
    private lateinit var viewModel: UpdateViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        fakeApiService = FakeUpdateApiService()

        repository = UpdateRepository(
            context = application,
            apiService = fakeApiService,
            okHttpClient = OkHttpClient.Builder().build(),
            ioDispatcher = testDispatcher
        )

        viewModel = UpdateViewModel(application, repository, ioDispatcher = testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(UpdateUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `manual checkForUpdates transitions to UpdateAvailable when new version exists`() = testScope.runTest {
        val higherVersionCode = (BuildConfig.VERSION_CODE + 1).toLong()
        fakeApiService.dto = AppUpdateDto(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = higherVersionCode,
            versionName = "1.0.0",
            releaseNotes = "New features",
            downloadPath = "/api/v1/app/download/nexus-tv-guide-1.0.0.apk",
            sha256 = "9a4f2f9f5b66f6b0f4f33dc51d5cf834d68f7f95d1a39de6fcd09b3a51fbe123",
            fileSizeBytes = 10000,
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        viewModel.checkForUpdates(isManual = true, customBaseUrl = "http://192.168.2.171:3000/")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UpdateUiState.UpdateAvailable)
        val available = state as UpdateUiState.UpdateAvailable
        assertEquals(higherVersionCode, available.metadata.versionCode)
    }

    @Test
    fun `manual checkForUpdates transitions to UpToDate when current version is latest`() = testScope.runTest {
        fakeApiService.dto = AppUpdateDto(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            versionName = BuildConfig.VERSION_NAME,
            releaseNotes = null,
            downloadPath = "/api/v1/app/download/nexus-tv-guide-current.apk",
            sha256 = "9a4f2f9f5b66f6b0f4f33dc51d5cf834d68f7f95d1a39de6fcd09b3a51fbe123",
            fileSizeBytes = 10000,
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        viewModel.checkForUpdates(isManual = true, customBaseUrl = "http://192.168.2.171:3000/")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UpdateUiState.UpToDate)
    }

    @Test
    fun `snooze updates repository and emits DismissDialog`() = testScope.runTest {
        var eventReceived: UpdateNavigationEvent? = null
        val job = launch(testDispatcher) {
            viewModel.navEvents.collect {
                eventReceived = it
            }
        }

        viewModel.snooze(5L)
        advanceUntilIdle()

        assertEquals(UpdateUiState.Idle, viewModel.uiState.value)
        assertEquals(UpdateNavigationEvent.DismissDialog, eventReceived)
        job.cancel()
    }
}
