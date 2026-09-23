package com.colorwalk.app.notification

import com.colorwalk.app.domain.StreakCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** I-6: the "today or tomorrow" trigger arithmetic behind every reminder alarm. */
class AlarmTriggerTest {

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun hourMinuteOf(millis: Long): Pair<Int, Int> =
        Calendar.getInstance().apply { timeInMillis = millis }
            .let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) }

    @Test
    fun slotStillAheadToday_firesToday() {
        val now = at(8, 0)
        val trigger = AlarmScheduler.nextTriggerMillis(hour = 10, minute = 30, nowMillis = now)
        assertEquals(at(10, 30), trigger)
    }

    @Test
    fun slotAlreadyPassed_firesTomorrowAtTheSameWallClockTime() {
        val now = at(11, 0)
        val trigger = AlarmScheduler.nextTriggerMillis(hour = 10, minute = 30, nowMillis = now)
        assertTrue("Must be in the future", trigger > now)
        assertEquals(10 to 30, hourMinuteOf(trigger))
        // Roughly a day ahead (23–25h window tolerates DST transitions).
        val delta = trigger - now
        assertTrue("Expected ~1 day ahead, got ${delta}ms", delta in (22L * 3600_000)..(26L * 3600_000))
    }

    @Test
    fun slotExactlyNow_firesTomorrowNotNow() {
        val now = at(10, 30)
        val trigger = AlarmScheduler.nextTriggerMillis(hour = 10, minute = 30, nowMillis = now)
        assertTrue("An alarm 'now' must roll to tomorrow, never fire immediately", trigger > now)
        assertEquals(10 to 30, hourMinuteOf(trigger))
    }

    // ── one fire per slot per day (BUG-004) ──────────────────────────────────

    private fun dayOf(millis: Long) = StreakCalculator.epochMillisToDayIndex(millis)

    @Test
    fun firedEarlyInsideTheWindow_rearmsForTomorrowNotTodaysSlot() {
        // Inexact window opened at 20:53; the alarm fired at 20:55 for the 21:00 slot.
        val now = at(20, 55)
        val trigger = AlarmScheduler.nextTriggerMillis(
            hour = 21, minute = 0, nowMillis = now, lastFiredDayIndex = dayOf(now)
        )
        assertEquals("Must skip today's already-fired slot", dayOf(now) + 1, dayOf(trigger))
        assertEquals(21 to 0, hourMinuteOf(trigger))
    }

    @Test
    fun appLaunchAfterTodaysFire_doesNotRearmToday() {
        // scheduleBoth() on app launch at 20:56, after the 21:00 slot fired early.
        val now = at(20, 56)
        val trigger = AlarmScheduler.nextTriggerMillis(
            hour = 21, minute = 0, nowMillis = now, lastFiredDayIndex = dayOf(at(21, 0))
        )
        assertEquals(dayOf(now) + 1, dayOf(trigger))
    }

    @Test
    fun firedYesterday_todaysSlotStillArms() {
        val now = at(8, 0)
        val trigger = AlarmScheduler.nextTriggerMillis(
            hour = 21, minute = 0, nowMillis = now, lastFiredDayIndex = dayOf(now) - 1
        )
        assertEquals(at(21, 0), trigger)
    }

    @Test
    fun noFireHistory_behavesAsBefore() {
        val now = at(8, 0)
        assertEquals(
            AlarmScheduler.nextTriggerMillis(hour = 21, minute = 0, nowMillis = now),
            AlarmScheduler.nextTriggerMillis(hour = 21, minute = 0, nowMillis = now, lastFiredDayIndex = null)
        )
    }

    @Test
    fun yearBoundary_rollsCleanlyIntoJanuary() {
        val newYearsEve = Calendar.getInstance().apply {
            set(2026, Calendar.DECEMBER, 31, 22, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val trigger = AlarmScheduler.nextTriggerMillis(hour = 10, minute = 0, nowMillis = newYearsEve)
        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(2027, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(10 to 0, hourMinuteOf(trigger))
    }
}
