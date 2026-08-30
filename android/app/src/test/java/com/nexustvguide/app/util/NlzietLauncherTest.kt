package com.nexustvguide.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.data.model.NlzietProgrammeTargetDto
import com.nexustvguide.app.data.model.ProgrammeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.threeten.bp.Instant
import java.util.concurrent.TimeUnit

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
        assertEquals(NlzietRelayActivity.RETRY_DELAY_MS, NlzietLauncher.COLD_START_RETRY_DELAY_MS)
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
    fun testLiveLabelDoesNotMakeHistoricalProgrammeCurrentlyAiring() {
        val historicalLiveProgramme = ProgrammeDto(
            id = "live-archive",
            channelId = "npo1",
            title = "Historische live-uitzending",
            start = "2026-08-29T18:00:00Z",
            end = "2026-08-29T19:00:00Z",
            description = null,
            imageUrl = null,
            genre = null,
            isLive = true,
            isRerun = false,
            isPremiere = false,
            ageRating = null
        )

        assertFalse(
            NlzietLauncher.isCurrentlyAiring(
                historicalLiveProgramme,
                Instant.parse("2026-08-30T12:00:00Z").toEpochMilli()
            )
        )
    }

    @Test
    fun testFutureProgrammeWithReplayTargetDoesNotOpenTheFailingPlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val future = Instant.now().plusSeconds(3600)
        val prog = ProgrammeDto(
            id = "future-1",
            channelId = "npo3",
            title = "Topdoks",
            start = future.toString(),
            end = future.plusSeconds(1200).toString(),
            description = null,
            imageUrl = null,
            genre = null,
            isLive = false,
            isRerun = false,
            isPremiere = false,
            ageRating = null,
            nlziet = NlzietProgrammeTargetDto(
                kind = "replay",
                contentItemId = "XezEafRN6kyd2J3_LcOULg",
                assetId = "108C33FB3A16FDFCE5E88B43871AC6BA",
                channelId = "npo3",
                isReplayAllowed = true,
                isRestartAllowed = true
            )
        )

        assertFalse(NlzietLauncher.hasStarted(prog))

        NlzietLauncher.launchProgramme(context, prog)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        // Geen relay en geen watchnext-URI: een nog niet begonnen uitzending heeft geen
        // replay-opname en zou in NLZIET een foutmelding tonen.
        assertFalse(started.dataString?.contains("watchnext") == true)
        assertFalse(
            started.component == ComponentName(context, NlzietRelayActivity::class.java)
        )
    }

    @Test
    fun testHasStartedBoundaries() {
        val prog = ProgrammeDto(
            id = "b",
            channelId = "npo1",
            title = "Grens",
            start = "2026-08-30T12:00:00Z",
            end = "2026-08-30T13:00:00Z",
            description = null,
            imageUrl = null,
            genre = null,
            isLive = false,
            isRerun = false,
            isPremiere = false,
            ageRating = null
        )
        val startMs = Instant.parse("2026-08-30T12:00:00Z").toEpochMilli()
        assertFalse(NlzietLauncher.hasStarted(prog, startMs - 1))
        assertTrue(NlzietLauncher.hasStarted(prog, startMs))
        assertTrue(NlzietLauncher.hasStarted(prog, startMs + 60_000))
    }

    @Test
    fun testCreateReplayDeeplinkIntent() {
        val contentItemId = "pXZD1nmyCkSuW_pB1ylCQg"
        val intent = NlzietLauncher.createReplayDeeplinkIntent(contentItemId)

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("nlziet://watchnext/$contentItemId", intent.dataString)
        assertEquals("nl.nlziet", intent.`package`)
        assertEquals(ComponentName("nl.nlziet", "nl.nlziet.tv.app.di.tv.InjectActivity"), intent.component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        // CLEAR_TOP zou InjectActivity (singleTop, root of task) herstarten in plaats van
        // onNewIntent() aan te roepen. Juist die onNewIntent-route speelt de uitzending af.
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
        assertEquals("nlziet://watchnext/$nlzietId", intent.dataString)
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
            start = "2026-08-29T18:00:00Z",
            end = "2026-08-29T19:00:00Z",
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
        val relayIntent = shadowApp.nextStartedActivity
        assertNotNull(relayIntent)

        // De klik start de relay-activity, niet direct NLZIET. De relay houdt de voorgrond vast
        // zodat de tweede (onNewIntent-)start niet door background-activity-launch geblokkeerd wordt.
        assertEquals(
            ComponentName(context, NlzietRelayActivity::class.java),
            relayIntent.component
        )

        // De relay draagt de exacte watchnext-intent als extra mee.
        val target = relayIntent.getParcelableExtra<Intent>(NlzietRelayActivity.EXTRA_TARGET_INTENT)
        assertNotNull(target)
        assertEquals(Intent.ACTION_VIEW, target!!.action)
        assertEquals("nlziet://watchnext/pXZD1nmyCkSuW_pB1ylCQg", target.dataString)
        assertEquals("nl.nlziet", target.`package`)
        assertEquals(
            ComponentName("nl.nlziet", "nl.nlziet.tv.app.di.tv.InjectActivity"),
            target.component
        )
    }

    @Test
    fun testRelaySendsTheIdenticalWatchNextIntentTwice() {
        val target = NlzietLauncher.createReplayDeeplinkIntent("pXZD1nmyCkSuW_pB1ylCQg")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val relayIntent = NlzietRelayActivity.createIntent(context, target)

        val controller = Robolectric.buildActivity(NlzietRelayActivity::class.java, relayIntent).setup()
        val shadowActivity = shadowOf(controller.get())

        // Eerste start: warmt NLZIET op bij een koude start.
        val first = shadowActivity.nextStartedActivity
        assertNotNull(first)
        assertTrue(first.filterEquals(target))

        // Direct daarna mag er nog geen tweede start zijn: twee starts achter elkaar komen
        // aantoonbaar te vroeg voor de NLZIET-router.
        assertNull(shadowActivity.nextStartedActivity)

        // Na de wachttijd volgt exact dezelfde intent; singleTop bezorgt die als onNewIntent().
        shadowOf(Looper.getMainLooper()).idleFor(
            NlzietRelayActivity.RETRY_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
        val retry = shadowActivity.nextStartedActivity
        assertNotNull(retry)
        assertTrue(retry.filterEquals(target))

        // De relay ruimt zichzelf op zodat hij niet in de weg blijft staan.
        shadowOf(Looper.getMainLooper()).idleFor(
            NlzietRelayActivity.SELF_FINISH_DELAY_MS - NlzietRelayActivity.RETRY_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun testLaunchProgrammeCurrentlyAiringWithoutExactTargetDoesNotUseUnsupportedLiveDeeplink() {
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
        assertFalse(nextStartedIntent.dataString == "nlziet://open/tv-kijken/canvas")
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
