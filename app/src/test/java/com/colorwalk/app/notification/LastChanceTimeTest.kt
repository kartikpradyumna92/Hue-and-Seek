package com.colorwalk.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastChanceTimeTest {

    private fun m(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun defaultEvening_nudgeAtNinePm() {
        // Default evening slot (17:00) is early — the nudge stays at its 21:00 base.
        assertEquals(m(21), AlarmScheduler.lastChanceMinuteOfDay(true, m(17)))
    }

    @Test
    fun eveningDisabled_nudgeAtNinePm() {
        assertEquals(m(21), AlarmScheduler.lastChanceMinuteOfDay(false, m(23, 45)))
    }

    @Test
    fun eveningJustBeforeLateThreshold_nudgeStaysAtBase() {
        assertEquals(m(21), AlarmScheduler.lastChanceMinuteOfDay(true, m(19, 29)))
    }

    @Test
    fun lateEvening_nudgeMovesNinetyMinutesAfter() {
        assertEquals(m(21, 30), AlarmScheduler.lastChanceMinuteOfDay(true, m(20)))
        assertEquals(m(22, 30), AlarmScheduler.lastChanceMinuteOfDay(true, m(21)))
    }

    @Test
    fun veryLateEvening_nudgeCapsAtElevenThirty() {
        // 22:30 + 90min would be midnight — capped to 23:30.
        assertEquals(m(23, 30), AlarmScheduler.lastChanceMinuteOfDay(true, m(22, 30)))
        assertEquals(m(23, 30), AlarmScheduler.lastChanceMinuteOfDay(true, m(23)))
    }

    @Test
    fun eveningSoLateItIsTheLastCall_noNudge() {
        // Cap (23:30) would land within 30 min of the evening slot — the evening
        // reminder already serves as the last call, so no nudge is scheduled.
        assertNull(AlarmScheduler.lastChanceMinuteOfDay(true, m(23, 15)))
        assertNull(AlarmScheduler.lastChanceMinuteOfDay(true, m(23, 45)))
    }

    @Test
    fun nudgeNeverLandsBeforeItsBaseOrAfterTheCap() {
        for (evening in 0 until 24 * 60 step 5) {
            val nudge = AlarmScheduler.lastChanceMinuteOfDay(true, evening) ?: continue
            assertTrue(nudge >= AlarmScheduler.LAST_CHANCE_DEFAULT_MINUTE)
            assertTrue(nudge <= AlarmScheduler.LAST_CHANCE_LATEST_MINUTE)
            // Whenever a nudge exists it keeps a real gap after the evening slot.
            assertTrue(nudge >= evening + AlarmScheduler.LAST_CHANCE_MIN_GAP)
        }
    }
}
