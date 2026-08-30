package com.nexustvguide.app.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.nexustvguide.app.R
import com.nexustvguide.app.data.model.NlzietProgrammeTargetDto
import com.nexustvguide.app.data.model.ProgrammeDto
import org.threeten.bp.Instant

/**
 * Centrale launcher en deeplink builder voor NLZIET Android TV (Leanback UI).
 *
 * Volgt de veilige NLZIET Android TV Leanback routeringshiërarchie:
 * 1. Exacte replay target (isReplayAllowed == true) -> nlziet://watchnext/<contentItemId>
 * 2. Lopende uitzending met restart target (isRestartAllowed == true) -> nlziet://watchnext/<contentItemId>
 * 3. Geen aantoonbaar afspeelbaar target -> NLZIET Leanback UI
 */
object NlzietLauncher {

    const val TAG = "NlzietLauncher"

    const val PACKAGE_NAME = "nl.nlziet"
    const val LEANBACK_ACTIVITY_NAME = "nl.nlziet.tv.app.di.tv.InjectActivity"

    const val SCHEME = "nlziet"
    const val WATCHNEXT_AUTHORITY = "watchnext"
    const val DEEPLINK_AUTHORITY = "open"
    const val EPG_PATH = "epg"
    const val LIVE_PATH = "tv-kijken"
    const val VOD_PATH = "vod"

    /**
     * NLZIET TV 5.15.3 verwerkt watchnext alleen in InjectActivity.onNewIntent(). Bij een
     * koude start opent de eerste intent de activity, maar wordt de URI niet afgehandeld.
     * Een identieke intent na de initialisatie wordt wel als onNewIntent ontvangen.
     */
    const val COLD_START_RETRY_DELAY_MS = NlzietRelayActivity.RETRY_DELAY_MS

    const val PLAY_STORE_MARKET_URI = "market://details?id=nl.nlziet"
    const val PLAY_STORE_WEB_URL = "https://play.google.com/store/apps/details?id=nl.nlziet"

    private val CHANNEL_ID_MAP = mapOf(
        "vrtcanvas" to "canvas",
        "bbc1" to "bbcone",
        "bbc2" to "bbctwo"
    )

