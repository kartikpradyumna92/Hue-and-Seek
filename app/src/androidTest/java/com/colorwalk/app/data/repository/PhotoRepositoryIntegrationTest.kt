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
import org.junit.Assert.assertNull
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
        // Tombstones live in shared prefs — start each test clean.
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PhotoRepository(context, db.photoDao(), db)
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
        colorHex: String = "#E53935",
        latitude: Double? = null,
        longitude: Double? = null,
        locationName: String? = null
    ): Long {
        return db.photoDao().insert(
            PhotoEntity(
                filePath = "file:///photos/test_${dateTaken}.jpg",
                colorName = colorName,
                colorHex = colorHex,
                dateTaken = dateTaken,
                latitude = latitude,
                longitude = longitude,
                locationName = locationName,
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
    fun getCapturedDayIndices_notLimitedToSixtyDays() = runTest {
        // A1 regression: indices must cover ALL photo days, not a 60-row window.
        val baseTime = midnightToday() - (64L * 24 * 60 * 60 * 1000L)
        for (i in 0 until 65) {
            insertPhoto(dateTaken = baseTime + i * 24 * 60 * 60 * 1000L)
        }

        val indices = repo.getCapturedDayIndices()
        assertEquals("All 65 photo days must be indexed", 65, indices.size)
    }

    // ── long streaks (A1 regression) ──────────────────────────────────────────

    @Test
    fun getStreak_seventyConsecutiveDays_returnsSeventy() = runTest {
        // Streaks longer than 60 days must be counted in full (milestones go to 365).
        for (i in 0 until 70) {
            insertPhoto(dateTaken = daysAgoNoon(i))
        }
        assertEquals(70, repo.getStreak())
    }

    @Test
    fun getStreak_multiplePhotosPerDay_doesNotCapStreak() = runTest {
        // 40 consecutive days × 3 photos each = 120 rows. Under the old 60-row
        // LIMIT only the most recent ~20 days were visible to the calculator.
        for (day in 0 until 40) {
            repeat(3) { n ->
                insertPhoto(dateTaken = daysAgoNoon(day) + n * 1000L)
            }
        }
        assertEquals(40, repo.getStreak())
    }

    // ── deletion tombstones (A2 regression) ───────────────────────────────────

    @Test
    fun deletePhoto_recordsFilenameAndDateTombstones() = runTest {
        val ts = daysAgoNoon(1)
        val id = insertPhoto(dateTaken = ts) // filePath = file:///photos/test_<ts>.jpg
        val photo = db.photoDao().getAllPhotosSnapshot().first { it.id == id }

        repo.deletePhoto(photo)

        assertTrue(
            "Deleted filename must be tombstoned",
            "test_$ts.jpg" in DeletionTombstones.deletedFilenames(context)
        )
        assertTrue(
            "Deleted dateTaken must be tombstoned",
            ts in DeletionTombstones.deletedDates(context)
        )
        assertTrue("Row must be gone from the DB", db.photoDao().getAllPhotosSnapshot().isEmpty())
    }

    @Test
    fun tombstones_recordAndClear_roundTrip() {
        DeletionTombstones.record(context, "ColorWalk_20260611_120000_123.jpg", 1_780_000_000_000L)
        assertTrue("ColorWalk_20260611_120000_123.jpg" in DeletionTombstones.deletedFilenames(context))
        assertTrue(1_780_000_000_000L in DeletionTombstones.deletedDates(context))

        DeletionTombstones.clear(context, "ColorWalk_20260611_120000_123.jpg", 1_780_000_000_000L)
        assertFalse("ColorWalk_20260611_120000_123.jpg" in DeletionTombstones.deletedFilenames(context))
        assertFalse(1_780_000_000_000L in DeletionTombstones.deletedDates(context))
    }

    @Test
    fun tombstones_recordWithNullFilename_stillRecordsDate() {
        DeletionTombstones.record(context, null, 42L)
        assertTrue(42L in DeletionTombstones.deletedDates(context))
    }

    // ── backfillLocationData (F5 / C1) ────────────────────────────────────────

    @Test
    fun backfillLocationData_withNoPhotos_doesNotThrow() = runTest {
        repo.backfillLocationData()
        assertTrue(db.photoDao().getAllPhotosSnapshot().isEmpty())
    }

    @Test
    fun backfillLocationData_withAllPhotosHavingRealGps_doesNotMarkAnyAsAttempted() = runTest {
        insertPhoto(dateTaken = daysAgoNoon(1), latitude = 37.77, longitude = -122.41)

        repo.backfillLocationData()

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val attempted = prefs.getStringSet("backfill_attempted_ids", emptySet())
        assertTrue("Photos with real GPS must not be queued for backfill", attempted!!.isEmpty())
    }

    @Test
    fun backfillLocationData_withPhotosLackingGps_marksAllAsAttempted() = runTest {
        val id1 = insertPhoto(dateTaken = daysAgoNoon(1))
        val id2 = insertPhoto(dateTaken = daysAgoNoon(2))

        repo.backfillLocationData()

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val attempted = prefs.getStringSet("backfill_attempted_ids", emptySet())!!
            .mapNotNull { it.toLongOrNull() }.toSet()
        assertTrue("id1 must be marked attempted", id1 in attempted)
        assertTrue("id2 must be marked attempted", id2 in attempted)
    }

    @Test
    fun backfillLocationData_calledTwice_secondCallIsNoop() = runTest {
        insertPhoto(dateTaken = daysAgoNoon(1))

        repo.backfillLocationData()   // first run — marks IDs attempted
        repo.backfillLocationData()   // second run — toAttempt is empty, exits early

        // Verify the DB was not corrupted across both calls.
        assertEquals(1, db.photoDao().getAllPhotosSnapshot().size)
    }

    @Test
    fun backfillLocationData_afterDeleteAndReinsert_freshAttemptMade() = runTest {
        val id = insertPhoto(dateTaken = daysAgoNoon(1))
        val photo = db.photoDao().getAllPhotosSnapshot().first { it.id == id }

        repo.backfillLocationData()   // marks id as attempted

        repo.deletePhoto(photo)       // clearBackfillAttempted(id) called in deletePhoto

        val newId = insertPhoto(dateTaken = daysAgoNoon(1))

        repo.backfillLocationData()   // must attempt newId since marker was cleared

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val attempted = prefs.getStringSet("backfill_attempted_ids", emptySet())!!
            .mapNotNull { it.toLongOrNull() }.toSet()
        assertTrue("Re-inserted photo must be marked attempted after fresh backfill", newId in attempted)
    }

    // ── tagPhotoLocation (B7 regression) ──────────────────────────────────────

    @Test
    fun tagPhotoLocation_keepsOwnCoordinates_whenPhotoHasRealFix() = runTest {
        val id = insertPhoto(dateTaken = daysAgoNoon(1), latitude = 37.77, longitude = -122.41)

        repo.tagPhotoLocation(id, "San Francisco")

        val photo = db.photoDao().getAllPhotosSnapshot().first { it.id == id }
        assertEquals("San Francisco", photo.locationName)
        assertEquals("Real GPS fix must never be overwritten by tagging", 37.77, photo.latitude!!, 0.0001)
        assertEquals(-122.41, photo.longitude!!, 0.0001)
    }

    @Test
    fun tagPhotoLocation_inheritsCoordinates_fromSameNamedPhoto_whenNoFix() = runTest {
        insertPhoto(
            dateTaken = daysAgoNoon(2),
            latitude = 37.77, longitude = -122.41, locationName = "San Francisco"
        )
        val id = insertPhoto(dateTaken = daysAgoNoon(1)) // no coordinates

        repo.tagPhotoLocation(id, "San Francisco")

        val photo = db.photoDao().getAllPhotosSnapshot().first { it.id == id }
        assertEquals("San Francisco", photo.locationName)
        assertEquals(37.77, photo.latitude!!, 0.0001)
        assertEquals(-122.41, photo.longitude!!, 0.0001)
    }

    @Test
    fun tagPhotoLocation_nullIslandCoordinates_areTreatedAsNoFix() = runTest {
        val id = insertPhoto(dateTaken = daysAgoNoon(1), latitude = 0.0, longitude = 0.0)

        repo.tagPhotoLocation(id, "Somewhere")

        val photo = db.photoDao().getAllPhotosSnapshot().first { it.id == id }
        assertEquals("Somewhere", photo.locationName)
        assertNull("A (0,0) bad fix must not be kept as a real coordinate", photo.latitude)
        assertNull(photo.longitude)
    }
}
