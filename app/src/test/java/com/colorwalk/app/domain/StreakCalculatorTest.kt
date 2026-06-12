package com.colorwalk.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class StreakCalculatorTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Returns epoch-millis for "today" at the given hour/minute (local TZ). */
    private fun todayAt(hour: Int = 12, minute: Int = 0): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Returns epoch-millis for N days ago at the given hour (local TZ). */
    private fun daysAgo(n: Int, hour: Int = 12): Long {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -n)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // ── compute() ────────────────────────────────────────────────────────────

    @Test
    fun streak_withEmptyList_returnsZero() {
        assertEquals(0, StreakCalculator.compute(emptyList()))
    }

    @Test
    fun streak_withSinglePhotoToday_returnsOne() {
        assertEquals(1, StreakCalculator.compute(listOf(todayAt(9))))
    }

    @Test
    fun streak_withSinglePhotoYesterday_returnsOne() {
        // A photo from yesterday keeps the streak alive (today is allowed as buffer)
        assertEquals(1, StreakCalculator.compute(listOf(daysAgo(1))))
    }

    @Test
    fun streak_withSinglePhotoTwoDaysAgo_returnsZero() {
        // Gap between 2-days-ago and "yesterday" breaks the streak
        assertEquals(0, StreakCalculator.compute(listOf(daysAgo(2))))
    }

    @Test
    fun streak_withThreeConsecutiveDaysEndingToday_returnsThree() {
        val photos = listOf(daysAgo(2), daysAgo(1), todayAt(8))
        assertEquals(3, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_withThreeConsecutiveDaysEndingYesterday_returnsThree() {
        // yesterday is still "live" — one day grace
        val photos = listOf(daysAgo(3), daysAgo(2), daysAgo(1))
        assertEquals(3, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_withHundredConsecutiveDays_returnsHundred() {
        // A1 regression: long streaks must be counted in full (milestones go to 365)
        val photos = (0 until 100).map { daysAgo(it) }
        assertEquals(100, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_withMultiplePhotosPerDay_countsDaysNotPhotos() {
        // A1 regression: 3 photos/day for 70 days = 210 timestamps → streak is 70
        val photos = (0 until 70).flatMap { day ->
            listOf(daysAgo(day, hour = 9), daysAgo(day, hour = 12), daysAgo(day, hour = 18))
        }
        assertEquals(70, StreakCalculator.compute(photos))
    }

    // ── epochMillisToDayIndex: DST / timezone edges (B4 regression) ──────────

    private fun withTimeZone(id: String, block: () -> Unit) {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
            block()
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    private fun millisAt(year: Int, month: Int, day: Int, hour: Int, zone: String): Long =
        java.time.ZonedDateTime
            .of(year, month, day, hour, 0, 0, 0, java.time.ZoneId.of(zone))
            .toInstant().toEpochMilli()

    @Test
    fun dayIndex_consecutiveDaysAcrossSpringForward_areConsecutive() {
        // US DST 2026 starts March 8 (23-hour day in Los Angeles)
        withTimeZone("America/Los_Angeles") {
            val zone = "America/Los_Angeles"
            val i7 = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 3, 7, 12, zone))
            val i8 = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 3, 8, 12, zone))
            val i9 = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 3, 9, 12, zone))
            assertEquals(i7 + 1, i8)
            assertEquals(i8 + 1, i9)
        }
    }

    @Test
    fun dayIndex_consecutiveDaysAcrossFallBack_areConsecutive() {
        // US DST 2026 ends November 1 (25-hour day in Los Angeles)
        withTimeZone("America/Los_Angeles") {
            val zone = "America/Los_Angeles"
            val i31 = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 10, 31, 12, zone))
            val i1  = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 11, 1, 12, zone))
            val i2  = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 11, 2, 12, zone))
            assertEquals(i31 + 1, i1)
            assertEquals(i1 + 1, i2)
        }
    }

    @Test
    fun dayIndex_localMidnightsInFarEastOffset_mapToDistinctConsecutiveDays() {
        // UTC+13: local midnight is 11:00 UTC the previous day — the old
        // millis/86,400,000 division mapped these to the wrong/same UTC day.
        withTimeZone("Pacific/Tongatapu") {
            val zone = "Pacific/Tongatapu"
            val i1 = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 6, 10, 0, zone))
            val i2 = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 6, 11, 0, zone))
            assertEquals(i1 + 1, i2)
        }
    }

    @Test
    fun dayIndex_sameLocalDay_startAndEnd_shareOneIndex() {
        withTimeZone("Pacific/Tongatapu") {
            val zone = "Pacific/Tongatapu"
            val start = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 6, 10, 0, zone))
            val end   = StreakCalculator.epochMillisToDayIndex(millisAt(2026, 6, 10, 23, zone))
            assertEquals(start, end)
        }
    }

    @Test
    fun streak_withFiveConsecutiveDaysIncludingTodayAndYesterday_returnsFive() {
        val photos = listOf(daysAgo(4), daysAgo(3), daysAgo(2), daysAgo(1), todayAt(10))
        assertEquals(5, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_withGapOnDayThreeOfFive_returnsTwoFromMostRecentRun() {
        // days-ago: 4, 3, [gap at 2], 1, today  → only 1+today = 2
        val photos = listOf(daysAgo(4), daysAgo(3), daysAgo(1), todayAt(10))
        assertEquals(2, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_withDuplicatePhotosOnSameDay_countsAsOneDay() {
        // Three captures today should still count as streak=1
        val photos = listOf(todayAt(8), todayAt(11), todayAt(16))
        assertEquals(1, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_withPhotosOutOfChronologicalOrder_stillComputesCorrectly() {
        // Reversed input — the algorithm should sort them
        val photos = listOf(todayAt(10), daysAgo(2), daysAgo(1))
        assertEquals(3, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_withExactly60PhotoEntries_computesCorrectStreak() {
        // 60 consecutive days (0..59 days ago); most recent day is today
        val photos = (0 until 60).map { daysAgo(it, hour = 9) }
        assertEquals(60, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_atMilestoneSevenDays_returnsSeven() {
        val photos = (0 until 7).map { daysAgo(it) }
        assertEquals(7, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_atMilestoneTwentyOneDays_returnsTwentyOne() {
        val photos = (0 until 21).map { daysAgo(it) }
        assertEquals(21, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_atMilestoneThirtyDays_returnsThirty() {
        val photos = (0 until 30).map { daysAgo(it) }
        assertEquals(30, StreakCalculator.compute(photos))
    }

    // ── epochMillisToDayIndex() ──────────────────────────────────────────────

    @Test
    fun epochMillisToDayIndex_sameCalendarDay_differentHours_returnsSameIndex() {
        val morningMillis = todayAt(0, 0)
        val eveningMillis = todayAt(23, 59)
        assertEquals(
            StreakCalculator.epochMillisToDayIndex(morningMillis),
            StreakCalculator.epochMillisToDayIndex(eveningMillis)
        )
    }

    @Test
    fun epochMillisToDayIndex_consecutiveDays_differByOne() {
        val todayIndex = StreakCalculator.epochMillisToDayIndex(todayAt(12))
        val yesterdayIndex = StreakCalculator.epochMillisToDayIndex(daysAgo(1, 12))
        assertEquals(1, todayIndex - yesterdayIndex)
    }

    @Test
    fun epochMillisToDayIndex_epochZero_isLocalDateOfEpoch() {
        // epoch 0 = Jan 1 1970 UTC = day 0 — or Dec 31 1969 (day -1) in zones behind
        // UTC. Indices are epoch days of the LOCAL date; only differences matter.
        val idx = StreakCalculator.epochMillisToDayIndex(0L)
        assert(idx == 0 || idx == -1) { "Expected epoch day 0 or -1 for epoch 0, got $idx" }
    }

    @Test
    fun epochMillisToDayIndex_returnsValueMatchingManualCalculation() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val expectedDays = TimeUnit.MILLISECONDS.toDays(cal.timeInMillis).toInt()
        assertEquals(expectedDays, StreakCalculator.epochMillisToDayIndex(System.currentTimeMillis()))
    }

    @Test
    fun streak_photosOnlyTwoDaysAgoAndThreeDaysAgo_returnsZero() {
        // Streak is dead — most recent day is 2 days ago, no today or yesterday
        val photos = listOf(daysAgo(2), daysAgo(3))
        assertEquals(0, StreakCalculator.compute(photos))
    }

    @Test
    fun streak_singlePhotoAtMidnightTonight_returnsOne() {
        // 23:59 tonight is still "today"
        val photos = listOf(todayAt(23, 59))
        assertEquals(1, StreakCalculator.compute(photos))
    }
}
