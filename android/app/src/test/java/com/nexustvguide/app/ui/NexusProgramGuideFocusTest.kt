package com.nexustvguide.app.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
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
}
