package com.nexustvguide.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.data.model.NlzietProgrammeTargetDto
import com.nexustvguide.app.data.model.ProgrammeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.threeten.bp.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.nexustvguide.app")
class NlzietLauncherTest {

    @Test
    fun testConstants() {
        assertEquals("nl.nlziet", NlzietLauncher.PACKAGE_NAME)
        assertEquals("nl.nlziet.tv.app.di.tv.InjectActivity", NlzietLauncher.LEANBACK_ACTIVITY_NAME)
        assertEquals("nlziet", NlzietLauncher.SCHEME)
        assertEquals("open", NlzietLauncher.DEEPLINK_AUTHORITY)
        assertEquals("epg", NlzietLauncher.EPG_PATH)
        assertEquals("tv-kijken", NlzietLauncher.LIVE_PATH)
        assertEquals("vod", NlzietLauncher.VOD_PATH)
        assertEquals("market://details?id=nl.nlziet", NlzietLauncher.PLAY_STORE_MARKET_URI)
        assertEquals("https://play.google.com/store/apps/details?id=nl.nlziet", NlzietLauncher.PLAY_STORE_WEB_URL)
    }

    @Test
    fun testValidation() {
        // ContentItemId (22 tekens base64url)
        assertTrue(NlzietLauncher.isValidContentItemId("pDNA4tFqJU6JzlVKbBLGtQ"))
        assertTrue(NlzietLauncher.isValidContentItemId("pXZD1nmyCkSuW_pB1ylCQg"))
        assertFalse(NlzietLauncher.isValidContentItemId("short-id"))
        assertFalse(NlzietLauncher.isValidContentItemId("npo-nos-journaal-latest"))
        assertFalse(NlzietLauncher.isValidContentItemId(null))
        assertFalse(NlzietLauncher.isValidContentItemId(""))

        // AssetId (32 hex tekens)
        assertTrue(NlzietLauncher.isValidAssetId("108C33FB3A16FDFCE5E88B43871AC6BA"))
        assertTrue(NlzietLauncher.isValidAssetId("108c33fb3a16fdfce5e88b43871ac6ba"))
        assertFalse(NlzietLauncher.isValidAssetId("108C33FB3A16FDFCE5E88B43871AC6B")) // 31 tekens
        assertFalse(NlzietLauncher.isValidAssetId("108C33FB3A16FDFCE5E88B43871AC6BAZZ")) // 34 tekens / non-hex
        assertFalse(NlzietLauncher.isValidAssetId(null))

        // ChannelId
        assertTrue(NlzietLauncher.isValidChannelId("npo1"))
        assertTrue(NlzietLauncher.isValidChannelId("canvas"))
        assertTrue(NlzietLauncher.isValidChannelId("bbcone"))
        assertFalse(NlzietLauncher.isValidChannelId(null))
        assertFalse(NlzietLauncher.isValidChannelId(""))

        // EpgTarget DTO
        val validTarget = NlzietProgrammeTargetDto(
            kind = "replay",
            contentItemId = "pXZD1nmyCkSuW_pB1ylCQg",
            assetId = "108C33FB3A16FDFCE5E88B43871AC6BA",
            channelId = "npo1",
            isReplayAllowed = true,
            isRestartAllowed = true
        )
        assertTrue(NlzietLauncher.isValidEpgTarget(validTarget))

        val invalidTarget = validTarget.copy(contentItemId = "too-short")
        assertFalse(NlzietLauncher.isValidEpgTarget(invalidTarget))
    }

    @Test
    fun testCreateReplayDeeplinkIntent() {
        val contentItemId = "pXZD1nmyCkSuW_pB1ylCQg"
        val assetId = "108C33FB3A16FDFCE5E88B43871AC6BA"
        val intent = NlzietLauncher.createReplayDeeplinkIntent(contentItemId, assetId)

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("nlziet://open/epg/$contentItemId/$assetId", intent.dataString)
        assertEquals("nl.nlziet", intent.`package`)
        assertEquals(ComponentName("nl.nlziet", "nl.nlziet.tv.app.di.tv.InjectActivity"), intent.component)
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(expectedFlags, intent.flags and expectedFlags)
    }

