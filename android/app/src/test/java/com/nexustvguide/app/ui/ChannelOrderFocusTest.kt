package com.nexustvguide.app.ui

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.nexustvguide.app.R
import com.nexustvguide.app.data.model.ChannelDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChannelOrderFocusTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>().apply {
            setTheme(R.style.Theme_NexusTVGuide)
        }
    }

    @Test
    fun testFragmentLayoutFocusAttributes() {
        val view = LayoutInflater.from(context).inflate(R.layout.fragment_channel_order, null)
        assertNotNull(view)

        assertFalse("Root view must not be focusable", view.isFocusable)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_channel_order)
        assertNotNull(recyclerView)
        assertEquals(RecyclerView.FOCUS_AFTER_DESCENDANTS, recyclerView.descendantFocusability)
    }

    @Test
    fun testChannelOrderItemFocusPrioritizesRowOverDescendants() {
        val view = LayoutInflater.from(context).inflate(R.layout.item_channel_order, null)
        assertNotNull(view)

        val btnVis = view.findViewById<View>(R.id.btn_channel_visibility)
        assertNotNull(btnVis)

        // Requesting focus on the row must focus the row itself, not the eye button child
        view.requestFocus()
        assertTrue("Row itemView itself must have focus", view.isFocused)
        assertFalse("Eye button should not have focus when row is requested", btnVis.isFocused)
    }
}
