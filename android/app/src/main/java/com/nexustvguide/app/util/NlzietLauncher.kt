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
import com.nexustvguide.app.data.model.ProgrammeDto

/**
 * Helper voor het starten van en deeplinken naar de officiële NLZIET Android TV app.
 *
 * Geverifieerde gegevens uit NLZIET Android TV APK (v5.15.3, build 740504):
 * - Package name: nl.nlziet
 * - Leanback Activity: nl.nlziet.tv.app.di.tv.InjectActivity
 * - Intent filter: android.intent.action.MAIN + android.intent.category.LEANBACK_LAUNCHER
 * - VOD deep link: nlziet://open/vod/<contentId>
 * - Dynamic Links: https://nlzietshare.page.link
 */
object NlzietLauncher {

    private const val TAG = "NlzietLauncher"

    const val PACKAGE_NAME = "nl.nlziet"
    const val LEANBACK_ACTIVITY_NAME = "nl.nlziet.tv.app.di.tv.InjectActivity"
    const val SCHEME = "nlziet"
    const val DEEPLINK_AUTHORITY = "open"
    const val VOD_PATH = "vod"
    const val PLAY_STORE_MARKET_URI = "market://details?id=nl.nlziet"
    const val PLAY_STORE_WEB_URL = "https://play.google.com/store/apps/details?id=nl.nlziet"

    /**
     * Valideert of een string een authentieke NLZIET VOD content-ID is.
     * Authentieke NLZIET content-IDs zijn base64url-compatibele UUID-hashes van 22 karakters.
     */
    fun isValidNlzietId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (!id.matches(Regex("^[a-zA-Z0-9_-]{22}$"))) return false
        if (id.contains("-latest") || id.contains("latest")) return false
        if (!id.any { it.isUpperCase() || it.isDigit() }) return false
        return true
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
     * Bouwt een expliciete Leanback Intent voor de NLZIET Android TV UI.
     */
    fun createLeanbackIntent(): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PACKAGE_NAME, LEANBACK_ACTIVITY_NAME)
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
    }

    /**
     * Bouwt de door de NLZIET TV-app ondersteunde VOD-deeplink.
     *
     * De app registreert `nlziet://open/vod/<contentId>`; `watchnext` is geen geregistreerde
     * route en wordt door NLZIET als onbekende deeplink genegeerd.
     */
    fun createVodDeeplinkIntent(nlzietId: String): Intent {
        require(isValidNlzietId(nlzietId)) { "Invalid NLZIET VOD content ID" }

        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(DEEPLINK_AUTHORITY)
            .appendPath(VOD_PATH)
            .appendPath(nlzietId)
            .build()

        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PACKAGE_NAME)
            component = ComponentName(PACKAGE_NAME, LEANBACK_ACTIVITY_NAME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            return leanbackIntent
        }

        // 2. Probeer PackageManager Leanback launch intent
        val pmLeanback = pm.getLeanbackLaunchIntentForPackage(PACKAGE_NAME)
        if (pmLeanback != null && pmLeanback.resolveActivity(pm) != null) {
            pmLeanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return pmLeanback
        }

        // 3. Fallback naar algemene launch intent
        val standardIntent = pm.getLaunchIntentForPackage(PACKAGE_NAME)
        if (standardIntent != null && standardIntent.resolveActivity(pm) != null) {
            standardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return standardIntent
        }

        return null
    }

    /**
     * Start de NLZIET app. Indien niet geïnstalleerd, wordt de gebruiker naar de Google Play Store geleid.
     */
    fun launchApp(context: Context): Boolean {
        val intent = getLaunchIntent(context)
        if (intent != null) {
            return try {
                Log.d(TAG, "Starting NLZIET with intent: $intent")
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NLZIET", e)
                openPlayStore(context)
                false
            }
        }

        Log.w(TAG, "NLZIET is not installed on this device.")
        Toast.makeText(
            context,
            context.getString(R.string.nlziet_not_installed),
            Toast.LENGTH_LONG
        ).show()
        openPlayStore(context)
        return false
    }

    /**
     * Start NLZIET voor een specifiek gids-programma.
     * Als er een geverifieerd NLZIET content-ID (nlzietId) aanwezig is, wordt de VOD-deeplink aangeroepen.
     * Zonder geldig VOD-ID wordt de hoofd-app geopend zonder foutmelding te triggeren.
     */
    fun launchProgramme(context: Context, programme: ProgrammeDto?): Boolean {
        if (programme != null) {
            val nlzietId = programme.nlzietId
            if (isValidNlzietId(nlzietId)) {
                Log.d(TAG, "Launching NLZIET via deeplink for: ${programme.title} (nlzietId: $nlzietId)")
                try {
                    Toast.makeText(
                        context,
                        context.getString(R.string.nlziet_opening_program, programme.title),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (ignored: Exception) {}
                val intent = createVodDeeplinkIntent(nlzietId)
                return try {
                    context.startActivity(intent)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch VOD deeplink for $nlzietId, falling back to main app", e)
                    launchApp(context)
                }
            } else {
                Log.d(TAG, "Launching NLZIET app for programme without direct VOD ID: ${programme.title}")
                try {
                    Toast.makeText(
                        context,
                        context.getString(R.string.nlziet_opening_program, programme.title),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (ignored: Exception) {}
            }
        }
        return launchApp(context)
    }

    /**
     * Start NLZIET voor een specifiek kanaal.
     */
    fun launchChannel(context: Context, channelName: CharSequence?): Boolean {
        if (!channelName.isNullOrBlank()) {
            Log.d(TAG, "Launching NLZIET for channel: $channelName")
            Toast.makeText(
                context,
                context.getString(R.string.nlziet_opening_channel, channelName),
                Toast.LENGTH_SHORT
            ).show()
        }
        return launchApp(context)
    }

    /**
     * Opent de Google Play Store (of browser) om NLZIET te installeren.
     */
    fun openPlayStore(context: Context) {
        val playStoreIntent = createPlayStoreIntent()
        val pm = context.packageManager
        if (playStoreIntent.resolveActivity(pm) != null) {
            try {
                context.startActivity(playStoreIntent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Could not open market:// URI, trying web URL", e)
            }
        }

        // Web URL fallback
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB_URL)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Play Store link", e)
        }
    }
}
