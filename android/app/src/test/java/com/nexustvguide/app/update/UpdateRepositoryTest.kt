package com.nexustvguide.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.data.api.UpdateApiService
import com.nexustvguide.app.data.model.AppUpdateDto
import com.nexustvguide.app.data.repository.DownloadState
import com.nexustvguide.app.data.repository.TimeProvider
import com.nexustvguide.app.data.repository.UpdateCheckResult
import com.nexustvguide.app.data.repository.UpdateError
import com.nexustvguide.app.data.repository.UpdateRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class TestTimeProvider(var time: Long) : TimeProvider {
    override fun currentTimeMillis(): Long = time
}

class FakeUpdateApiService : UpdateApiService {
    var dto: AppUpdateDto? = null
    var shouldThrow: Boolean = false

    override suspend fun getLatestVersion(): AppUpdateDto {
        if (shouldThrow) {
            throw IOException("Simulated network error")
        }
        return dto ?: throw IOException("No DTO set")
    }
}

@RunWith(RobolectricTestRunner::class)
class UpdateRepositoryTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var fakeApiService: FakeUpdateApiService
    private lateinit var timeProvider: TestTimeProvider
    private lateinit var repository: UpdateRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer()
        mockWebServer.start()

        timeProvider = TestTimeProvider(1000000L)
        fakeApiService = FakeUpdateApiService()

        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        repository = UpdateRepository(
            context = context,
            apiService = fakeApiService,
            okHttpClient = okHttpClient,
            timeProvider = timeProvider
        )

        // Clear prefs
        context.getSharedPreferences(UpdateRepository.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        repository.cleanupOldUpdateFiles()
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `checkForUpdates returns UpdateAvailable when remote version is strictly higher`() = runBlocking {
        val baseUrl = mockWebServer.url("/").toString()
        val higherVersionCode = (BuildConfig.VERSION_CODE + 1).toLong()

        fakeApiService.dto = AppUpdateDto(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = higherVersionCode,
            versionName = "1.0.0",
            releaseNotes = "Major release",
            downloadPath = "/api/v1/app/download/nexus-tv-guide-1.0.0.apk",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            fileSizeBytes = 5000,
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        val result = repository.checkForUpdates(isManual = true, customBaseUrl = baseUrl)
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(higherVersionCode, available.metadata.versionCode)
        assertFalse(available.isSnoozed)
    }

    @Test
    fun `checkForUpdates returns UpToDate when remote version is equal or lower`() = runBlocking {
        val baseUrl = mockWebServer.url("/").toString()
        val currentVersionCode = BuildConfig.VERSION_CODE.toLong()

        fakeApiService.dto = AppUpdateDto(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = currentVersionCode,
            versionName = BuildConfig.VERSION_NAME,
            releaseNotes = "Current version",
            downloadPath = "/api/v1/app/download/nexus-tv-guide-current.apk",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            fileSizeBytes = 5000,
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        val result = repository.checkForUpdates(isManual = true, customBaseUrl = baseUrl)
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `checkForUpdates returns Error on network failure`() = runBlocking {
        val baseUrl = mockWebServer.url("/").toString()
        fakeApiService.shouldThrow = true

        val result = repository.checkForUpdates(isManual = true, customBaseUrl = baseUrl)
        assertTrue(result is UpdateCheckResult.Error)
    }

    @Test
    fun `passive check respects 24h throttle while manual check bypasses it`() = runBlocking {
        val baseUrl = mockWebServer.url("/").toString()
        val higherVersionCode = (BuildConfig.VERSION_CODE + 1).toLong()

        fakeApiService.dto = AppUpdateDto(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = higherVersionCode,
            versionName = "1.0.0",
            releaseNotes = "Notes",
            downloadPath = "/api/v1/app/download/nexus-tv-guide-1.0.0.apk",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            fileSizeBytes = 5000,
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        // 1. Eerste passieve check: slaagt en stempelt tijd af
        val res1 = repository.checkForUpdates(isManual = false, customBaseUrl = baseUrl)
        assertTrue(res1 is UpdateCheckResult.UpdateAvailable)

        // 2. Tweede passieve check na 2 uur: throttled
        timeProvider.time += 2 * 60 * 60 * 1000L
        val res2 = repository.checkForUpdates(isManual = false, customBaseUrl = baseUrl)
        assertTrue(res2 is UpdateCheckResult.Throttled)

        // 3. Handmatige check na 2 uur: niet gethrottled
        val res3 = repository.checkForUpdates(isManual = true, customBaseUrl = baseUrl)
        assertTrue(res3 is UpdateCheckResult.UpdateAvailable)

        // 4. Derde passieve check na 25 uur: slaagt
        timeProvider.time += 23 * 60 * 60 * 1000L // Totaal 25 uur
        val res4 = repository.checkForUpdates(isManual = false, customBaseUrl = baseUrl)
        assertTrue(res4 is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun `downloadApk downloads, verifies hash, and emits progress and success`() = runBlocking {
        val apkPayload = "APK_BINARY_DATA_TEST_12345".toByteArray()
        val hash = sha256(apkPayload)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setHeader("Content-Length", apkPayload.size)
                .setBody(Buffer().write(apkPayload))
        )

        val downloadUrl = mockWebServer.url("/api/v1/app/download/nexus-tv-guide-1.0.0.apk").toString()
        val metadata = ValidatedUpdateMetadata(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = 2L,
            versionName = "1.0.0",
            releaseNotes = "Test",
            downloadUrl = downloadUrl,
            sha256 = hash,
            fileSizeBytes = apkPayload.size.toLong(),
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        val states = repository.downloadApk(metadata).toList()
        assertTrue(states.isNotEmpty())

        val lastState = states.last()
        assertTrue(lastState is DownloadState.Success)
        val success = lastState as DownloadState.Success
        assertTrue(success.apkFile.exists())
        assertEquals(apkPayload.size.toLong(), success.apkFile.length())
    }

    @Test
    fun `downloadApk fails and deletes part file on checksum mismatch`() = runBlocking {
        val apkPayload = "APK_BINARY_CORRUPTED".toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setHeader("Content-Length", apkPayload.size)
                .setBody(Buffer().write(apkPayload))
        )

        val downloadUrl = mockWebServer.url("/api/v1/app/download/nexus-tv-guide-1.0.0.apk").toString()
        val metadata = ValidatedUpdateMetadata(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = 2L,
            versionName = "1.0.0",
            releaseNotes = "Test",
            downloadUrl = downloadUrl,
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000", // Mismatch
            fileSizeBytes = apkPayload.size.toLong(),
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        val states = repository.downloadApk(metadata).toList()
        val lastState = states.last()
        assertTrue(lastState is DownloadState.Failed)
        val failed = lastState as DownloadState.Failed
        assertTrue(failed.error is UpdateError.ChecksumMismatch)

        // Part file must be deleted
        val partFile = File(repository.getUpdatesDir(), "update_2.apk.part")
        assertFalse(partFile.exists())
    }

    @Test
    fun `downloadApk fails on HTTP 500 error`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Server Error")
        )

        val downloadUrl = mockWebServer.url("/api/v1/app/download/nexus-tv-guide-1.0.0.apk").toString()
        val metadata = ValidatedUpdateMetadata(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = 2L,
            versionName = "1.0.0",
            releaseNotes = "Test",
            downloadUrl = downloadUrl,
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            fileSizeBytes = 1000,
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        val states = repository.downloadApk(metadata).toList()
        val lastState = states.last()
        assertTrue(lastState is DownloadState.Failed)
        assertTrue((lastState as DownloadState.Failed).error is UpdateError.NetworkError)
    }

    @Test
    fun `downloadApk fails on Content-Length mismatch`() = runBlocking {
        val apkPayload = "APK_DATA".toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody(Buffer().write(apkPayload))
        )

        val downloadUrl = mockWebServer.url("/api/v1/app/download/nexus-tv-guide-1.0.0.apk").toString()
        val metadata = ValidatedUpdateMetadata(
            schemaVersion = 1,
            applicationId = "com.nexustvguide.app",
            versionCode = 2L,
            versionName = "1.0.0",
            releaseNotes = "Test",
            downloadUrl = downloadUrl,
            sha256 = sha256(apkPayload),
            fileSizeBytes = 5000L, // Expected 5000 bytes, but server sends apkPayload.size bytes
            publishedAt = "2026-08-31T12:00:00.000Z"
        )

        val states = repository.downloadApk(metadata).toList()
        val lastState = states.last()
        assertTrue(lastState is DownloadState.Failed)
        assertTrue((lastState as DownloadState.Failed).error is UpdateError.ContractError)
    }

    @Test
    fun `snooze and clearSnooze persist correctly`() {
        repository.snoozeUpdate(42L)
        val prefs = context.getSharedPreferences(UpdateRepository.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(42L, prefs.getLong(UpdateRepository.KEY_SNOOZED_VERSION, -1L))

        repository.clearSnooze()
        assertEquals(-1L, prefs.getLong(UpdateRepository.KEY_SNOOZED_VERSION, -1L))
    }

    @Test
    fun `pending session persistence and reconciliation`() {
        repository.savePendingSession(1234, 100L)
        assertEquals(1234, repository.getPendingSessionId())
        assertEquals(100L, repository.getPendingTargetVersionCode())

        // Target version 100L is not yet reached by current BuildConfig.VERSION_CODE (which is <= 10)
        assertFalse(repository.reconcileStartupState())
        assertEquals(1234, repository.getPendingSessionId())

        // If target version is equal or lower than current BuildConfig.VERSION_CODE
        repository.savePendingSession(5678, BuildConfig.VERSION_CODE.toLong())
        assertTrue(repository.reconcileStartupState())
        assertNull(repository.getPendingSessionId())
        assertNull(repository.getPendingTargetVersionCode())
    }

    @Test
    fun `cleanupOldUpdateFiles removes old apk and part files`() {
        val dir = repository.getUpdatesDir()
        val oldApk = File(dir, "update_1.apk").apply { writeText("old") }
        val oldPart = File(dir, "update_1.apk.part").apply { writeText("part") }
        val currentApk = File(dir, "update_2.apk").apply { writeText("current") }

        repository.cleanupOldUpdateFiles(excludeVersionCode = 2L)

        assertFalse(oldApk.exists())
        assertFalse(oldPart.exists())
        assertTrue(currentApk.exists())
    }
}