    /**
     * Valideert of een string een authentieke NLZIET base64url content-ID (22 tekens) is.
     */
    fun isValidContentItemId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (!id.matches(Regex("^[a-zA-Z0-9_-]{22}$"))) return false
        if (id.contains("-latest") || id.contains("latest")) return false
        return true
    }

    /**
     * Valideert of een string een authentieke NLZIET asset-ID (32 hex tekens) is.
     */
    fun isValidAssetId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return id.matches(Regex("^[a-fA-F0-9]{32}$"))
    }

    /**
     * Valideert of een zender-ID veilig en niet-leeg is.
     */
    fun isValidChannelId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return id.matches(Regex("^[a-zA-Z0-9_-]+$"))
    }

    /**
     * Valideert een volledig EPG replay/restart target DTO.
     */
    fun isValidEpgTarget(target: NlzietProgrammeTargetDto?): Boolean {
        if (target == null) return false
        return isValidContentItemId(target.contentItemId) &&
                isValidAssetId(target.assetId) &&
                isValidChannelId(target.channelId)
    }

    /**
     * Legacy validatiefunctie voor VOD content-IDs.
     */
    fun isValidNlzietId(id: String?): Boolean {
        if (!isValidContentItemId(id)) return false
        return id!!.any { it.isUpperCase() || it.isDigit() }
    }

    /**
     * Bepaalt de technische NLZIET-zender-ID voor een programma.
     */
    fun resolveNlzietChannelId(programme: ProgrammeDto): String? {
        val targetCh = programme.nlziet?.channelId
        if (isValidChannelId(targetCh)) {
            return targetCh
        }
        val mapped = CHANNEL_ID_MAP[programme.channelId]
        if (mapped != null && isValidChannelId(mapped)) {
            return mapped
        }
        if (isValidChannelId(programme.channelId)) {
            return programme.channelId
        }
        return null
    }

    /**
     * Controleert of een programma op dit moment (of op een gegeven timestamp) wordt uitgezonden.
     */
    fun isCurrentlyAiring(programme: ProgrammeDto, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return try {
            val startMillis = Instant.parse(programme.start).toEpochMilli()
            val endMillis = Instant.parse(programme.end).toEpochMilli()
            nowMillis in startMillis until endMillis
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Controleert of een programma al begonnen is.
     *
     * Een uitzending die nog moet beginnen heeft per definitie geen replay-opname. NLZIET
     * accepteert de deeplink dan wel, maar toont "Er is iets misgegaan / Onbekende fout
     * opgetreden". Dat is op het apparaat aangetoond voor meerdere zenders. Zulke programma's
     * openen daarom de gewone app in plaats van een kapotte speler.
     */
    fun hasStarted(programme: ProgrammeDto, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return try {
            nowMillis >= Instant.parse(programme.start).toEpochMilli()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Controleert of het NLZIET package is geïnstalleerd op het apparaat.
     */
    fun isNlzietInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo(PACKAGE_NAME, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Bouwt een intent voor directe replay / afleveringweergave via de TV-specifieke
     * watchnext-route. De TV-router accepteert alleen contentItemId; assetId blijft onderdeel
     * van de backendmatch om de exacte EPG-uitzending te bewijzen, maar hoort niet in deze URI.
     */
    fun createReplayDeeplinkIntent(contentItemId: String): Intent {
        val uri = Uri.parse("$SCHEME://$WATCHNEXT_AUTHORITY/$contentItemId")

        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PACKAGE_NAME)
            component = ComponentName(PACKAGE_NAME, LEANBACK_ACTIVITY_NAME)
            // Bewust GEEN FLAG_ACTIVITY_CLEAR_TOP: InjectActivity is singleTop en staat als
            // root van zijn task. CLEAR_TOP zou de activity dan opnieuw aanmaken in plaats van
            // onNewIntent() aan te roepen, en juist die onNewIntent-route speelt de uitzending af.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Start een TV-watchnext deeplink via de relay-activity.
     *
     * NLZIET TV (InjectActivity, launchMode="singleTop") negeert bij een koude start de eerste
     * URI omdat de interne router dan nog niet klaar is; er verschijnt alleen het dashboard.
     * Een tweede identieke intent komt dankzij singleTop binnen als onNewIntent() en start dan
     * wel de juiste uitzending. Twee starts direct achter elkaar zijn niet genoeg -- de tweede
     * arriveert dan nog steeds te vroeg. Er is een echte wachttijd nodig.
     *
     * Omdat NexusTVGuide na de eerste start naar de achtergrond gaat, mag het die tweede start
     * zelf niet meer doen (background-activity-launch beperking). [NlzietRelayActivity] blijft
     * daarom in de voorgrondtaak staan tot de retry verstuurd is.
     */
    fun launchWatchNextWithColdStartRetry(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(NlzietRelayActivity.createIntent(context, intent))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Relay-start mislukt; directe start van de watchnext-intent", e)
            try {
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Directe watchnext-start mislukt; terugval op de gewone app-launch", e2)
                launchApp(context)
            }
        }
    }

    /**
     * Bouwt een intent voor VOD / standalone aflevering weergave via Android TV watchnext schema.
     */
    fun createVodDeeplinkIntent(contentItemId: String): Intent {
        val uri = Uri.parse("$SCHEME://$WATCHNEXT_AUTHORITY/$contentItemId")

        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PACKAGE_NAME)
            component = ComponentName(PACKAGE_NAME, LEANBACK_ACTIVITY_NAME)
            // Zie createReplayDeeplinkIntent: geen CLEAR_TOP, anders vervalt de onNewIntent-route.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Bouwt een intent voor live tv-kijken naar een specifiek kanaal via deeplink.
     */
    fun createLiveDeeplinkIntent(channelId: String): Intent {
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(DEEPLINK_AUTHORITY)
            .appendPath(LIVE_PATH)
            .appendPath(channelId)
            .build()

        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PACKAGE_NAME)
            component = ComponentName(PACKAGE_NAME, LEANBACK_ACTIVITY_NAME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    /**
     * Bouwt de expliciete Leanback Launcher Intent voor NLZIET Android TV.
     */
    fun createLeanbackIntent(): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            setPackage(PACKAGE_NAME)
            component = ComponentName(PACKAGE_NAME, LEANBACK_ACTIVITY_NAME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
    }

    /**
     * Bouwt een Intent om de Google Play Store pagina van NLZIET te openen.
     */
    fun createPlayStoreIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_MARKET_URI)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Zoekt de beste intent om NLZIET te openen (Leanback UI heeft prioriteit).
     */
    fun getLaunchIntent(context: Context): Intent? {
        val pm = context.packageManager

        // 1. Probeer de expliciete Leanback Component (meest directe weg op Android TV)
        val leanbackIntent = createLeanbackIntent()
        if (leanbackIntent.resolveActivity(pm) != null) {
            Log.d(TAG, "getLaunchIntent: Selected explicit Leanback intent: $leanbackIntent")
            return leanbackIntent
        }

        // 2. Probeer PackageManager Leanback launch intent
        val pmLeanback = pm.getLeanbackLaunchIntentForPackage(PACKAGE_NAME)
        if (pmLeanback != null && pmLeanback.resolveActivity(pm) != null) {
            pmLeanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            Log.d(TAG, "getLaunchIntent: Selected PackageManager Leanback intent: $pmLeanback")
            return pmLeanback
        }

        // 3. Fallback naar algemene launch intent
        val standardIntent = pm.getLaunchIntentForPackage(PACKAGE_NAME)
        if (standardIntent != null && standardIntent.resolveActivity(pm) != null) {
            standardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            Log.d(TAG, "getLaunchIntent: Selected PackageManager standard intent: $standardIntent")
            return standardIntent
        }

        Log.w(TAG, "getLaunchIntent: No valid launch intent found for $PACKAGE_NAME")
        return null
    }

    /**
     * Start de NLZIET app. Indien niet geïnstalleerd, wordt de gebruiker naar de Google Play Store geleid.
     */
    fun launchApp(context: Context): Boolean {
        val intent = getLaunchIntent(context)
        if (intent != null) {
            return try {
                Log.i(TAG, "launchApp: Executing startActivity with intent: [action=${intent.action}, data=${intent.dataString}, component=${intent.component?.flattenToString()}, flags=0x${Integer.toHexString(intent.flags)}]")
                context.startActivity(intent)
                Log.i(TAG, "launchApp: Successfully launched NLZIET main app.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "launchApp: Failed to start NLZIET with intent $intent", e)
                openPlayStore(context)
                false
            }
        }

        Log.w(TAG, "launchApp: NLZIET is not installed on this device.")
        Toast.makeText(
            context,
            context.getString(R.string.nlziet_not_installed),
            Toast.LENGTH_LONG
        ).show()
        openPlayStore(context)
        return false
    }

    /**
     * Start NLZIET voor een specifiek gids-programma volgens de strikte volgorde:
     * 1. Exact replay target (isReplayAllowed)
     * 2. Lopende uitzending met restart target (isRestartAllowed)
     * 3. NLZIET hoofd-app fallback
     */
    fun launchProgramme(context: Context, programme: ProgrammeDto?): Boolean {
        Log.i(TAG, "==================== launchProgramme START ====================")
        if (programme == null) {
            Log.w(TAG, "launchProgramme: ProgrammeDto is NULL. Defaulting to main app launch.")
            return launchApp(context)
        }

        val target = programme.nlziet
        val isAiring = isCurrentlyAiring(programme)

        Log.i(TAG, "Programme Info: id='${programme.id}', title='${programme.title}', channel='${programme.channelId}', start='${programme.start}', end='${programme.end}', isLive=${programme.isLive}, isCurrentlyAiring=$isAiring")
        Log.i(TAG, "Programme Target: nlzietTarget=${target?.let { "kind=${it.kind}, contentItemId=${it.contentItemId}, assetId=${it.assetId}, channelId=${it.channelId}, replayAllowed=${it.isReplayAllowed}, restartAllowed=${it.isRestartAllowed}" } ?: "NULL"}")
        Log.i(TAG, "Validation: isValidEpgTarget=${isValidEpgTarget(target)}, validContentId=${isValidContentItemId(target?.contentItemId)}, validAssetId=${isValidAssetId(target?.assetId)}, validChannelId=${isValidChannelId(target?.channelId)}")

        // 1. Exacte replay target. Alleen voor een uitzending die al begonnen is: eerder
        // bestaat de replay-opname nog niet en toont NLZIET een foutmelding.
        if (isValidEpgTarget(target) && target!!.isReplayAllowed && hasStarted(programme)) {
            val intent = createReplayDeeplinkIntent(target.contentItemId)
            Log.i(TAG, "DECISION: Tier 1 (Exact Replay Target -> watchnext). Intent=[action=${intent.action}, data=${intent.dataString}, component=${intent.component?.flattenToString()}, flags=0x${Integer.toHexString(intent.flags)}]")
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.nlziet_opening_program, programme.title),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (ignored: Exception) {}

            return launchWatchNextWithColdStartRetry(context, intent)
        }

        // 2. Lopende uitzending met restart target
        if (isValidEpgTarget(target) && target!!.isRestartAllowed && isAiring) {
            val intent = createReplayDeeplinkIntent(target.contentItemId)
            Log.i(TAG, "DECISION: Tier 2 (Live Airing with Restart Target -> watchnext). Intent=[action=${intent.action}, data=${intent.dataString}, component=${intent.component?.flattenToString()}, flags=0x${Integer.toHexString(intent.flags)}]")
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.nlziet_opening_program, programme.title),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (ignored: Exception) {}

            return launchWatchNextWithColdStartRetry(context, intent)
        }

        // De TV-build 5.15.3 handelt open/tv-kijken niet af als programma-intent.
        // Zonder exacte replay/restarttarget is de hoofd-app daarom de enige veilige fallback.
        Log.i(TAG, "DECISION: Tier 3 (Fallback - No valid replay/restart target). Opening NLZIET main dashboard.")
        try {
            Toast.makeText(
                context,
                context.getString(R.string.nlziet_opening_program, programme.title),
                Toast.LENGTH_SHORT
            ).show()
        } catch (ignored: Exception) {}

        return launchApp(context)
    }

    /**
     * Start NLZIET voor een specifiek kanaal.
     */
    fun launchChannel(context: Context, channelName: CharSequence?): Boolean {
        Log.i(TAG, "==================== launchChannel START ====================")
        Log.i(TAG, "launchChannel: channelName='$channelName'")
        if (!channelName.isNullOrBlank()) {
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.nlziet_opening_channel, channelName),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (ignored: Exception) {}
        }
        return launchApp(context)
    }

    /**
     * Opent de Google Play Store (of browser) om NLZIET te installeren.
     */
    fun openPlayStore(context: Context) {
        val playStoreIntent = createPlayStoreIntent()
        val pm = context.packageManager
        Log.i(TAG, "openPlayStore: Attempting to open market URI: $PLAY_STORE_MARKET_URI")
        if (playStoreIntent.resolveActivity(pm) != null) {
            try {
                context.startActivity(playStoreIntent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "openPlayStore: Could not open market:// URI, trying web URL", e)
            }
        }

        // Web URL fallback
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB_URL)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            Log.i(TAG, "openPlayStore: Opening web fallback: $PLAY_STORE_WEB_URL")
            context.startActivity(webIntent)
        } catch (e: Exception) {
            Log.e(TAG, "openPlayStore: Failed to open Play Store link", e)
        }
    }
}
