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
import java.util.Locale
import javax.inject.Inject

enum class GalleryViewMode { COLOR, DATE, PLACE }

enum class DateFilter(val label: String) {
    ALL("All"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    LAST_3_MONTHS("Last 3 Months")
}

enum class AlbumSortOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest")
}

data class PlaceSummary(
    val locationName: String,
    val photoCount: Int,
    val thumbnailPath: String,
    val colorHex: String
)

/** Color folder enriched with count + most-recent thumbnail for the gallery grid. */
data class ColorFolderInfo(
    val colorName: String,
    val colorHex: String,
    val photoCount: Int,
    val thumbnailPath: String
)

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

    private val _searchQuery    = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _dateFilter     = MutableStateFlow(DateFilter.ALL)
    val dateFilter: StateFlow<DateFilter> = _dateFilter

    private val _dateSortOrder  = MutableStateFlow(AlbumSortOrder.NEWEST)
    val dateSortOrder: StateFlow<AlbumSortOrder> = _dateSortOrder

    private val _albumSortOrder = MutableStateFlow(AlbumSortOrder.NEWEST)
    val albumSortOrder: StateFlow<AlbumSortOrder> = _albumSortOrder

    val colorFolders: StateFlow<List<ColorSummary>> =
        combine(rawColorFolders, _searchQuery) { folders, query ->
            if (query.isBlank()) folders
            else folders.filter { it.colorName.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Presentation-derived folder cards (count + newest photo as thumbnail),
    // sorted by photo count so the user's strongest colors lead the grid.
    val colorFolderCards: StateFlow<List<ColorFolderInfo>> =
        combine(rawAllPhotos, _searchQuery) { photos, query ->
            photos
                .groupBy { it.colorName }
                .map { (name, group) ->
                    val newest = group.maxByOrNull { it.dateTaken }!!
                    ColorFolderInfo(name, newest.colorHex, group.size, newest.filePath)
                }
                .filter { query.isBlank() || it.colorName.contains(query, ignoreCase = true) }
                .sortedByDescending { it.photoCount }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPhotos: StateFlow<List<PhotoEntity>> =
        combine(rawAllPhotos, _dateFilter, _dateSortOrder) { photos, filter, sort ->
            val filtered = when (filter) {
                DateFilter.ALL -> photos
                DateFilter.THIS_WEEK -> {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    photos.filter { it.dateTaken >= cal.timeInMillis }
                }
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
                    val cutoff = Calendar.getInstance().apply { add(Calendar.MONTH, -3) }.timeInMillis
                    photos.filter { it.dateTaken >= cutoff }
                }
            }
            when (sort) {
                AlbumSortOrder.NEWEST -> filtered.sortedByDescending { it.dateTaken }
                AlbumSortOrder.OLDEST -> filtered.sortedBy { it.dateTaken }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val photosByPlace: StateFlow<List<PlaceSummary>> = rawAllPhotos
        .map { photos ->
            photos
                .mapNotNull { photo ->
                    val label = photo.locationName ?: coordinateLabel(photo.latitude, photo.longitude)
                    if (label != null) photo to label else null
                }
                .groupBy { (_, label) -> label }
                .map { (label, group) ->
                    val sorted = group.map { it.first }.sortedByDescending { it.dateTaken }
                    PlaceSummary(label, sorted.size, sorted.first().filePath, sorted.first().colorHex)
                }
                .sortedByDescending { it.photoCount }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun PhotoEntity.isUntagged(): Boolean {
        if (locationName != null) return false
        val lat = latitude; val lon = longitude
        return (lat == null && lon == null) ||
               (lat != null && lon != null && Math.abs(lat) < 0.001 && Math.abs(lon) < 0.001)
    }

    val untaggedPhotos: StateFlow<List<PhotoEntity>> = rawAllPhotos
        .map { photos -> photos.filter { it.isUntagged() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasUntaggedPhotos: StateFlow<Boolean> = untaggedPhotos
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val existingPlaceNames: StateFlow<List<String>> = photosByPlace
        .map { places -> places.map { it.locationName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showingUntagged  = MutableStateFlow(false)
    val showingUntagged: StateFlow<Boolean> = _showingUntagged

    private val _photoBeingTagged = MutableStateFlow<PhotoEntity?>(null)
    val photoBeingTagged: StateFlow<PhotoEntity?> = _photoBeingTagged

    private val _viewMode = MutableStateFlow(GalleryViewMode.COLOR)
    val viewMode: StateFlow<GalleryViewMode> = _viewMode

    private val _selectedColor = MutableStateFlow<String?>(null)
    val selectedColor: StateFlow<String?> = _selectedColor

    private val _selectedPlace = MutableStateFlow<String?>(null)
    val selectedPlace: StateFlow<String?> = _selectedPlace

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

    val photosForPlace: StateFlow<List<PhotoEntity>> =
        combine(rawAllPhotos, _selectedPlace, _albumSortOrder) { photos, place, order ->
            if (place == null) emptyList()
            else {
                val filtered = photos.filter { photo ->
                    photo.locationName == place ||
                    (photo.locationName == null && coordinateLabel(photo.latitude, photo.longitude) == place)
                }
                when (order) {
                    AlbumSortOrder.NEWEST -> filtered.sortedByDescending { it.dateTaken }
                    AlbumSortOrder.OLDEST -> filtered.sortedBy { it.dateTaken }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _viewerState = MutableStateFlow<PhotoViewerState?>(null)
    val viewerState: StateFlow<PhotoViewerState?> = _viewerState

    init {
        viewModelScope.launch { repo.backfillLocationData() }
    }

    fun setViewMode(mode: GalleryViewMode)         { _viewMode.value = mode }
    fun selectColor(colorName: String)             { _selectedColor.value = colorName }
    fun clearSelection()                           { _selectedColor.value = null }
    fun selectPlace(name: String)                  { _selectedPlace.value = name }
    fun clearPlaceSelection()                      { _selectedPlace.value = null }
    fun setSearchQuery(query: String)              { _searchQuery.value = query }
    fun setDateFilter(filter: DateFilter)          { _dateFilter.value = filter }
    fun setDateSortOrder(order: AlbumSortOrder)    { _dateSortOrder.value = order }
    fun setAlbumSortOrder(order: AlbumSortOrder)   { _albumSortOrder.value = order }
    fun openUntagged()                             { _showingUntagged.value = true }
    fun closeUntagged()                            { _showingUntagged.value = false }
    fun startTagging(photo: PhotoEntity)           { _photoBeingTagged.value = photo }
    fun cancelTagging()                            { _photoBeingTagged.value = null }
    fun submitTag(photo: PhotoEntity, locationName: String) {
        if (locationName.isBlank()) return
        viewModelScope.launch {
            repo.tagPhotoLocation(photo.id, locationName.trim())
            _photoBeingTagged.value = null
        }
    }

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

    // Bucket to ~11 km grid. Floor on signed value so bucket boundaries are symmetric
    // around the equator/prime meridian. Locale.US prevents comma decimal separators
    // (e.g. German "33,8") in the label which is used as a Map key.
    private fun coordinateLabel(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        if (Math.abs(lat) < 0.001 && Math.abs(lon) < 0.001) return null
        val latR = Math.floor(lat * 10) / 10.0
        val lonR = Math.floor(lon * 10) / 10.0
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return "Near %.1f°%s, %.1f°%s".format(Locale.US, Math.abs(latR), latDir, Math.abs(lonR), lonDir)
    }
}
