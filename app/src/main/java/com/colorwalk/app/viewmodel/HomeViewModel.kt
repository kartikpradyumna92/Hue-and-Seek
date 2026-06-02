package com.colorwalk.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.WalkColor
import com.colorwalk.app.domain.colorForDay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val colorOfDay: WalkColor? = null,
    val streak: Int = 0,
    val capturedToday: Boolean = false,
    val capturedDayIndices: Set<Int> = emptySet(),
    val shouldShowReview: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: PhotoRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var loadJob: Job? = null

    init {
        // Show whatever is already in the DB immediately (fast path).
        load()
        // Then rebuild any records wiped by a reinstall in the background and refresh.
        viewModelScope.launch {
            repo.syncGalleryWithDatabase()
            load()
        }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val color = colorForDay(System.currentTimeMillis())
            val streak = repo.getStreak()
            val capturedDayIndices = repo.getCapturedDayIndices()
            // Derive capturedToday from the already-fetched set — saves a redundant DB query
            val todayIndex = StreakCalculator.epochMillisToDayIndex(System.currentTimeMillis())
            val capturedToday = todayIndex in capturedDayIndices
            val reviewNotYetShown = !context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getBoolean("review_shown", false)
            _state.value = HomeUiState(
                colorOfDay = color,
                streak = streak,
                capturedToday = capturedToday,
                capturedDayIndices = capturedDayIndices,
                shouldShowReview = streak >= 7 && reviewNotYetShown
            )
        }
    }

    fun onReviewShown() {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("review_shown", true).apply()
        // Clear the flag from state without re-running the full load
        _state.value = _state.value.copy(shouldShowReview = false)
    }
}
