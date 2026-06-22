package com.colorwalk.app.domain

import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

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

    /** Returns epoch-millis of local midnight at the start of today. */
    fun todayMidnightMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Local-calendar-date index (epoch day) for an instant. Uses java.time so two
     * photos on different local dates can never share an index — the previous
     * implementation divided local-midnight millis by 86,400,000 (UTC days), which
     * collapsed or skipped a day around DST transitions and in offsets where local
     * midnight sits within an hour of a UTC day boundary (B4).
     */
    internal fun epochMillisToDayIndex(millis: Long): Int =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
            .toInt()
}
