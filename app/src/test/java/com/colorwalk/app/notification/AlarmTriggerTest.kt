package com.colorwalk.app.notification

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
