package com.colorwalk.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorwalk.app.data.db.ColorSummary
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

enum class GalleryViewMode { COLOR, DATE }

enum class DateFilter(val label: String) {
    ALL("All"),
    THIS_MONTH("This Month"),
    LAST_3_MONTHS("Last 3 Months")
}

enum class AlbumSortOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest")
}

data class PhotoViewerState(
    val photos: List<PhotoEntity>,
    val initialIndex: Int
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repo: PhotoRepository
) : ViewModel() {

    private val rawColorFolders = repo.getDistinctColors()
    private val rawAllPhotos    = repo.getAllPhotos()

    private val _searchQuery   = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _dateFilter    = MutableStateFlow(DateFilter.ALL)
    val dateFilter: StateFlow<DateFilter> = _dateFilter

    private val _albumSortOrder = MutableStateFlow(AlbumSortOrder.NEWEST)
    val albumSortOrder: StateFlow<AlbumSortOrder> = _albumSortOrder

    val colorFolders: StateFlow<List<ColorSummary>> =
        combine(rawColorFolders, _searchQuery) { folders, query ->
            if (query.isBlank()) folders
            else folders.filter { it.colorName.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPhotos: StateFlow<List<PhotoEntity>> =
        combine(rawAllPhotos, _dateFilter) { photos, filter ->
            when (filter) {
                DateFilter.ALL -> photos
                DateFilter.THIS_MONTH -> {
                    val cutoff = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    photos.filter { it.dateTaken >= cutoff }
                }
                DateFilter.LAST_3_MONTHS -> {
                    val cutoff = Calendar.getInstance().apply {
                        add(Calendar.MONTH, -3)
                    }.timeInMillis
                    photos.filter { it.dateTaken >= cutoff }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _viewMode = MutableStateFlow(GalleryViewMode.COLOR)
    val viewMode: StateFlow<GalleryViewMode> = _viewMode

    private val _selectedColor = MutableStateFlow<String?>(null)
    val selectedColor: StateFlow<String?> = _selectedColor

    private val rawPhotosForColor: Flow<List<PhotoEntity>> = _selectedColor
        .flatMapLatest { colorName ->
            if (colorName == null) flowOf(emptyList())
            else repo.getPhotosByColor(colorName)
        }

    val photosForColor: StateFlow<List<PhotoEntity>> =
        combine(rawPhotosForColor, _albumSortOrder) { photos, order ->
            when (order) {
                AlbumSortOrder.NEWEST -> photos.sortedByDescending { it.dateTaken }
                AlbumSortOrder.OLDEST -> photos.sortedBy { it.dateTaken }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _viewerState = MutableStateFlow<PhotoViewerState?>(null)
    val viewerState: StateFlow<PhotoViewerState?> = _viewerState

    fun setViewMode(mode: GalleryViewMode)         { _viewMode.value = mode }
    fun selectColor(colorName: String)             { _selectedColor.value = colorName }
    fun clearSelection()                           { _selectedColor.value = null }
    fun setSearchQuery(query: String)              { _searchQuery.value = query }
    fun setDateFilter(filter: DateFilter)          { _dateFilter.value = filter }
    fun setAlbumSortOrder(order: AlbumSortOrder)   { _albumSortOrder.value = order }

    fun openPhoto(photo: PhotoEntity, photos: List<PhotoEntity>) {
        val idx = photos.indexOfFirst { it.id == photo.id }
        if (idx >= 0) _viewerState.value = PhotoViewerState(photos, idx)
    }

    fun closePhoto() { _viewerState.value = null }

    fun rotatePhoto(photo: PhotoEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.rotatePhoto(photo)
            onDone()
        }
    }

    fun deletePhoto(photo: PhotoEntity) {
        viewModelScope.launch {
            repo.deletePhoto(photo)
            val vs = _viewerState.value ?: return@launch
            val newList = vs.photos.filter { it.id != photo.id }
            _viewerState.value = if (newList.isEmpty()) null
            else PhotoViewerState(newList, vs.initialIndex.coerceAtMost(newList.lastIndex))
        }
    }
}
