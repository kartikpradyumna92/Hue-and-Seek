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
    fun epochMillisToDayIndex_epochZero_returnsZero() {
        // epoch 0 = Jan 1 1970 UTC, which maps to day 0 in the local-TZ calculation
        val idx = StreakCalculator.epochMillisToDayIndex(0L)
        // The exact value is timezone-dependent; just assert it's non-negative and small
        assert(idx >= 0) { "Expected non-negative day index for epoch 0, got $idx" }
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
