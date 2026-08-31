package com.nexustvguide.app.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexustvguide.app.R
import com.nexustvguide.app.ui.update.UpdateDialogFragment
import com.nexustvguide.app.ui.update.UpdateNavigationEvent
import com.nexustvguide.app.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, NexusProgramGuideFragment())
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

        // Passieve updatecontrole starten na het eerste frame
        window.decorView.post {
            updateViewModel.checkForUpdates(isManual = false)
        }
    }

    private fun showUpdateDialog() {
        if (supportFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG) == null) {
            val dialog = UpdateDialogFragment.newInstance()
            dialog.show(supportFragmentManager, UpdateDialogFragment.TAG)
        }
    }
}
