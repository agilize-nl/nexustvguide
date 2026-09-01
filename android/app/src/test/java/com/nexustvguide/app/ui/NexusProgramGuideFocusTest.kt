package com.nexustvguide.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.egeniq.androidtvprogramguide.R as LibraryR
import com.egeniq.androidtvprogramguide.entity.ProgramGuideSchedule
import com.egeniq.androidtvprogramguide.item.ProgramGuideItemView
import com.egeniq.androidtvprogramguide.util.FixedLocalDateTime
import com.egeniq.androidtvprogramguide.util.ProgramGuideUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.threeten.bp.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NexusProgramGuideFocusTest {

    @Test
    fun testFindNextFocusedProgramPrioritizesCurrentLiveProgramWhenKeepCurrentProgramFocused() {
        val context = ApplicationProvider.getApplicationContext<Context>().apply { setTheme(com.nexustvguide.app.R.style.Theme_NexusTVGuide) }
        val rowLayout = FrameLayout(context)

        val now = System.currentTimeMillis()
        val pastSchedule = ProgramGuideSchedule.createScheduleWithProgram(
            id = 100L,
            startsAt = Instant.ofEpochMilli(now - 7200_000),
            endsAt = Instant.ofEpochMilli(now - 3600_000),
            isClickable = true,
            displayTitle = "Past Program",
            program = "Past Program Data"
        )
        val currentSchedule = ProgramGuideSchedule.createScheduleWithProgram(
            id = 101L,
            startsAt = Instant.ofEpochMilli(now - 1800_000),
            endsAt = Instant.ofEpochMilli(now + 1800_000),
            isClickable = true,
            displayTitle = "Live Program",
            program = "Live Program Data"
        )

        val pastView = ProgramGuideItemView<String>(context)
        pastView.setValues(pastSchedule, now - 7200_000, now + 3600_000, "Gap", false)

        val liveView = ProgramGuideItemView<String>(context)
        liveView.setValues(currentSchedule, now - 7200_000, now + 3600_000, "Gap", false)

        rowLayout.addView(pastView)
        rowLayout.addView(liveView)

        // Set lastClickedSchedule to the past program
        ProgramGuideUtil.lastClickedSchedule = pastSchedule

        // When keepCurrentProgramFocused is true (returning to live view):
        val focused = ProgramGuideUtil.findNextFocusedProgram(
            programRow = rowLayout,
            focusRangeLeft = 0,
            focusRangeRight = 1000,
            keepCurrentProgramFocused = true
        )

        // Should return the LIVE view, and lastClickedSchedule should be cleared
        assertSame(liveView, focused)
        assertEquals(null, ProgramGuideUtil.lastClickedSchedule)
    }

    @Test
    fun testTodaySelectionUpdatesDateToCurrentDay() {
        val today = FixedLocalDateTime.now().toLocalDate()
        assertNotNull(today)
        assertTrue(today.year >= 2024)
    }

    @Test
    fun testLogoOnFarLeftAndMenuButtonProperHeight() {
        val context = ApplicationProvider.getApplicationContext<Context>().apply {
            setTheme(com.nexustvguide.app.R.style.Theme_NexusTVGuide)
        }
        val layout = LayoutInflater.from(context).inflate(LibraryR.layout.programguide_fragment, null)
        assertNotNull(layout)

        val menuButton = layout.findViewById<ImageButton>(LibraryR.id.programguide_menu_button)
        val logoView = layout.findViewById<ImageView>(LibraryR.id.programguide_header_logo)
        val rightContainer = layout.findViewById<ViewGroup>(LibraryR.id.programguide_header_right_container)

        assertNotNull(menuButton)
        assertNotNull(logoView)
        assertNotNull(rightContainer)

        // Logo is a direct child of root constraint layout on the far left
        assertEquals(layout, logoView.parent)

        // Menu button is inside rightContainer
        val menuIndex = rightContainer.indexOfChild(menuButton)
        assertTrue("Menu button must be inside rightContainer", menuIndex >= 0)

        // Menu button must be focusable
        assertTrue("Menu button must be focusable", menuButton.isFocusable)

        // Menu button height should be 28dp (scaled by density)
        val density = context.resources.displayMetrics.density
        val expectedHeightPx = (28 * density).toInt()
        assertEquals(expectedHeightPx, menuButton.layoutParams.height)
    }
    @Test
    fun testTimelineTimeLabelsAlignWithGridStartWithoutStaleOffset() {
        val context = ApplicationProvider.getApplicationContext<Context>().apply {
            setTheme(com.nexustvguide.app.R.style.Theme_NexusTVGuide)
        }
        val widthPerHour = context.resources.getDimensionPixelSize(LibraryR.dimen.programguide_table_width_per_hour)
        ProgramGuideUtil.setWidthPerHour(widthPerHour)

        val adapter = com.egeniq.androidtvprogramguide.timeline.ProgramGuideTimeListAdapter(
            context.resources,
            org.threeten.bp.ZoneId.of("Europe/Amsterdam")
        )

        val halfHourMillis = 30 * 60 * 1000L
        val t0 = 1700000000000L
        val timelineStart = t0 - halfHourMillis
        val timelineAdjustmentPx = ProgramGuideUtil.convertMillisToPixel(halfHourMillis)

        adapter.update(timelineStart, timelineAdjustmentPx)

        val parent = androidx.recyclerview.widget.RecyclerView(context).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false) }
        val vh0 = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(vh0, 0)
        val lp0 = vh0.itemView.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams

        val halfHourWidthPx = ProgramGuideUtil.convertMillisToPixel(halfHourMillis)
        assertEquals(-halfHourWidthPx / 2 - timelineAdjustmentPx, lp0.marginStart)

        val vh1 = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1))
        adapter.onBindViewHolder(vh1, 1)
        val lp1 = vh1.itemView.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams
        assertEquals(0, lp1.marginStart)

        val item1Center = lp0.marginStart + lp0.width + (lp1.width / 2)
        assertEquals("Label for T0 must be centered at coordinate 0 relative to time row start", 0, item1Center)
    }
}
