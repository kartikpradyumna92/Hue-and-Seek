package com.colorwalk.app.ui

import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.ui.components.panLimits
import com.colorwalk.app.ui.home.pageOf
import com.colorwalk.app.ui.stats.buildMonthGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** Pure layout math behind the Performance/Lifecycle fixes. */
class LayoutMathTest {

    // ── hub pane restore (BUG-008) ───────────────────────────────────────────

    @Test
    fun pageOf_recognizesOnlyExactPagePositions() {
        assertEquals(0, pageOf(0f, 1080))
        assertEquals(1, pageOf(1080f, 1080))      // Camera / Settings
        assertEquals(-1, pageOf(-1080f, 1080))    // Gallery / Newsfeed
        assertNull("mid-drag is not a settled pane", pageOf(540f, 1080))
        assertNull(pageOf(-1079.5f, 1080))
    }

    // ── zoom pan clamp (BUG-058) ─────────────────────────────────────────────

    @Test
    fun panLimits_stopAtThePhotosEdges_notTheLetterboxedBox() {
        // A 4:3 landscape photo fitted into a 1080×2400 portrait box shows as 1080×810.
        val at2x = panLimits(1080f, 2400f, 1080f, 810f, scale = 2f)
        assertEquals(540f, at2x.x, 0.01f)
        // 810×2 = 1620 < 2400: still shorter than the box — no vertical pan into the
        // black bars (the old box-based clamp allowed 1200px of it).
        assertEquals(0f, at2x.y, 0.01f)

        val at5x = panLimits(1080f, 2400f, 1080f, 810f, scale = 5f)
        assertEquals((810f * 5 - 2400f) / 2f, at5x.y, 0.01f)
    }

    @Test
    fun panLimits_unzoomedCannotPan() {
        val p = panLimits(1080f, 2400f, 1080f, 810f, scale = 1f)
        assertEquals(0f, p.x, 0f)
        assertEquals(0f, p.y, 0f)
    }

    // ── Stats calendar (BUG-037) ─────────────────────────────────────────────

    @Test
    fun monthGrid_honorsTheLocalesFirstDayOfWeek() {
        // 1 September 2026 is a Tuesday.
        val sundayFirst = buildMonthGrid(2026, Calendar.SEPTEMBER, Calendar.SUNDAY)
        val mondayFirst = buildMonthGrid(2026, Calendar.SEPTEMBER, Calendar.MONDAY)
        assertEquals(2, sundayFirst.first().indexOfFirst { it != null })   // S M [T]
        assertEquals(1, mondayFirst.first().indexOfFirst { it != null })   // M [T]
        assertEquals(1, mondayFirst.first()[1]!!.dayOfMonth)
    }

    @Test
    fun monthGrid_weekStartingOnTheFirst_hasNoLeadingBlanks() {
        // 1 June 2026 is a Monday.
        assertEquals(0, buildMonthGrid(2026, Calendar.JUNE, Calendar.MONDAY).first().indexOfFirst { it != null })
    }

    @Test
    fun monthGrid_dayIndicesMatchStreakCalculator() {
        val grid = buildMonthGrid(2026, Calendar.SEPTEMBER, Calendar.MONDAY)
        val sept1 = grid.flatten().filterNotNull().first()
        val noon = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 1, 12, 0, 0) }.timeInMillis
        assertEquals(StreakCalculator.epochMillisToDayIndex(noon), sept1.dayIndex)
        assertEquals(30, grid.flatten().count { it != null })
    }
}
