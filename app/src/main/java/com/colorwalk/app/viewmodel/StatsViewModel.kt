package com.colorwalk.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.domain.StreakCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val totalPhotos: Int = 0,
    val totalActiveDays: Int = 0,
    val favouriteColorName: String? = null,
    val favouriteColorHex: String? = null,
    // One photo per day index (most recent if multiple exist on the same day)
    val photosByDayIndex: Map<Int, PhotoEntity> = emptyMap(),
    val selectedPhoto: PhotoEntity? = null
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repo: PhotoRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            val allPhotos = repo.getAllPhotosSnapshot()
            val timestamps = allPhotos.map { it.dateTaken }
            val currentStreak = repo.getStreak()
            val bestStreak = computeBestStreak(timestamps)
            val totalActiveDays = timestamps
                .map { StreakCalculator.epochMillisToDayIndex(it) }
                .distinct().size
            val favEntry = allPhotos
                .groupBy { it.colorName }
                .maxByOrNull { it.value.size }
            // Sort descending so .first() per day is the most recent photo
            val photosByDayIndex = allPhotos
                .sortedByDescending { it.dateTaken }
                .groupBy { StreakCalculator.epochMillisToDayIndex(it.dateTaken) }
                .mapValues { it.value.first() }

            _state.value = StatsUiState(
                currentStreak = currentStreak,
                bestStreak = bestStreak,
                totalPhotos = allPhotos.size,
                totalActiveDays = totalActiveDays,
                favouriteColorName = favEntry?.key,
                favouriteColorHex = favEntry?.value?.firstOrNull()?.colorHex,
                photosByDayIndex = photosByDayIndex
            )
        }
    }

    fun selectPhoto(photo: PhotoEntity?) {
        _state.value = _state.value.copy(selectedPhoto = photo)
    }

    private fun computeBestStreak(timestamps: List<Long>): Int {
        if (timestamps.isEmpty()) return 0
        val days = timestamps
            .map { StreakCalculator.epochMillisToDayIndex(it) }
            .toSortedSet().toList()
        var best = 1; var current = 1
        for (i in 1 until days.size) {
            current = if (days[i] - days[i - 1] == 1) current + 1 else 1
            if (current > best) best = current
        }
        return best
    }
}
