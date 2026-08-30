package com.nexustvguide.app.util

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Onzichtbare relay-activity die de NLZIET koude-startbug omzeilt.
 *
 * Vastgesteld gedrag van NLZIET Android TV (5.15.3, build 740504), geverifieerd op een
 * Android 16 (API 36) TV-emulator:
 *
 * - `nl.nlziet.tv.app.di.tv.InjectActivity` staat in het manifest als `launchMode="singleTop"`.
 * - Bij een koude start maakt de eerste `nlziet://watchnext/<id>` de activity aan, maar is de
 *   interne router nog niet geinitialiseerd; de URI wordt genegeerd en het dashboard verschijnt.
 * - Een tweede identieke intent wordt dankzij singleTop als `onNewIntent()` bezorgd en start
 *   dan wel de juiste uitzending.
 * - Twee direct opeenvolgende starts werken NIET: de tweede intent arriveert wel, maar nog
 *   steeds voor de router klaar is. Er is een echte wachttijd nodig; vanaf circa 1 seconde
 *   is de start betrouwbaar.
 *
 * Het probleem daarbij is Android's background-activity-launch (BAL) beperking: zodra
 * NexusTVGuide na de eerste start naar de achtergrond gaat, mag het geen tweede activity
 * meer starten. Deze relay-activity lost dat op door zelf in de voorgrondtaak te blijven
 * staan totdat de retry verstuurd is. Zij draait in een eigen task (`documentLaunchMode`
 * en `excludeFromRecents` in het manifest) en sluit zichzelf daarna.
 */
class NlzietRelayActivity : Activity() {

    companion object {
        const val TAG = "NlzietRelay"
        const val EXTRA_TARGET_INTENT = "com.nexustvguide.app.extra.TARGET_INTENT"

        /**
         * Alleen bedoeld voor handmatige apparaattests: geeft een contentItemId mee als string,
         * zodat `adb shell am start ... --es content_item_id <id>` exact dezelfde route aflegt.
         * `am start` kan namelijk geen geneste Intent als extra meesturen.
         */
        const val EXTRA_CONTENT_ITEM_ID = "content_item_id"

        /**
         * Wachttijd voordat de identieke intent opnieuw wordt verstuurd. Op de emulator was
         * 1000 ms de eerste waarde die betrouwbaar afspeelde; 1250 ms geeft marge op tragere
         * apparaten zonder merkbaar oponthoud voor de gebruiker.
         */
        const val RETRY_DELAY_MS = 1_250L

        /** Maximale tijd dat de relay zichtbaar blijft voordat hij zichzelf hoe dan ook sluit. */
        const val SELF_FINISH_DELAY_MS = RETRY_DELAY_MS + 250L

        fun createIntent(context: android.content.Context, target: Intent): Intent {
            return Intent(context, NlzietRelayActivity::class.java).apply {
                putExtra(EXTRA_TARGET_INTENT, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target: Intent? = intentExtraTarget() ?: contentItemIdTarget()
        if (target == null) {
            Log.w(TAG, "Geen doelintent meegegeven; relay sluit direct.")
            finish()
            return
        }

        // Eerste start: warmt NLZIET op (koud) of speelt direct af (warm).
        try {
            Log.i(TAG, "Eerste watchnext-start: ${target.dataString}")
            startActivity(target)
        } catch (e: Exception) {
            Log.e(TAG, "Eerste NLZIET-start mislukt; terugval op de gewone app-launch", e)
            NlzietLauncher.launchApp(this)
            finish()
            return
        }

        // Tweede, identieke start. Deze wordt door singleTop als onNewIntent bezorgd en is de
        // start die bij een koude start daadwerkelijk afspeelt. De relay staat op dit moment
        // nog in de voorgrondtaak, waardoor de start niet door BAL geblokkeerd wordt.
        handler.postDelayed({
            if (!retryDone && !isFinishing) {
                retryDone = true
                try {
                    Log.i(TAG, "Retry watchnext-start (onNewIntent-route): ${target.dataString}")
                    startActivity(Intent(target))
                } catch (e: Exception) {
                    // De eerste start is al gelukt; NLZIET staat open. Een mislukte retry
                    // betekent alleen dat de gebruiker op het dashboard blijft.
                    Log.w(TAG, "Retry van de watchnext-intent mislukt", e)
                }
            }
        }, RETRY_DELAY_MS)

        handler.postDelayed({ if (!isFinishing) finish() }, SELF_FINISH_DELAY_MS)
    }

    /**
     * Testroute: bouwt de target-intent uit een meegegeven contentItemId. Het ID wordt met
     * dezelfde validatie gecontroleerd als in de gids, zodat deze route nooit een ongeldige
     * URI kan openen.
     */
    private fun contentItemIdTarget(): Intent? {
        val id = intent?.getStringExtra(EXTRA_CONTENT_ITEM_ID) ?: return null
        if (!NlzietLauncher.isValidContentItemId(id)) {
            Log.w(TAG, "Ongeldig contentItemId meegegeven; genegeerd.")
            return null
        }
        return NlzietLauncher.createReplayDeeplinkIntent(id)
    }

    @Suppress("DEPRECATION")
    private fun intentExtraTarget(): Intent? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_TARGET_INTENT)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
