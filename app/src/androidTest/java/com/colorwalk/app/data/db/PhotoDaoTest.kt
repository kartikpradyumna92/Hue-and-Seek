package com.colorwalk.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class PhotoDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.photoDao()
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

    private fun tomorrowMidnight(): Long = midnightToday() + 24 * 60 * 60 * 1000L

    private fun yesterdayMidnight(): Long = midnightToday() - 24 * 60 * 60 * 1000L

    private fun makeEntity(
        filePath: String = "file:///photos/test.jpg",
        colorName: String = "Red",
        colorHex: String = "#E53935",
        dateTaken: Long = System.currentTimeMillis(),
        dominantColorHex: String = "#E53935"
    ) = PhotoEntity(
        filePath = filePath,
        colorName = colorName,
        colorHex = colorHex,
        dateTaken = dateTaken,
        latitude = null,
        longitude = null,
        locationName = null,
        dominantColorHex = dominantColorHex
    )

    // ── insert + getAllPhotos ─────────────────────────────────────────────────

    @Test
    fun insertPhoto_appearsInGetAllPhotosFlow() = runTest {
        val entity = makeEntity(dateTaken = midnightToday() + 1000L)
        dao.insert(entity)

        dao.getAllPhotos().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("Red", list[0].colorName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getAllPhotos_returnsMostRecentFirst() = runTest {
        val older = makeEntity(dateTaken = midnightToday() + 1000L)
        val newer = makeEntity(dateTaken = midnightToday() + 60_000L)
        dao.insert(older)
        dao.insert(newer)

        dao.getAllPhotos().test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertTrue("Newer photo should come first", list[0].dateTaken > list[1].dateTaken)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getAllPhotos_withNoPhotos_emitsEmptyList() = runTest {
        dao.getAllPhotos().test {
            val list = awaitItem()
            assertTrue(list.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── insert + getPhotoForDay ───────────────────────────────────────────────

    @Test
    fun getPhotoForDay_withPhotoInDayWindow_returnsDateTaken() = runTest {
        val ts = midnightToday() + 3600_000L // 1 hour into today
        dao.insert(makeEntity(dateTaken = ts))

        val result = dao.getPhotoForDay(midnightToday(), tomorrowMidnight())
        assertNotNull(result)
        assertEquals(ts, result)
    }

    @Test
    fun getPhotoForDay_withPhotoOutsideDayWindow_returnsNull() = runTest {
        val ts = yesterdayMidnight() + 3600_000L // yesterday
        dao.insert(makeEntity(dateTaken = ts))

        val result = dao.getPhotoForDay(midnightToday(), tomorrowMidnight())
        assertNull("Photo taken yesterday should not appear in today's window", result)
    }

    @Test
    fun getPhotoForDay_withNoPhotos_returnsNull() = runTest {
        val result = dao.getPhotoForDay(midnightToday(), tomorrowMidnight())
        assertNull(result)
    }

    // ── getRecentPhotoDates ──────────────────────────────────────────────────

    @Test
    fun getRecentPhotoDates_returnsDateTakenValues() = runTest {
        val ts1 = midnightToday() + 1000L
        val ts2 = midnightToday() + 2000L
        dao.insert(makeEntity(dateTaken = ts1))
        dao.insert(makeEntity(dateTaken = ts2))

        val dates = dao.getRecentPhotoDates()
        assertEquals(2, dates.size)
        assertTrue(dates.contains(ts1))
        assertTrue(dates.contains(ts2))
    }

    @Test
    fun getRecentPhotoDates_limitedToSixtyMostRecent() = runTest {
        val baseTime = midnightToday() - (70L * 24 * 60 * 60 * 1000L)
        // Insert 70 photos, each a day apart
        for (i in 0 until 70) {
            dao.insert(makeEntity(dateTaken = baseTime + i * 24 * 60 * 60 * 1000L))
        }

        val dates = dao.getRecentPhotoDates()
        assertEquals("Should return at most 60 dates", 60, dates.size)
    }

    @Test
    fun getRecentPhotoDates_returnsNewestFirst() = runTest {
        val ts1 = midnightToday() - 86_400_000L  // yesterday
        val ts2 = midnightToday() + 3600_000L    // today
        dao.insert(makeEntity(dateTaken = ts1))
        dao.insert(makeEntity(dateTaken = ts2))

        val dates = dao.getRecentPhotoDates()
        assertTrue("Most recent date should come first", dates[0] > dates[1])
    }

    // ── getAllPhotoDates ──────────────────────────────────────────────────────

    @Test
    fun getAllPhotoDates_returnsAllDatesWithoutLimit() = runTest {
        val baseTime = midnightToday() - (65L * 24 * 60 * 60 * 1000L)
        for (i in 0 until 65) {
            dao.insert(makeEntity(dateTaken = baseTime + i * 24 * 60 * 60 * 1000L))
        }

        val dates = dao.getAllPhotoDates()
        assertEquals("getAllPhotoDates must return all 65 entries", 65, dates.size)
    }

    // ── getPhotoIdForDay ──────────────────────────────────────────────────────

    @Test
    fun getPhotoIdForDay_withPhotoInDayWindow_returnsId() = runTest {
        val ts = midnightToday() + 7200_000L
        val id = dao.insert(makeEntity(dateTaken = ts))

        val result = dao.getPhotoIdForDay(midnightToday(), tomorrowMidnight())
        assertEquals(id, result)
    }

    @Test
    fun getPhotoIdForDay_withNoPhotoInDayWindow_returnsNull() = runTest {
        val result = dao.getPhotoIdForDay(midnightToday(), tomorrowMidnight())
        assertNull(result)
    }

    // ── updateFilePath ────────────────────────────────────────────────────────

    @Test
    fun updateFilePath_changesFilePathInDatabase() = runTest {
        val id = dao.insert(makeEntity(filePath = "file:///old/path.jpg"))
        val newPath = "file:///new/path.jpg"

        dao.updateFilePath(id, newPath)

        val snapshot = dao.getAllPhotosSnapshot()
        assertEquals(1, snapshot.size)
        assertEquals(newPath, snapshot[0].filePath)
    }

    @Test
    fun updateFilePath_doesNotAffectOtherRows() = runTest {
        val id1 = dao.insert(makeEntity(filePath = "file:///one.jpg"))
        val id2 = dao.insert(makeEntity(filePath = "file:///two.jpg"))

        dao.updateFilePath(id1, "file:///updated.jpg")

        val snapshot = dao.getAllPhotosSnapshot()
        val photo2 = snapshot.first { it.id == id2 }
        assertEquals("file:///two.jpg", photo2.filePath)
    }

    // ── deleteById ───────────────────────────────────────────────────────────

    @Test
    fun deleteById_removesPhotoFromGetAllPhotos() = runTest {
        val id = dao.insert(makeEntity())
        dao.deleteById(id)

        dao.getAllPhotos().test {
            val list = awaitItem()
            assertTrue("Deleted photo must not appear", list.none { it.id == id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteById_leavesOtherPhotosIntact() = runTest {
        val id1 = dao.insert(makeEntity(dateTaken = midnightToday() + 1000L))
        val id2 = dao.insert(makeEntity(dateTaken = midnightToday() + 2000L))

        dao.deleteById(id1)

        val snapshot = dao.getAllPhotosSnapshot()
        assertEquals(1, snapshot.size)
        assertEquals(id2, snapshot[0].id)
    }

    // ── getDistinctColors ─────────────────────────────────────────────────────

    @Test
    fun getDistinctColors_returnsUniqueColorEntries() = runTest {
        dao.insert(makeEntity(colorName = "Red", colorHex = "#E53935", dateTaken = midnightToday() + 1000L))
        dao.insert(makeEntity(colorName = "Red", colorHex = "#E53935", dateTaken = midnightToday() + 2000L))
        dao.insert(makeEntity(colorName = "Blue", colorHex = "#1E88E5", dateTaken = midnightToday() + 3000L))

        dao.getDistinctColors().test {
            val colors = awaitItem()
            assertEquals("Should have 2 distinct colors", 2, colors.size)
            assertTrue(colors.any { it.colorName == "Red" })
            assertTrue(colors.any { it.colorName == "Blue" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getDistinctColors_withNoPhotos_emitsEmptyList() = runTest {
        dao.getDistinctColors().test {
            val colors = awaitItem()
            assertTrue(colors.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── countByColor ─────────────────────────────────────────────────────────

    @Test
    fun countByColor_returnsCorrectCount() = runTest {
        dao.insert(makeEntity(colorName = "Green", dateTaken = midnightToday() + 1000L))
        dao.insert(makeEntity(colorName = "Green", dateTaken = midnightToday() + 2000L))
        dao.insert(makeEntity(colorName = "Red", dateTaken = midnightToday() + 3000L))

        assertEquals(2, dao.countByColor("Green"))
        assertEquals(1, dao.countByColor("Red"))
        assertEquals(0, dao.countByColor("Blue"))
    }

    // ── getAllPhotosSnapshot ──────────────────────────────────────────────────

    @Test
    fun getAllPhotosSnapshot_returnsAllPhotosAtThatMoment() = runTest {
        dao.insert(makeEntity(dateTaken = midnightToday() + 1000L))
        dao.insert(makeEntity(dateTaken = midnightToday() + 2000L))

        val snapshot = dao.getAllPhotosSnapshot()
        assertEquals(2, snapshot.size)
    }

    @Test
    fun getAllPhotosSnapshot_withNoPhotos_returnsEmptyList() = runTest {
        val snapshot = dao.getAllPhotosSnapshot()
        assertTrue(snapshot.isEmpty())
    }

    // ── day boundary tests ────────────────────────────────────────────────────

    @Test
    fun getPhotoForDay_photoAtEndOfDay_2359_isFoundInThatDayWindow() = runTest {
        // 23:59:59.999 — still within today
        val almostMidnight = tomorrowMidnight() - 1L
        dao.insert(makeEntity(dateTaken = almostMidnight))

        val result = dao.getPhotoForDay(midnightToday(), tomorrowMidnight())
        assertNotNull("Photo at 23:59:59.999 must be in today's window", result)
    }

    @Test
    fun getPhotoForDay_photoAtExactTomorrowMidnight_isNotInTodayWindow() = runTest {
        // The window is [midnight, tomorrowMidnight) — exclusive upper bound
        val exactTomorrow = tomorrowMidnight()
        dao.insert(makeEntity(dateTaken = exactTomorrow))

        val result = dao.getPhotoForDay(midnightToday(), tomorrowMidnight())
        assertNull("Photo at tomorrowMidnight (exclusive) must not be in today's window", result)
    }

    @Test
    fun getPhotoForDay_photoAtMidnightToday_isInTodayWindow() = runTest {
        val exactMidnight = midnightToday()
        dao.insert(makeEntity(dateTaken = exactMidnight))

        val result = dao.getPhotoForDay(midnightToday(), tomorrowMidnight())
        assertNotNull("Photo at midnight (inclusive) must be in today's window", result)
    }

    @Test
    fun getPhotoForDay_twoBoundaryPhotos_eachInCorrectWindow() = runTest {
        val endOfDay = tomorrowMidnight() - 1L          // 23:59:59.999 today
        val startOfTomorrow = tomorrowMidnight()        // 00:00:00.000 tomorrow

        dao.insert(makeEntity(dateTaken = endOfDay, colorName = "Red"))
        dao.insert(makeEntity(dateTaken = startOfTomorrow, colorName = "Blue"))

        val todayResult = dao.getPhotoForDay(midnightToday(), tomorrowMidnight())
        assertNotNull("End-of-day photo must be found in today's window", todayResult)
        assertEquals(endOfDay, todayResult)

        val tomorrowResult = dao.getPhotoForDay(tomorrowMidnight(), tomorrowMidnight() + 86_400_000L)
        assertNotNull("Start-of-tomorrow photo must be found in tomorrow's window", tomorrowResult)
        assertEquals(startOfTomorrow, tomorrowResult)
    }
}
