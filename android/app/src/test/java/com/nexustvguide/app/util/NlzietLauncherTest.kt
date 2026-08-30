package com.nexustvguide.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.data.model.ProgrammeDto
import org.junit.Assert.assertEquals
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
        assertEquals("market://details?id=nl.nlziet", NlzietLauncher.PLAY_STORE_MARKET_URI)
        assertEquals("https://play.google.com/store/apps/details?id=nl.nlziet", NlzietLauncher.PLAY_STORE_WEB_URL)
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
    fun testCreateDeeplinkIntent() {
        val testUri = "nlziet://watchnext/12345"
        val intent = NlzietLauncher.createDeeplinkIntent(testUri)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(testUri, intent.dataString)
        assertEquals("nl.nlziet", intent.`package`)
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
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
    fun testLaunchProgrammeWithNlzietIdTriggersDeeplink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val progWithNlzietId = ProgrammeDto(
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
            nlzietId = "widm-2026-ep3"
        )

        NlzietLauncher.launchProgramme(context, progWithNlzietId)
        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedIntent = shadowApp.nextStartedActivity
        assertNotNull(nextStartedIntent)
        assertEquals(Intent.ACTION_VIEW, nextStartedIntent.action)
        assertEquals("nlziet://watchnext/widm-2026-ep3", nextStartedIntent.dataString)
        assertEquals("nl.nlziet", nextStartedIntent.`package`)
    }
}
