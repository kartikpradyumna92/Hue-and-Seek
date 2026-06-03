package com.colorwalk.app.data.repository

import android.content.Context
import android.content.ContentResolver
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.domain.StreakCalculator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * Integration tests for [PhotoRepository] using a real in-memory Room database.
 *
 * Methods that interact with MediaStore, the filesystem, GPS, or geocoding are exercised only
 * at the level of DAO queries — [savePhoto] and [deletePhoto] (which touch storage/GPS) are
 * covered in isolation in [PhotoDaoTest]. Here we focus on the pure-query methods:
 * [hasCapturedToday], [getStreak], and [getCapturedDayIndices].
 */
@RunWith(AndroidJUnit4::class)
class PhotoRepositoryIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PhotoRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PhotoRepository(context, db.photoDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun midnightToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun daysAgoNoon(n: Int): Long {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -n)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private suspend fun insertPhoto(
        dateTaken: Long,
        colorName: String = "Red",
        colorHex: String = "#E53935"
    ): Long {
        return db.photoDao().insert(
            PhotoEntity(
                filePath = "file:///photos/test_${dateTaken}.jpg",
                colorName = colorName,
                colorHex = colorHex,
                dateTaken = dateTaken,
                latitude = null,
                longitude = null,
                locationName = null,
                dominantColorHex = colorHex
            )
        )
    }

    // ── hasCapturedToday ──────────────────────────────────────────────────────

    @Test
    fun hasCapturedToday_withNoPhotos_returnsFalse() = runTest {
        assertFalse(repo.hasCapturedToday())
    }

    @Test
    fun hasCapturedToday_withPhotoTakenToday_returnsTrue() = runTest {
        insertPhoto(dateTaken = midnightToday() + 3600_000L) // 1 hour after midnight
        assertTrue(repo.hasCapturedToday())
    }

    @Test
    fun hasCapturedToday_withPhotoTakenYesterday_returnsFalse() = runTest {
        insertPhoto(dateTaken = daysAgoNoon(1))
        assertFalse(repo.hasCapturedToday())
    }

    @Test
    fun hasCapturedToday_withMultiplePhotos_onlyTodayMatters() = runTest {
        insertPhoto(dateTaken = daysAgoNoon(3))
        insertPhoto(dateTaken = daysAgoNoon(2))
        insertPhoto(dateTaken = midnightToday() + 1000L)  // today

        assertTrue("Should return true because one photo is from today", repo.hasCapturedToday())
    }

    @Test
    fun hasCapturedToday_withPhotoExactlyAtMidnight_returnsTrue() = runTest {
        insertPhoto(dateTaken = midnightToday())
        assertTrue(repo.hasCapturedToday())
    }

    // ── getStreak ─────────────────────────────────────────────────────────────

    @Test
    fun getStreak_withNoPhotos_returnsZero() = runTest {
        assertEquals(0, repo.getStreak())
    }

    @Test
    fun getStreak_withOnePhotoToday_returnsOne() = runTest {
        insertPhoto(dateTaken = midnightToday() + 3600_000L)
        assertEquals(1, repo.getStreak())
    }

    @Test
    fun getStreak_withThreeConsecutiveDaysEndingToday_returnsThree() = runTest {
        insertPhoto(dateTaken = daysAgoNoon(2))
        insertPhoto(dateTaken = daysAgoNoon(1))
        insertPhoto(dateTaken = midnightToday() + 3600_000L)

        assertEquals(3, repo.getStreak())
    }

    @Test
    fun getStreak_withGapInStreak_returnsOnlyCurrentRun() = runTest {
        // 4 days ago, 3 days ago, then gap, then yesterday and today
        insertPhoto(dateTaken = daysAgoNoon(4))
        insertPhoto(dateTaken = daysAgoNoon(3))
        // gap at 2 days ago
        insertPhoto(dateTaken = daysAgoNoon(1))
        insertPhoto(dateTaken = midnightToday() + 3600_000L)

        assertEquals(2, repo.getStreak())
    }

    @Test
    fun getStreak_withOnlyYesterdayPhoto_returnsOne() = runTest {
        // Yesterday is still "live" (within the grace window)
        insertPhoto(dateTaken = daysAgoNoon(1))
        assertEquals(1, repo.getStreak())
    }

    @Test
    fun getStreak_withDeadStreak_returnsZero() = runTest {
        // Photo from 3 days ago — no today or yesterday
        insertPhoto(dateTaken = daysAgoNoon(3))
        assertEquals(0, repo.getStreak())
    }

    // ── getCapturedDayIndices ─────────────────────────────────────────────────

    @Test
    fun getCapturedDayIndices_withNoPhotos_returnsEmptySet() = runTest {
        val indices = repo.getCapturedDayIndices()
        assertTrue(indices.isEmpty())
    }

    @Test
    fun getCapturedDayIndices_returnsCorrectDayIndices() = runTest {
        val ts1 = midnightToday() + 3600_000L
        val ts2 = daysAgoNoon(1)
        val ts3 = daysAgoNoon(2)
        insertPhoto(dateTaken = ts1)
        insertPhoto(dateTaken = ts2)
        insertPhoto(dateTaken = ts3)

        val expected = setOf(ts1, ts2, ts3).map { StreakCalculator.epochMillisToDayIndex(it) }.toSet()
        val actual = repo.getCapturedDayIndices()
        assertEquals(expected, actual)
    }

    @Test
    fun getCapturedDayIndices_withDuplicatesOnSameDay_returnsSingleIndex() = runTest {
        // Two photos on the same day → only one day index
        insertPhoto(dateTaken = midnightToday() + 1000L)
        insertPhoto(dateTaken = midnightToday() + 2000L)

        val indices = repo.getCapturedDayIndices()
        assertEquals("Duplicate day should collapse to single index", 1, indices.size)
    }

    @Test
    fun getCapturedDayIndices_limitedToSixtyDays() = runTest {
        // Insert 65 photos, each a day apart — only the 60 most recent should be indexed
        val baseTime = midnightToday() - (64L * 24 * 60 * 60 * 1000L)
        for (i in 0 until 65) {
            insertPhoto(dateTaken = baseTime + i * 24 * 60 * 60 * 1000L)
        }

        val indices = repo.getCapturedDayIndices()
        assertEquals(
            "getCapturedDayIndices should be bounded by the 60-entry LIMIT in getRecentPhotoDates",
            60,
            indices.size
        )
    }
}
