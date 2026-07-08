package com.colorwalk.app.viewmodel

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.domain.WALK_COLORS
import com.colorwalk.app.domain.colorForDay
import com.colorwalk.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repo: PhotoRepository
    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var prefsEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)
        prefsEditor = mockk(relaxed = true)
        context = mockk(relaxed = true)

        // SharedPreferences chain
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { sharedPrefs.edit() } returns prefsEditor
        every { prefsEditor.putBoolean(any(), any()) } returns prefsEditor
        every { prefsEditor.putInt(any(), any()) } returns prefsEditor
        every { prefsEditor.apply() } returns Unit

        // Default prefs — no review shown, no celebration recorded
        every { sharedPrefs.getBoolean("review_shown", false) } returns false
        every { sharedPrefs.getInt("last_celebration_day", -1) } returns -1

        // Default repo behaviour
        coEvery { repo.getStreak() } returns 0
        coEvery { repo.getCapturedDayIndices() } returns emptySet()
        coEvery { repo.syncGalleryWithDatabase() } returns Unit
        every { repo.getAllPhotos() } returns flowOf(emptyList())
        every { repo.getDistinctColors() } returns flowOf(emptyList())
    }

    private fun buildViewModel(): HomeViewModel = HomeViewModel(repo, context)

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    fun initialState_colorOfDay_isNotNull() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()
        assertNotNull(vm.state.value.colorOfDay)
    }

    @Test
    fun initialState_streak_isZeroWhenRepoReturnsZero() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(0, vm.state.value.streak)
    }

    @Test
    fun initialState_capturedToday_isFalseWhenNoCapturedIndices() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.capturedToday)
    }

    @Test
    fun initialState_colorOfDay_matchesColorForDayToday() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()
        val expected = colorForDay(System.currentTimeMillis())
        assertEquals(expected, vm.state.value.colorOfDay)
    }

    // ── load() reflects repo values ──────────────────────────────────────────

    @Test
    fun load_withStreakThree_stateStreakIsThree() = runTest {
        coEvery { repo.getStreak() } returns 3
        val vm = buildViewModel()
        advanceUntilIdle()
        assertEquals(3, vm.state.value.streak)
    }

    @Test
    fun load_withTodayCaptured_capturedTodayIsTrue() = runTest {
        val todayIndex = com.colorwalk.app.domain.StreakCalculator
            .epochMillisToDayIndex(System.currentTimeMillis())
        coEvery { repo.getCapturedDayIndices() } returns setOf(todayIndex)
        val vm = buildViewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.capturedToday)
    }

    @Test
    fun load_capturedDayIndices_propagatedToState() = runTest {
        val indices = setOf(100, 101, 102)
        coEvery { repo.getCapturedDayIndices() } returns indices
        val vm = buildViewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.capturedDayIndices.containsAll(indices))
    }

    // ── celebration logic ────────────────────────────────────────────────────

    @Test
    fun celebration_dailyCelebration_triggersWhenCapturedTodayTransitionsFalseToTrue() = runTest {
        // First load: not captured. Second load (simulating capture): captured today.
        val todayIndex = com.colorwalk.app.domain.StreakCalculator
            .epochMillisToDayIndex(System.currentTimeMillis())
        coEvery { repo.getStreak() } returns 1
        coEvery { repo.getCapturedDayIndices() } returns emptySet()

        val vm = buildViewModel()
        advanceUntilIdle()

        // Simulate capture happening
        coEvery { repo.getCapturedDayIndices() } returns setOf(todayIndex)
        // Manually call load() (not fromSync)
        vm.load(fromSync = false)
        advanceUntilIdle()

        val celebration = vm.state.value.celebrationState
        assertNotNull("Expected celebration after false→true transition", celebration)
    }

    @Test
    fun celebration_milestoneStreak_triggersWithMilestoneState() = runTest {
        val todayIndex = com.colorwalk.app.domain.StreakCalculator
            .epochMillisToDayIndex(System.currentTimeMillis())
        coEvery { repo.getStreak() } returns 7
        coEvery { repo.getCapturedDayIndices() } returns emptySet()

        val vm = buildViewModel()
        advanceUntilIdle()

        coEvery { repo.getCapturedDayIndices() } returns setOf(todayIndex)
        vm.load(fromSync = false)
        advanceUntilIdle()

        val celebration = vm.state.value.celebrationState
        assertTrue(
            "Expected Milestone celebration at streak=7, got $celebration",
            celebration is CelebrationState.Milestone && celebration.days == 7
        )
    }

    @Test
    fun celebration_fromSyncLoad_doesNotTriggerCelebration() = runTest {
        val todayIndex = com.colorwalk.app.domain.StreakCalculator
            .epochMillisToDayIndex(System.currentTimeMillis())
        coEvery { repo.getStreak() } returns 3
        // Start UNcaptured so the init load can't celebrate — this test is about the
        // fromSync flip specifically. (Loads no longer clear an active celebration,
        // so a celebration from init would leak into the assertion below.)
        coEvery { repo.getCapturedDayIndices() } returns emptySet()

        val vm = buildViewModel()
        advanceUntilIdle()

        // fromSync = true should suppress celebration even when capturedToday flips
        coEvery { repo.getCapturedDayIndices() } returns setOf(todayIndex)
        vm.load(fromSync = true)
        advanceUntilIdle()

        assertNull(
            "fromSync load must never trigger celebration",
            vm.state.value.celebrationState
        )
    }

    @Test
    fun celebration_alreadyCelebratedToday_doesNotFireAgain() = runTest {
        val todayIndex = com.colorwalk.app.domain.StreakCalculator
            .epochMillisToDayIndex(System.currentTimeMillis())
        // Simulate "last_celebration_day" already == todayIndex
        every { sharedPrefs.getInt("last_celebration_day", -1) } returns todayIndex
        coEvery { repo.getStreak() } returns 1
        coEvery { repo.getCapturedDayIndices() } returns setOf(todayIndex)

        val vm = buildViewModel()
        advanceUntilIdle()

        assertNull(
            "Celebration must not fire again on the same day",
            vm.state.value.celebrationState
        )
    }

    @Test
    fun celebration_dbEmissionAfterCapture_triggersCelebration() = runTest {
        // The swipe hub keeps Home composed, so no navigation/lifecycle event fires
        // after a capture — the Room emission itself must trigger the celebration.
        val todayIndex = com.colorwalk.app.domain.StreakCalculator
            .epochMillisToDayIndex(System.currentTimeMillis())
        val photosFlow = kotlinx.coroutines.flow.MutableStateFlow(
            emptyList<com.colorwalk.app.data.db.PhotoEntity>()
        )
        every { repo.getAllPhotos() } returns photosFlow
        coEvery { repo.getStreak() } returns 1

        val vm = buildViewModel()
        advanceUntilIdle() // init load + sync complete, nothing captured yet

        // Capture lands: DB now reports today's index and Room re-emits the list.
        coEvery { repo.getCapturedDayIndices() } returns setOf(todayIndex)
        photosFlow.value = listOf(mockk(relaxed = true))
        advanceUntilIdle()

        assertTrue(
            "DB emission after a capture must trigger the daily celebration",
            vm.state.value.celebrationState is CelebrationState.Daily
        )

        // A follow-up emission (async location resolution / note save) must NOT
        // clear the celebration mid-confetti.
        photosFlow.value = listOf(mockk(relaxed = true), mockk(relaxed = true))
        advanceUntilIdle()

        assertTrue(
            "Follow-up DB emissions must not cut an active celebration short",
            vm.state.value.celebrationState is CelebrationState.Daily
        )
    }

    // ── syncGalleryWithDatabase exception handling ────────────────────────────

    @Test
    fun syncGalleryWithDatabase_exceptionDoesNotCrashViewModel() = runTest {
        coEvery { repo.syncGalleryWithDatabase() } throws RuntimeException("Disk full")

        // Should not throw; ViewModel catches and logs
        val vm = buildViewModel()
        advanceUntilIdle()

        // After the exception the ViewModel should still have a valid state
        assertNotNull(vm.state.value.colorOfDay)
    }

    // ── shouldShowReview ──────────────────────────────────────────────────────

    @Test
    fun shouldShowReview_isTrueWhenStreakAtLeastSevenAndReviewNotYetShown() = runTest {
        every { sharedPrefs.getBoolean("review_shown", false) } returns false
        coEvery { repo.getStreak() } returns 7

        val vm = buildViewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.shouldShowReview)
    }

    @Test
    fun shouldShowReview_isFalseWhenReviewAlreadyShown() = runTest {
        every { sharedPrefs.getBoolean("review_shown", false) } returns true
        coEvery { repo.getStreak() } returns 10

        val vm = buildViewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.shouldShowReview)
    }

    @Test
    fun shouldShowReview_isFalseWhenStreakBelowSeven() = runTest {
        every { sharedPrefs.getBoolean("review_shown", false) } returns false
        coEvery { repo.getStreak() } returns 5

        val vm = buildViewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.shouldShowReview)
    }
}
