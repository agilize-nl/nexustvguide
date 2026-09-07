package com.nexustvguide.app.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.R
import com.nexustvguide.app.ui.update.UpdateDialogFragment
import com.nexustvguide.app.ui.update.UpdateNavigationEvent
import com.nexustvguide.app.ui.update.UpdateViewModel
import kotlinx.coroutines.launch
import org.threeten.bp.LocalDate

class MainActivity : FragmentActivity() {

    companion object {
        const val TAG_GUIDE = "guide"
        const val TAG_CHANNEL_ORDER = "channel_order"
    }

    private val updateViewModel: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, NexusProgramGuideFragment(), TAG_GUIDE)
                .commitNow()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateViewModel.navEvents.collect { event ->
                    if (event is UpdateNavigationEvent.ShowUpdateDialog) {
                        showUpdateDialog()
                    }
                }
            }
        }

        // Passieve updatecontrole starten na het eerste frame (alleen in REMOTE-modus conform §7.6)
        window.decorView.post {
            val source = com.nexustvguide.app.data.repository.GuideRepositoryProvider.getGuideSource(this)
            if (source != "LOCAL") {
                updateViewModel.checkForUpdates(isManual = false)
            }
        }
    }

    fun showChannelOrder(date: LocalDate) {
        if (supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(TAG_CHANNEL_ORDER) != null) return

        val guideFragment = supportFragmentManager.findFragmentByTag(TAG_GUIDE) ?: return
        val channelOrderFragment = ChannelOrderFragment.newInstance(date)

        supportFragmentManager.beginTransaction()
            .add(R.id.main_container, channelOrderFragment, TAG_CHANNEL_ORDER)
            .hide(guideFragment)
            .setMaxLifecycle(guideFragment, Lifecycle.State.STARTED)
            .setPrimaryNavigationFragment(channelOrderFragment)
            .addToBackStack(TAG_CHANNEL_ORDER)
            .commit()
    }

    private fun showUpdateDialog() {
        if (supportFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG) == null) {
            val dialog = UpdateDialogFragment.newInstance()
            dialog.show(supportFragmentManager, UpdateDialogFragment.TAG)
        }
    }
}
