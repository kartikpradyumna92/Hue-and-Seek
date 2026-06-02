package com.colorwalk.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.domain.WalkColor
import com.colorwalk.app.domain.colorForDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val colorOfDay: WalkColor? = null,
    val streak: Int = 0,
    val capturedToday: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: PhotoRepository
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
            val capturedToday = repo.hasCapturedToday()
            _state.value = HomeUiState(color, streak, capturedToday)
        }
    }
}
