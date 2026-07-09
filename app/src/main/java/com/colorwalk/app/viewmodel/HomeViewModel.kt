package com.colorwalk.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.WalkColor
import com.colorwalk.app.domain.colorForDay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CelebrationState {
    object Daily : CelebrationState()
    data class Milestone(val days: Int) : CelebrationState()
}

data class HomeUiState(
    val colorOfDay: WalkColor? = null,
    val streak: Int = 0,
    val capturedToday: Boolean = false,
    val capturedDayIndices: Set<Int> = emptySet(),
    val shouldShowReview: Boolean = false,
    val celebrationState: CelebrationState? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: PhotoRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    // Three most recent photos — drives the newsfeed peek strip on Home.
    val recentPhotos: StateFlow<List<PhotoEntity>> = repo.getAllPhotos()
        .map { it.take(3) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var loadJob: Job? = null

    // True once startup gallery sync has finished. DB emissions before this point are
    // startup/recovery churn, not a user capture, and must not celebrate.
    @Volatile
    private var syncCompleted = false

    init {
        load()
        viewModelScope.launch {
            try {
                repo.syncGalleryWithDatabase()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Gallery sync failed", e)
            }
            load(fromSync = true)
            syncCompleted = true
        }
        // The swipe hub keeps Home permanently composed, so returning from Camera
        // triggers no navigation or lifecycle event — the DB is the only reliable
        // signal that a photo was captured. Room re-emits on EVERY row update though
        // (note saves, geocode backfill), and load() re-reads all photo dates — so
        // only react when the photo set itself changes: the count (capture/delete)
        // or the newest timestamp (capture). The list is ordered dateTaken DESC, so
        // first() is the max. Skip the initial emission (startup state, already
        // covered by load() above). (M-2)
        viewModelScope.launch {
            repo.getAllPhotos()
                .map { photos -> photos.size to (photos.firstOrNull()?.dateTaken ?: 0L) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    load(fromSync = !syncCompleted)
                }
        }
    }

    fun load(fromSync: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val prevCaptured = _state.value.capturedToday
            val color = colorForDay(System.currentTimeMillis())
            val streak = repo.getStreak()
            val capturedDayIndices = repo.getCapturedDayIndices()
            val todayIndex = StreakCalculator.epochMillisToDayIndex(System.currentTimeMillis())
            val capturedToday = todayIndex in capturedDayIndices

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val reviewNotYetShown = !prefs.getBoolean("review_shown", false)
            val lastCelebDay = prefs.getInt("last_celebration_day", -1)

            // Celebrate when capturedToday transitions false→true, once per calendar day.
            // fromSync loads never trigger confetti (reinstall recovery shouldn't celebrate).
            val celebration = when {
                !fromSync && capturedToday && !prevCaptured && lastCelebDay != todayIndex ->
                    if (streak in MILESTONE_STREAKS) CelebrationState.Milestone(streak)
                    else CelebrationState.Daily
                else -> null
            }

            _state.value = HomeUiState(
                colorOfDay = color,
                streak = streak,
                capturedToday = capturedToday,
                capturedDayIndices = capturedDayIndices,
                shouldShowReview = streak >= 7 && reviewNotYetShown,
                // Never null out a celebration that's still playing: follow-up DB
                // emissions (async location resolution, note save) re-run load()
                // moments after the capture and must not cut the confetti short.
                celebrationState = celebration ?: _state.value.celebrationState
            )
        }
    }

    fun onCelebrationDone() {
        val todayIndex = StreakCalculator.epochMillisToDayIndex(System.currentTimeMillis())
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putInt("last_celebration_day", todayIndex).apply()
        _state.value = _state.value.copy(celebrationState = null)
    }

    fun onReviewShown() {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("review_shown", true).apply()
        _state.value = _state.value.copy(shouldShowReview = false)
    }

    companion object {
        val MILESTONE_STREAKS = setOf(7, 21, 30, 50, 100, 150, 180, 200, 240, 300, 365)
    }
}
