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
 * Volgt de veilige NLZIET-EPG routeringshiërarchie met uitgebreide logging:
 * 1. Exacte replay target (isReplayAllowed == true) -> nlziet://open/epg/<contentItemId>/<assetId>
 * 2. Lopende uitzending met restart target (isRestartAllowed == true) -> nlziet://open/epg/<contentItemId>/<assetId>
 * 3. Lopende uitzending live -> nlziet://open/tv-kijken/<channelId>
 * 4. Fallback -> NLZIET Leanback UI
 */
object NlzietLauncher {

    const val TAG = "NlzietLauncher"

    const val PACKAGE_NAME = "nl.nlziet"
    const val LEANBACK_ACTIVITY_NAME = "nl.nlziet.tv.app.di.tv.InjectActivity"

    const val SCHEME = "nlziet"
    const val DEEPLINK_AUTHORITY = "open"
    const val EPG_PATH = "epg"
    const val LIVE_PATH = "tv-kijken"
    const val VOD_PATH = "vod"

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
        if (programme.isLive) return true
        return try {
            val startMillis = Instant.parse(programme.start).toEpochMilli()
            val endMillis = Instant.parse(programme.end).toEpochMilli()
            nowMillis in startMillis until endMillis
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
     * Bouwt een intent voor directe replay / aflevering weergave via de officiële EPG deeplink.
     */
    fun createReplayDeeplinkIntent(contentItemId: String, assetId: String): Intent {
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(DEEPLINK_AUTHORITY)
            .appendPath(EPG_PATH)
            .appendPath(contentItemId)
            .appendPath(assetId)
            .build()

        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PACKAGE_NAME)
            component = ComponentName(PACKAGE_NAME, LEANBACK_ACTIVITY_NAME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    /**
     * Bouwt een intent voor live tv-kijken naar een specifiek kanaal via deeplink.
     */
    /**
     * Bouwt een intent voor VOD / aflevering weergave via deeplink (legacy / standalone).
     */
    fun createVodDeeplinkIntent(contentItemId: String): Intent {
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(DEEPLINK_AUTHORITY)
            .appendPath(VOD_PATH)
            .appendPath(contentItemId)
            .build()

        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PACKAGE_NAME)
            component = ComponentName(PACKAGE_NAME, LEANBACK_ACTIVITY_NAME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

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
     * 3. Lopende uitzending live kanaal
     * 4. NLZIET hoofd-app fallback
     */
    fun launchProgramme(context: Context, programme: ProgrammeDto?): Boolean {
        Log.i(TAG, "==================== launchProgramme START ====================")
        if (programme == null) {
            Log.w(TAG, "launchProgramme: ProgrammeDto is NULL. Defaulting to main app launch.")
            return launchApp(context)
        }

        val target = programme.nlziet
        val isAiring = isCurrentlyAiring(programme)
        val nlzietChannelId = resolveNlzietChannelId(programme)

        Log.i(TAG, "Programme Info: id='${programme.id}', title='${programme.title}', channel='${programme.channelId}', start='${programme.start}', end='${programme.end}', isLive=${programme.isLive}, isCurrentlyAiring=$isAiring")
        Log.i(TAG, "Programme Target: nlzietTarget=${target?.let { "kind=${it.kind}, contentItemId=${it.contentItemId}, assetId=${it.assetId}, channelId=${it.channelId}, replayAllowed=${it.isReplayAllowed}, restartAllowed=${it.isRestartAllowed}" } ?: "NULL"}")
        Log.i(TAG, "Validation: isValidEpgTarget=${isValidEpgTarget(target)}, validContentId=${isValidContentItemId(target?.contentItemId)}, validAssetId=${isValidAssetId(target?.assetId)}, validChannelId=${isValidChannelId(nlzietChannelId)}")

        // 1. Exacte replay target
        if (isValidEpgTarget(target) && target!!.isReplayAllowed) {
            val intent = createReplayDeeplinkIntent(target.contentItemId, target.assetId)
            Log.i(TAG, "DECISION: Tier 1 (Exact Replay Target). Intent=[action=${intent.action}, data=${intent.dataString}, component=${intent.component?.flattenToString()}, flags=0x${Integer.toHexString(intent.flags)}]")
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.nlziet_opening_program, programme.title),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (ignored: Exception) {}

            return try {
                context.startActivity(intent)
                Log.i(TAG, "launchProgramme: Successfully fired Tier 1 Replay deeplink.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "launchProgramme: Exception firing Tier 1 deeplink, falling back to main app", e)
                launchApp(context)
            }
        }

        // 2. Lopende uitzending met restart target
        if (isValidEpgTarget(target) && target!!.isRestartAllowed && isAiring) {
            val intent = createReplayDeeplinkIntent(target.contentItemId, target.assetId)
            Log.i(TAG, "DECISION: Tier 2 (Live Airing with Restart Target). Intent=[action=${intent.action}, data=${intent.dataString}, component=${intent.component?.flattenToString()}, flags=0x${Integer.toHexString(intent.flags)}]")
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.nlziet_opening_program, programme.title),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (ignored: Exception) {}

            return try {
                context.startActivity(intent)
                Log.i(TAG, "launchProgramme: Successfully fired Tier 2 Restart deeplink.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "launchProgramme: Exception firing Tier 2 deeplink, falling back to main app", e)
                launchApp(context)
            }
        }

        // 3. Lopende uitzending live stream
        if (isAiring && isValidChannelId(nlzietChannelId)) {
            val intent = createLiveDeeplinkIntent(nlzietChannelId!!)
            Log.i(TAG, "DECISION: Tier 3 (Live Airing Channel Stream). Channel=$nlzietChannelId, Intent=[action=${intent.action}, data=${intent.dataString}, component=${intent.component?.flattenToString()}, flags=0x${Integer.toHexString(intent.flags)}]")
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.nlziet_opening_program, programme.title),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (ignored: Exception) {}

            return try {
                context.startActivity(intent)
                Log.i(TAG, "launchProgramme: Successfully fired Tier 3 Live deeplink.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "launchProgramme: Exception firing Tier 3 deeplink, falling back to main app", e)
                launchApp(context)
            }
        }

        // 4. Toekomstig of niet-replaybaar programma: toon melding en open hoofd-app
        Log.i(TAG, "DECISION: Tier 4 (Fallback - No valid replay/restart/live target). Opening NLZIET main dashboard.")
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
