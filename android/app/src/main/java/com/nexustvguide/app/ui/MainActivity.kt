package com.nexustvguide.app.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.egeniq.androidtvprogramguide.util.FixedLocalDateTime
import com.nexustvguide.app.R

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, NexusProgramGuideFragment())
                .commitNow()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val fragment = supportFragmentManager.findFragmentById(R.id.main_container) as? NexusProgramGuideFragment
            fragment?.let {
                val today = FixedLocalDateTime.now().toLocalDate()
                if (it.currentDate != today) {
                    it.selectToday()
                } else {
                    it.jumpToLive(focus = true)
                }
            }
        }
    }
}
