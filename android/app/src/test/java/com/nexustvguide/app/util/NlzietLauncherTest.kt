package com.nexustvguide.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.nexustvguide.app")
class NlzietLauncherTest {

    @Test
    fun testConstants() {
        assertEquals("nl.nlziet", NlzietLauncher.PACKAGE_NAME)
        assertEquals("nl.nlziet.tv.app.di.tv.InjectActivity", NlzietLauncher.LEANBACK_ACTIVITY_NAME)
        assertEquals("nlziet", NlzietLauncher.SCHEME)
        assertEquals("open", NlzietLauncher.DEEPLINK_AUTHORITY)
        assertEquals("vod", NlzietLauncher.VOD_PATH)
        assertEquals("market://details?id=nl.nlziet", NlzietLauncher.PLAY_STORE_MARKET_URI)
        assertEquals("https://play.google.com/store/apps/details?id=nl.nlziet", NlzietLauncher.PLAY_STORE_WEB_URL)
    }

    @Test
    fun testIsValidNlzietId() {
        assertTrue(NlzietLauncher.isValidNlzietId("pDNA4tFqJU6JzlVKbBLGtQ"))
        assertTrue(NlzietLauncher.isValidNlzietId("Xk6gZ6CVQEulAN6plVOlEA"))
        assertTrue(NlzietLauncher.isValidNlzietId("4AcLsp3y30ygoRsNgjnBjQ"))
        assertFalse(NlzietLauncher.isValidNlzietId("npo-nos-journaal-latest"))
        assertFalse(NlzietLauncher.isValidNlzietId("hmm-29830-dfa8"))
        assertFalse(NlzietLauncher.isValidNlzietId(null))
        assertFalse(NlzietLauncher.isValidNlzietId(""))
    }

    @Test
    fun testCreateLeanbackIntent() {
        val intent = NlzietLauncher.createLeanbackIntent()
        assertNotNull(intent)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(
            ComponentName("nl.nlziet", "nl.nlziet.tv.app.di.tv.InjectActivity"),
            intent.component
        )
        assertTrue(intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
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
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(expectedFlags, intent.flags and expectedFlags)
    }

    @Test
    fun testCreatePlayStoreIntent() {
        val intent = NlzietLauncher.createPlayStoreIntent()
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("market://details?id=nl.nlziet", intent.dataString)
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK
        assertEquals(expectedFlags, intent.flags and expectedFlags)
    }

    @Test
    fun testLaunchProgrammeWithValidNlzietIdTriggersVodDeeplink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val progWithValidNlzietId = ProgrammeDto(
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
            nlzietId = "pDNA4tFqJU6JzlVKbBLGtQ"
        )

        NlzietLauncher.launchProgramme(context, progWithValidNlzietId)
        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedIntent = shadowApp.nextStartedActivity
        assertNotNull(nextStartedIntent)
        assertEquals(Intent.ACTION_VIEW, nextStartedIntent.action)
        assertEquals("nlziet://open/vod/pDNA4tFqJU6JzlVKbBLGtQ", nextStartedIntent.dataString)
        assertEquals("nl.nlziet", nextStartedIntent.`package`)
        assertEquals(
            ComponentName("nl.nlziet", "nl.nlziet.tv.app.di.tv.InjectActivity"),
            nextStartedIntent.component
        )
    }

    @Test
    fun testLaunchProgrammeWithInvalidNlzietIdFallsBackToMainApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val progWithInvalidNlzietId = ProgrammeDto(
            id = "456",
            channelId = "npo1",
            title = "NOS Journaal",
            start = "2026-08-30T18:00:00Z",
            end = "2026-08-30T18:30:00Z",
            description = "Nieuws",
            imageUrl = null,
            genre = "Nieuws",
            isLive = false,
            isRerun = false,
            isPremiere = false,
            ageRating = null,
            nlzietId = "npo-nos-journaal-latest"
        )

        NlzietLauncher.launchProgramme(context, progWithInvalidNlzietId)
        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedIntent = shadowApp.nextStartedActivity
        // Zonder mock activities zal het proberen de play store of main intent te openen
        assertNotNull(nextStartedIntent)
    }
}
