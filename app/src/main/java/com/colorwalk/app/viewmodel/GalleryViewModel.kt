package com.colorwalk.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.annotation.StringRes
import com.colorwalk.app.R

enum class GalleryViewMode { COLOR, DATE, PLACE }

enum class DateFilter(@StringRes val label: Int) {
    ALL(R.string.filter_all),
    THIS_WEEK(R.string.filter_this_week),
    THIS_MONTH(R.string.filter_this_month),
    LAST_3_MONTHS(R.string.filter_last_3_months)
}

enum class AlbumSortOrder(@StringRes val label: Int) {
    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest)
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
class GalleryViewModel internal constructor(
    private val repo: PhotoRepository,
    private val savedState: SavedStateHandle,
    // Where grouping/sorting runs; tests pass their own dispatcher.
    private val compute: CoroutineDispatcher
) : ViewModel() {

    @Inject constructor(repo: PhotoRepository, savedState: SavedStateHandle) :
        this(repo, savedState, Dispatchers.Default)

    // BUG-041: ONE Room observer for the whole table, shared by every derived list —
    // five separate collectors used to re-run the full query on every row write.
    private val rawAllPhotos = repo.getAllPhotos()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    private val _searchQuery    = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _dateFilter     = MutableStateFlow(DateFilter.ALL)
    val dateFilter: StateFlow<DateFilter> = _dateFilter

    private val _dateSortOrder  = MutableStateFlow(AlbumSortOrder.NEWEST)
    val dateSortOrder: StateFlow<AlbumSortOrder> = _dateSortOrder

    private val _albumSortOrder = MutableStateFlow(AlbumSortOrder.NEWEST)
    val albumSortOrder: StateFlow<AlbumSortOrder> = _albumSortOrder

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
        }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // BUG-063: "now" for the relative date filters. The cutoffs were computed only when
    // the photo list re-emitted, so "This Week" kept last week's start after a week
    // boundary passed with the app open. GalleryScreen feeds this at each midnight.
    private val _now = MutableStateFlow(System.currentTimeMillis())

    fun onDayChanged(nowMillis: Long) { _now.value = nowMillis }

    val allPhotos: StateFlow<List<PhotoEntity>> =
        combine(rawAllPhotos, _dateFilter, _dateSortOrder, _now) { photos, filter, sort, now ->
            val filtered = when (filter) {
                DateFilter.ALL -> photos
                DateFilter.THIS_WEEK -> {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = now
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
                        timeInMillis = now
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    photos.filter { it.dateTaken >= cutoff }
                }
                DateFilter.LAST_3_MONTHS -> {
                    val cutoff = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -3) }.timeInMillis
                    photos.filter { it.dateTaken >= cutoff }
                }
            }
            when (sort) {
                AlbumSortOrder.NEWEST -> filtered.sortedByDescending { it.dateTaken }
                AlbumSortOrder.OLDEST -> filtered.sortedBy { it.dateTaken }
            }
        }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * By Date grid sections ("September 2026" → photos), grouped here off the main
     * thread (BUG-041) — the grid used to format every photo's date during composition.
     * Follows [allPhotos]' filter and sort order.
     */
    val photosByMonth: StateFlow<List<Pair<String, List<PhotoEntity>>>> = allPhotos
        .map { photos ->
            val fmt = SimpleDateFormat("LLLL yyyy", Locale.getDefault()) // standalone month form (BUG-040)
            // allPhotos is already sorted, so groupBy's insertion order is the display order.
            photos.groupBy { fmt.format(Date(it.dateTaken)) }.toList()
        }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun PhotoEntity.isUntagged(): Boolean {
        if (locationName != null) return false
        val lat = latitude; val lon = longitude
        return (lat == null && lon == null) ||
               (lat != null && lon != null && Math.abs(lat) < 0.001 && Math.abs(lon) < 0.001)
    }

    val untaggedPhotos: StateFlow<List<PhotoEntity>> = rawAllPhotos
        .map { photos -> photos.filter { it.isUntagged() } }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasUntaggedPhotos: StateFlow<Boolean> = untaggedPhotos
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val existingPlaceNames: StateFlow<List<String>> = photosByPlace
        .map { places -> places.map { it.locationName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // BUG-008: which tab / album / untagged list is open survives process death
    // (SavedStateHandle), so returning to the app lands where the user left off.
    // The full-screen viewer is intentionally NOT restored — its photo list is a
    // snapshot too large and too stale to persist.
    val showingUntagged: StateFlow<Boolean> = savedState.getStateFlow(KEY_UNTAGGED, false)

    private val _photoBeingTagged = MutableStateFlow<PhotoEntity?>(null)
    val photoBeingTagged: StateFlow<PhotoEntity?> = _photoBeingTagged

    val viewMode: StateFlow<GalleryViewMode> = savedState.getStateFlow(KEY_VIEW_MODE, GalleryViewMode.COLOR)

    val selectedColor: StateFlow<String?> = savedState.getStateFlow(KEY_COLOR, null)

    val selectedPlace: StateFlow<String?> = savedState.getStateFlow(KEY_PLACE, null)

    private val rawPhotosForColor: Flow<List<PhotoEntity>> = selectedColor
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
        }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val photosForPlace: StateFlow<List<PhotoEntity>> =
        combine(rawAllPhotos, selectedPlace, _albumSortOrder) { photos, place, order ->
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
        }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _viewerState = MutableStateFlow<PhotoViewerState?>(null)

    /**
     * BUG-063: the viewer's list is a snapshot taken when it opened (order and
     * membership), but each photo in it is the LIVE row — a place name landing, a
     * note, or resolveShareFile's content:// → private-path migration now reach the
     * open viewer (a later rotate used to act on the stale content:// path). A row
     * not (yet) in the table keeps its snapshot.
     */
    val viewerState: StateFlow<PhotoViewerState?> =
        combine(_viewerState, rawAllPhotos.onStart { emit(emptyList()) }) { vs, all ->
            if (vs == null) null
            else {
                val live = all.associateBy { it.id }
                vs.copy(photos = vs.photos.map { live[it.id] ?: it })
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { repo.backfillLocationData() }
    }

    fun setViewMode(mode: GalleryViewMode)         { savedState[KEY_VIEW_MODE] = mode }
    fun selectColor(colorName: String)             { savedState[KEY_COLOR] = colorName }
    fun clearSelection()                           { savedState[KEY_COLOR] = null }
    fun selectPlace(name: String)                  { savedState[KEY_PLACE] = name }
    fun clearPlaceSelection()                      { savedState[KEY_PLACE] = null }
    fun setSearchQuery(query: String)              { _searchQuery.value = query }
    fun setDateFilter(filter: DateFilter)          { _dateFilter.value = filter }
    fun setDateSortOrder(order: AlbumSortOrder)    { _dateSortOrder.value = order }
    fun setAlbumSortOrder(order: AlbumSortOrder)   { _albumSortOrder.value = order }
    fun openUntagged()                             { savedState[KEY_UNTAGGED] = true }
    fun closeUntagged()                            { savedState[KEY_UNTAGGED] = false }
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

    /**
     * The pager reports every page settle here (M-11): deletePhoto below and any
     * viewer recreation (config change wipes the pager's remembered page) must work
     * from where the user actually IS, not the photo the viewer was opened on.
     */
    fun onViewerPageChanged(index: Int) {
        val vs = _viewerState.value ?: return
        if (index != vs.initialIndex && index in vs.photos.indices) {
            _viewerState.value = vs.copy(initialIndex = index)
        }
    }

    fun closePhoto() { _viewerState.value = null }

    /**
     * Resolves a photo to a private, FileProvider-shareable file (M-12). Legacy
     * content:// rows are copied into private storage first — the app cannot grant
     * read permission on a MediaStore URI it doesn't own, so sharing those raw URIs
     * failed silently in the receiving app. [onReady] is invoked on the main thread
     * with null when the photo's bytes can't be obtained at all.
     */
    fun prepareShare(photo: PhotoEntity, onReady: (java.io.File?) -> Unit) {
        viewModelScope.launch {
            onReady(repo.prepareShareFile(photo))
        }
    }

    fun saveDescription(photo: PhotoEntity, text: String?) {
        viewModelScope.launch {
            repo.saveDescription(photo, text)
            // Patch the viewer snapshot immediately so the note appears without
            // waiting for Room to re-emit through the full allPhotos flow.
            val vs = _viewerState.value ?: return@launch
            val stored = text?.trim()?.ifBlank { null }
            _viewerState.value = vs.copy(
                photos = vs.photos.map { if (it.id == photo.id) it.copy(description = stored) else it }
            )
        }
    }

    /** [onDone] receives whether the rotation happened (BUG-059). */
    fun rotatePhoto(photo: PhotoEntity, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            onDone(repo.rotatePhoto(photo))
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

    private companion object {
        const val KEY_VIEW_MODE = "gallery_view_mode"
        const val KEY_COLOR     = "gallery_selected_color"
        const val KEY_PLACE     = "gallery_selected_place"
        const val KEY_UNTAGGED  = "gallery_showing_untagged"
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
