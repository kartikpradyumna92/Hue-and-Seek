package com.colorwalk.app.domain

import java.util.Calendar
import java.util.concurrent.TimeUnit

object StreakCalculator {

    /** Given epoch-millis timestamps of accepted photos, compute current streak in days. */
    fun compute(photoTimestamps: List<Long>): Int {
        if (photoTimestamps.isEmpty()) return 0

        val days = photoTimestamps
            .map { epochMillisToDayIndex(it) }
            .toSortedSet()
            .toList()
            .reversed() // most recent first

        val todayIndex = epochMillisToDayIndex(System.currentTimeMillis())

        // Streak must include today or yesterday to be "live"
        if (days.first() < todayIndex - 1) return 0

        var streak = 1
        for (i in 1 until days.size) {
            if (days[i - 1] - days[i] == 1) streak++ else break
        }
        return streak
    }

    private fun epochMillisToDayIndex(millis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return TimeUnit.MILLISECONDS.toDays(cal.timeInMillis).toInt()
    }
}
