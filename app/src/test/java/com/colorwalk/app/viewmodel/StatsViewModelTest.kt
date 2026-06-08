package com.colorwalk.app.viewmodel

import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repo: PhotoRepository

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        coEvery { repo.getAllPhotosSnapshot() } returns emptyList()
        coEvery { repo.getStreak() } returns 0
    }

    private fun buildViewModel() = StatsViewModel(repo)

    private val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /** Returns a timestamp guaranteed to fall N calendar days before today (local noon). */
    private fun daysAgo(n: Int): Long {
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -n)
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun makePhoto(
        id: Long,
        dateTaken: Long = daysAgo(0),
        colorName: String = "Red",
        colorHex: String = "#E53935"
    ) = PhotoEntity(
        id = id,
        filePath = "/photos/$id.jpg",
        colorName = colorName,
        colorHex = colorHex,
        dateTaken = dateTaken,
        latitude = null,
        longitude = null,
        locationName = null,
        dominantColorHex = colorHex
    )

    // ── empty state ──────────────────────────────────────────────────────────

    @Test
    fun load_emptyPhotos_allCountsAreZero() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        with(vm.state.value) {
            assertEquals(0, currentStreak)
            assertEquals(0, bestStreak)
            assertEquals(0, totalPhotos)
            assertEquals(0, totalActiveDays)
            assertNull(favouriteColorName)
            assertNull(favouriteColorHex)
            assertTrue(photosByDayIndex.isEmpty())
        }
    }

    @Test
    fun initialSelectedDayPhotos_isEmpty() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.selectedDayPhotos.isEmpty())
    }

    // ── totalPhotos ──────────────────────────────────────────────────────────

    @Test
    fun load_singlePhoto_totalPhotosIsOne() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(makePhoto(1L))
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.totalPhotos)
    }

    @Test
    fun load_threePhotos_totalPhotosIsThree() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(
            makePhoto(1L), makePhoto(2L, dateTaken = daysAgo(1)), makePhoto(3L, dateTaken = daysAgo(2))
        )
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(3, vm.state.value.totalPhotos)
    }

    // ── totalActiveDays ──────────────────────────────────────────────────────

    @Test
    fun load_singlePhoto_activeDaysIsOne() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(makePhoto(1L))
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.totalActiveDays)
    }

    @Test
    fun load_twoPhotosOnDifferentDays_activeDaysIsTwo() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(
            makePhoto(1L, dateTaken = daysAgo(0)),
            makePhoto(2L, dateTaken = daysAgo(1))
        )
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.totalActiveDays)
    }

    @Test
    fun load_twoPhotosOnSameDay_activeDaysIsOne() = runTest {
        val base = daysAgo(0)
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(
            makePhoto(1L, dateTaken = base),
            makePhoto(2L, dateTaken = base + 3_600_000L) // same day, 1 hour later
        )
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.totalActiveDays)
    }

    // ── currentStreak ─────────────────────────────────────────────────────────

    @Test
    fun load_currentStreak_delegatesToRepo() = runTest {
        coEvery { repo.getStreak() } returns 7
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(makePhoto(1L))
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(7, vm.state.value.currentStreak)
    }

    // ── bestStreak ────────────────────────────────────────────────────────────

    @Test
    fun load_singlePhoto_bestStreakIsOne() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(makePhoto(1L))
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.bestStreak)
    }

    @Test
    fun load_fourConsecutiveDays_bestStreakIsFour() = runTest {
        val photos = (0..3).mapIndexed { i, day -> makePhoto(i.toLong(), dateTaken = daysAgo(day)) }
        coEvery { repo.getAllPhotosSnapshot() } returns photos
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(4, vm.state.value.bestStreak)
    }

    @Test
    fun load_twoRunsSeparatedByGap_bestStreakIsLongerRun() = runTest {
        // Days 0,1,2 (run=3) and days 5,6 (run=2) — gap at 3,4
        val photos = listOf(0, 1, 2, 5, 6)
            .mapIndexed { i, day -> makePhoto(i.toLong(), dateTaken = daysAgo(day)) }
        coEvery { repo.getAllPhotosSnapshot() } returns photos
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(3, vm.state.value.bestStreak)
    }

    @Test
    fun load_allNonConsecutiveDays_bestStreakIsOne() = runTest {
        val photos = listOf(0, 2, 4, 6)
            .mapIndexed { i, day -> makePhoto(i.toLong(), dateTaken = daysAgo(day)) }
        coEvery { repo.getAllPhotosSnapshot() } returns photos
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.bestStreak)
    }

    @Test
    fun load_bestStreakAndCurrentStreakCanDiffer() = runTest {
        // Provide a long best streak historically but mock current streak as 2
        coEvery { repo.getStreak() } returns 2
        val photos = (0..9).mapIndexed { i, day -> makePhoto(i.toLong(), dateTaken = daysAgo(day)) }
        coEvery { repo.getAllPhotosSnapshot() } returns photos
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.currentStreak)
        assertEquals(10, vm.state.value.bestStreak)
    }

    // ── favouriteColor ────────────────────────────────────────────────────────

    @Test
    fun load_favouriteColor_isColorWithMostPhotos() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(
            makePhoto(1L, colorName = "Blue", colorHex = "#1E88E5"),
            makePhoto(2L, colorName = "Blue", colorHex = "#1E88E5"),
            makePhoto(3L, colorName = "Red",  colorHex = "#E53935")
        )
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals("Blue", vm.state.value.favouriteColorName)
        assertEquals("#1E88E5", vm.state.value.favouriteColorHex)
    }

    @Test
    fun load_singlePhotoColor_isFavourite() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(
            makePhoto(1L, colorName = "Green", colorHex = "#43A047")
        )
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals("Green", vm.state.value.favouriteColorName)
    }

    // ── photosByDayIndex ──────────────────────────────────────────────────────

    @Test
    fun load_photosOnTwoDays_photosByDayIndexHasTwoEntries() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(
            makePhoto(1L, dateTaken = daysAgo(0)),
            makePhoto(2L, dateTaken = daysAgo(1))
        )
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.photosByDayIndex.size)
    }

    @Test
    fun load_twoPhotosOnSameDay_photosByDayIndexHasBothAndMostRecentIsFirst() = runTest {
        val earlier = daysAgo(0) - 3_600_000L // 1 hour earlier in the day
        val later   = daysAgo(0)
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(
            makePhoto(1L, dateTaken = earlier),
            makePhoto(2L, dateTaken = later)
        )
        val vm = buildViewModel()
        advanceUntilIdle()
        val dayIndex = StreakCalculator.epochMillisToDayIndex(later)
        val dayPhotos = vm.state.value.photosByDayIndex[dayIndex]
        assertEquals(2, dayPhotos?.size)
        assertEquals(2L, dayPhotos?.first()?.id) // most recent first
    }

    @Test
    fun load_photosByDayIndex_dayIndexMatchesStreakCalculator() = runTest {
        val ts = daysAgo(3)
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(makePhoto(1L, dateTaken = ts))
        val vm = buildViewModel()
        advanceUntilIdle()
        val expectedIndex = StreakCalculator.epochMillisToDayIndex(ts)
        assertTrue(vm.state.value.photosByDayIndex.containsKey(expectedIndex))
    }

    // ── selectDay ─────────────────────────────────────────────────────────────

    @Test
    fun selectDay_setsSelectedDayPhotos() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()
        val photos = listOf(makePhoto(99L), makePhoto(100L))
        vm.selectDay(photos)
        assertEquals(photos, vm.state.value.selectedDayPhotos)
    }

    @Test
    fun selectDay_emptyList_clearsSelectedDayPhotos() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()
        vm.selectDay(listOf(makePhoto(99L)))
        vm.selectDay(emptyList())
        assertTrue(vm.state.value.selectedDayPhotos.isEmpty())
    }

    @Test
    fun selectDay_doesNotAffectOtherStateFields() = runTest {
        coEvery { repo.getAllPhotosSnapshot() } returns listOf(makePhoto(1L))
        coEvery { repo.getStreak() } returns 5
        val vm = buildViewModel()
        advanceUntilIdle()
        val totalBefore = vm.state.value.totalPhotos
        val streakBefore = vm.state.value.currentStreak

        vm.selectDay(listOf(makePhoto(99L)))

        assertEquals(totalBefore, vm.state.value.totalPhotos)
        assertEquals(streakBefore, vm.state.value.currentStreak)
    }
}
