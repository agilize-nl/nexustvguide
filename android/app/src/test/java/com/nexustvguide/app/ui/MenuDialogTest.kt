package com.nexustvguide.app.ui

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.egeniq.androidtvprogramguide.R as LibraryR
import com.nexustvguide.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
class MenuDialogTest {

    @Test
    fun testMenuStringResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("Zoeken naar updates", context.getString(R.string.menu_item_check_updates))
        assertEquals("Zenders ordenen", context.getString(R.string.menu_item_channel_order))
        assertEquals("Gidsbronnen aanpassen", context.getString(R.string.menu_item_guide_source))
        assertEquals("Over Nexus TV Gids", context.getString(R.string.menu_item_about))
        assertEquals("Over Nexus TV Gids", context.getString(R.string.about_dialog_title))

        val aboutMessage = context.getString(R.string.about_dialog_message, "1.0.0", 1)
        assertTrue("Moet bron TVgids.nl vermelden", aboutMessage.contains("TVgids.nl"))
        assertTrue("Moet omroepen/zenders vermelden", aboutMessage.contains("omroepen"))
        assertTrue("Moet auteursrechten/copyright vermelden", aboutMessage.contains("Auteursrechten") || aboutMessage.contains("Copyright"))
        assertTrue("Moet NLZIET vermelden", aboutMessage.contains("NLZIET"))
    }

    @Test
    fun testMenuDialogItemsOrder() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val fragment = activity.supportFragmentManager.findFragmentByTag(MainActivity.TAG_GUIDE) as? NexusProgramGuideFragment
        assertNotNull("NexusProgramGuideFragment moet gevonden worden", fragment)

        val menuButton = fragment!!.requireView().findViewById<View>(LibraryR.id.programguide_menu_button)
        assertNotNull("Menu button moet aanwezig zijn in layout", menuButton)
        menuButton.performClick()

        val dialog = ShadowAlertDialog.getLatestDialog() as? AlertDialog
        assertNotNull("Menu dialog moet getoond worden na klik op menuknop", dialog)

        val listView = dialog!!.listView
        assertNotNull("ListView moet aanwezig zijn in dialog", listView)
        val adapter = listView.adapter
        assertNotNull("Adapter moet aanwezig zijn in ListView", adapter)
        assertEquals("Menu moet 4 items bevatten", 4, adapter.count)

        assertEquals("Zoeken naar updates", adapter.getItem(0).toString())
        assertEquals("Zenders ordenen", adapter.getItem(1).toString())
        assertEquals("Gidsbronnen aanpassen", adapter.getItem(2).toString())
        assertEquals("Over Nexus TV Gids", adapter.getItem(3).toString())
    }
}
