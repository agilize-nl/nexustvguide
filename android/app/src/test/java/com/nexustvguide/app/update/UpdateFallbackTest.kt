package com.nexustvguide.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.data.api.UpdateApiService
import com.nexustvguide.app.data.model.AppUpdateDto
import com.nexustvguide.app.data.repository.UpdateCheckResult
import com.nexustvguide.app.data.repository.UpdateRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Het noodscenario waarvoor het LAN-kanaal bestaat: het publieke kanaal is onbereikbaar,
 * en de app moet dan alsnog een update van de eigen server kunnen vinden.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateFallbackTest {

    private lateinit var context: Context
    private val nieuwereVersie = (BuildConfig.VERSION_CODE + 1).toLong()

    private val primary = UpdateChannel.primary("https://github.com/x/y/releases/latest/download/")
    private val lan = UpdateChannel.lanFallback()

    /** Geeft per kanaal een eigen antwoord: bereikbaar met DTO, of een netwerkfout. */
    private class ChannelService(
        private val dto: AppUpdateDto?,
        private val bereikbaar: Boolean
    ) : UpdateApiService {
        var opgevraagdPad: String? = null
        override suspend fun getLatestVersion(manifestPath: String): AppUpdateDto {
            opgevraagdPad = manifestPath
            if (!bereikbaar) throw IOException("kanaal onbereikbaar")
            return dto ?: throw IOException("geen dto")
        }
    }

    private fun dto(versionCode: Long) = AppUpdateDto(
        schemaVersion = 1,
        applicationId = "com.nexustvguide.app",
        versionCode = versionCode,
        versionName = "9.9.9",
        releaseNotes = "noodkanaal",
        downloadPath = "/api/v1/app/download/nexus-tv-guide-9.9.9.apk",
        sha256 = "0".repeat(64),
        fileSizeBytes = 5000,
        publishedAt = "2026-09-07T12:00:00.000Z"
    )

    private fun repo(services: Map<String, UpdateApiService>) = UpdateRepository(
        context = context,
        okHttpClient = OkHttpClient.Builder().followRedirects(false).build(),
        timeProvider = com.nexustvguide.app.update.TestTimeProviderAlias(1_000_000L),
        channelProvider = { listOf(primary, lan) },
        serviceProvider = { services.getValue(it.id) }
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(UpdateRepository.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `valt terug op het LAN als het publieke kanaal onbereikbaar is`() = runBlocking {
        val lanService = ChannelService(dto(nieuwereVersie), bereikbaar = true)
        val result = repo(
            mapOf(
                UpdateChannel.ID_PRIMARY to ChannelService(null, bereikbaar = false),
                UpdateChannel.ID_LAN to lanService
            )
        ).checkForUpdates(isManual = true)

        assertTrue("moet de update via het LAN vinden", result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(nieuwereVersie, available.metadata.versionCode)
        assertEquals(UpdateChannel.ID_LAN, available.channel?.id)
        assertEquals("LAN moet zijn eigen manifestpad gebruiken", "api/v1/app/version", lanService.opgevraagdPad)
    }

    @Test
    fun `gebruikt het publieke kanaal zolang dat werkt en raakt het LAN niet aan`() = runBlocking {
        val lanService = ChannelService(dto(nieuwereVersie), bereikbaar = true)
        val result = repo(
            mapOf(
                UpdateChannel.ID_PRIMARY to ChannelService(dto(nieuwereVersie), bereikbaar = true),
                UpdateChannel.ID_LAN to lanService
            )
        ).checkForUpdates(isManual = true)

        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(UpdateChannel.ID_PRIMARY, available.channel?.id)
        assertEquals("het LAN mag niet zijn bevraagd", null, lanService.opgevraagdPad)
    }

    @Test
    fun `een bereikbaar publiek kanaal dat up-to-date meldt zoekt niet door naar het LAN`() = runBlocking {
        // Anders zou een oudere LAN-server een nieuwere GitHub-release kunnen overrulen.
        val lanService = ChannelService(dto(nieuwereVersie), bereikbaar = true)
        val result = repo(
            mapOf(
                UpdateChannel.ID_PRIMARY to ChannelService(dto(BuildConfig.VERSION_CODE.toLong()), bereikbaar = true),
                UpdateChannel.ID_LAN to lanService
            )
        ).checkForUpdates(isManual = true)

        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("het LAN mag niet zijn bevraagd", null, lanService.opgevraagdPad)
    }

    @Test
    fun `meldt een fout als beide kanalen onbereikbaar zijn`() = runBlocking {
        val result = repo(
            mapOf(
                UpdateChannel.ID_PRIMARY to ChannelService(null, bereikbaar = false),
                UpdateChannel.ID_LAN to ChannelService(null, bereikbaar = false)
            )
        ).checkForUpdates(isManual = true)

        assertTrue(result is UpdateCheckResult.Error)
    }
}

/** Kleine tijdbron zodat deze test los staat van de fixture in UpdateRepositoryTest. */
class TestTimeProviderAlias(private val t: Long) : com.nexustvguide.app.data.repository.TimeProvider {
    override fun currentTimeMillis(): Long = t
}
