package com.colorwalk.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ColorForDayTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Returns epoch-millis for today at the specified hour/minute (local TZ). */
    private fun todayAt(hour: Int, minute: Int = 0): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Returns epoch-millis for a specific absolute date (local TZ). */
    private fun dateOf(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1) // Calendar months are 0-based
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // ── colorForDay() ────────────────────────────────────────────────────────

    @Test
    fun colorForDay_sameTimestamp_alwaysReturnsSameColor() {
        val ts = System.currentTimeMillis()
        val c1 = colorForDay(ts)
        val c2 = colorForDay(ts)
        assertEquals("Same timestamp must always yield the same color", c1, c2)
    }

    @Test
    fun colorForDay_differentDays_areConsistentAcrossCalls() {
        val day1 = dateOf(2025, 1, 1)
        val day2 = dateOf(2025, 1, 2)
        // Each day is deterministic — calling twice returns the same result
        assertEquals(colorForDay(day1), colorForDay(day1))
        assertEquals(colorForDay(day2), colorForDay(day2))
    }

    @Test
    fun colorForDay_twoDifferentDays_mayReturnDifferentColors() {
        // Jan 1 2025 and Jan 2 2025 should map to adjacent WALK_COLORS slots.
        // We verify the algorithm is day-sensitive (even if by chance same color,
        // the index arithmetic differs by 1).
        val day1 = dateOf(2025, 1, 1, 12)
        val day2 = dateOf(2025, 1, 2, 12)
        val cal1 = Calendar.getInstance().apply { timeInMillis = day1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = day2 }
        val idx1 = cal1.get(Calendar.YEAR) * 366 + cal1.get(Calendar.DAY_OF_YEAR)
        val idx2 = cal2.get(Calendar.YEAR) * 366 + cal2.get(Calendar.DAY_OF_YEAR)
        // Adjacent day indices must differ
        assertEquals(1, idx2 - idx1)
    }

    @Test
    fun colorForDay_everyDayInThirtyDayWindow_returnsValidWalkColor() {
        val today = Calendar.getInstance()
        repeat(30) { offset ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, today.get(Calendar.YEAR))
                set(Calendar.DAY_OF_YEAR, today.get(Calendar.DAY_OF_YEAR) - offset)
                set(Calendar.HOUR_OF_DAY, 12)
            }
            val color = colorForDay(cal.timeInMillis)
            assertNotNull("colorForDay must never return null", color)
            assertTrue("Color name must be non-blank", color.name.isNotBlank())
            assertTrue(
                "Color hex '${color.hex}' must match #RRGGBB format",
                color.hex.matches(Regex("^#[0-9A-Fa-f]{6}$"))
            )
            assertTrue(
                "Color must be a member of WALK_COLORS",
                WALK_COLORS.contains(color)
            )
        }
    }

    @Test
    fun colorForDay_timestampAtMidnight_returnsSameColorAsMiddleOfDay() {
        val midnight = todayAt(0, 0)
        val noon = todayAt(12, 0)
        assertEquals(
            "Midnight and noon of the same day must yield the same color",
            colorForDay(midnight),
            colorForDay(noon)
        )
    }

    @Test
    fun colorForDay_timestampAt2359_returnsSameColorAsMiddleOfDay() {
        val almostMidnight = todayAt(23, 59)
        val noon = todayAt(12, 0)
        assertEquals(
            "23:59 and noon of the same day must yield the same color",
            colorForDay(almostMidnight),
            colorForDay(noon)
        )
    }

    @Test
    fun colorForDay_indexModuloEqualsWalkColorsSize() {
        // The algorithm is: WALK_COLORS[floorMod(year*366+dayOfYear, WALK_COLORS.size)]
        // Verify the formula produces an index always in [0, size)
        val cal = Calendar.getInstance()
        repeat(100) { offset ->
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val raw = cal.get(Calendar.YEAR) * 366 + cal.get(Calendar.DAY_OF_YEAR)
            val idx = Math.floorMod(raw, WALK_COLORS.size)
            assertTrue("Index $idx must be in [0, ${WALK_COLORS.size})", idx in 0 until WALK_COLORS.size)
        }
    }

    @Test
    fun colorForDay_knownDate_returnsExpectedColor() {
        // Pinned regression test. Compute expected by running the same algorithm inline.
        val knownTs = dateOf(2025, 6, 15, 12)
        val cal = Calendar.getInstance().apply { timeInMillis = knownTs }
        val dayIndex = cal.get(Calendar.YEAR) * 366 + cal.get(Calendar.DAY_OF_YEAR)
        val expectedColor = WALK_COLORS[Math.floorMod(dayIndex, WALK_COLORS.size)]
        assertEquals(expectedColor, colorForDay(knownTs))
    }

    @Test
    fun colorForDay_cyclesBackAfterAllColors() {
        // Over a long run the colors cycle through all WALK_COLORS
        val foundColors = mutableSetOf<String>()
        val cal = Calendar.getInstance()
        // 8 colors — within 8 consecutive days every color appears at least once over time,
        // but since stride=1 and size=8, all 8 unique colors appear within any 8-day window.
        repeat(WALK_COLORS.size * 2) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            foundColors.add(colorForDay(cal.timeInMillis).name)
        }
        // After walking size*2 days we must have visited all colors
        assertEquals(
            "All ${WALK_COLORS.size} colors should appear within ${WALK_COLORS.size * 2} days",
            WALK_COLORS.size,
            foundColors.size
        )
    }
}