    @Test
    fun testCreateLiveDeeplinkIntent() {
        val channelId = "npo1"
        val intent = NlzietLauncher.createLiveDeeplinkIntent(channelId)

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("nlziet://open/tv-kijken/$channelId", intent.dataString)
        assertEquals("nl.nlziet", intent.`package`)
        assertEquals(ComponentName("nl.nlziet", "nl.nlziet.tv.app.di.tv.InjectActivity"), intent.component)
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(expectedFlags, intent.flags and expectedFlags)
    }

    @Test
    fun testCreateVodDeeplinkIntent() {
        val nlzietId = "pDNA4tFqJU6JzlVKbBLGtQ"
        val intent = NlzietLauncher.createVodDeeplinkIntent(nlzietId)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("nlziet://open/vod/$nlzietId", intent.dataString)
        assertEquals("nl.nlziet", intent.`package`)
        assertEquals(ComponentName("nl.nlziet", "nl.nlziet.tv.app.di.tv.InjectActivity"), intent.component)
    }

    @Test
    fun testLaunchProgrammeWithReplayTargetTriggersReplayDeeplink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prog = ProgrammeDto(
            id = "123",
            channelId = "npo1",
            title = "Wie is de Mol?",
            start = "2026-08-30T18:00:00Z",
            end = "2026-08-30T19:00:00Z",
            description = "Spannende aflevering",
            imageUrl = null,
            genre = "Spel",
            isLive = false,
            isRerun = false,
            isPremiere = false,
            ageRating = "12",
            nlziet = NlzietProgrammeTargetDto(
                kind = "replay",
                contentItemId = "pXZD1nmyCkSuW_pB1ylCQg",
                assetId = "108C33FB3A16FDFCE5E88B43871AC6BA",
                channelId = "npo1",
                isReplayAllowed = true,
                isRestartAllowed = true
            )
        )

        NlzietLauncher.launchProgramme(context, prog)
        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedIntent = shadowApp.nextStartedActivity
        assertNotNull(nextStartedIntent)
        assertEquals(Intent.ACTION_VIEW, nextStartedIntent.action)
        assertEquals("nlziet://open/epg/pXZD1nmyCkSuW_pB1ylCQg/108C33FB3A16FDFCE5E88B43871AC6BA", nextStartedIntent.dataString)
        assertEquals("nl.nlziet", nextStartedIntent.`package`)
        assertEquals(
            ComponentName("nl.nlziet", "nl.nlziet.tv.app.di.tv.InjectActivity"),
            nextStartedIntent.component
        )
    }

    @Test
    fun testLaunchProgrammeCurrentlyAiringWithoutReplayTriggersLiveDeeplink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.now()
        val startIso = now.minusSeconds(600).toString()
        val endIso = now.plusSeconds(1200).toString()

        val liveProg = ProgrammeDto(
            id = "456",
            channelId = "vrtcanvas",
            title = "Terzake Live",
            start = startIso,
            end = endIso,
            description = "Nieuws",
            imageUrl = null,
            genre = "Nieuws",
            isLive = true,
            isRerun = false,
            isPremiere = false,
            ageRating = null,
            nlziet = null
        )

        NlzietLauncher.launchProgramme(context, liveProg)
        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedIntent = shadowApp.nextStartedActivity
        assertNotNull(nextStartedIntent)
        assertEquals(Intent.ACTION_VIEW, nextStartedIntent.action)
        // vrtcanvas maps to canvas
        assertEquals("nlziet://open/tv-kijken/canvas", nextStartedIntent.dataString)
    }

    @Test
    fun testLaunchProgrammeLegacyNlzietIdIgnoredAndFallsBackToMainApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Toekomstig programma met alleen legacy nlzietId
        val futureProg = ProgrammeDto(
            id = "789",
            channelId = "npo1",
            title = "Studio Sport",
            start = "2026-09-05T18:00:00Z",
            end = "2026-09-05T19:00:00Z",
            description = "Sport",
            imageUrl = null,
            genre = "Sport",
            isLive = false,
            isRerun = false,
            isPremiere = false,
            ageRating = null,
            nlziet = null,
            nlzietId = "pDNA4tFqJU6JzlVKbBLGtQ"
        )

        NlzietLauncher.launchProgramme(context, futureProg)
        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedIntent = shadowApp.nextStartedActivity
        // Negeert legacy nlzietId en opent niet de VOD route nlziet://open/vod/..., maar valt terug op de main app launch / store
        assertNotNull(nextStartedIntent)
        assertFalse(nextStartedIntent.dataString?.contains("vod") == true)
    }
}
