package com.colorwalk.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorwalk.app.data.db.ColorSummary
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GalleryViewMode { COLOR, DATE }

data class PhotoViewerState(
    val photos: List<PhotoEntity>,
    val initialIndex: Int
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repo: PhotoRepository
) : ViewModel() {

    val colorFolders: StateFlow<List<ColorSummary>> = repo.getDistinctColors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPhotos: StateFlow<List<PhotoEntity>> = repo.getAllPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _viewMode = MutableStateFlow(GalleryViewMode.COLOR)
    val viewMode: StateFlow<GalleryViewMode> = _viewMode

    private val _selectedColor = MutableStateFlow<String?>(null)
    val selectedColor: StateFlow<String?> = _selectedColor

    val photosForColor: StateFlow<List<PhotoEntity>> = _selectedColor
        .flatMapLatest { colorName ->
            if (colorName == null) flowOf(emptyList())
            else repo.getPhotosByColor(colorName)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _viewerState = MutableStateFlow<PhotoViewerState?>(null)
    val viewerState: StateFlow<PhotoViewerState?> = _viewerState

    fun setViewMode(mode: GalleryViewMode) { _viewMode.value = mode }
    fun selectColor(colorName: String) { _selectedColor.value = colorName }
    fun clearSelection() { _selectedColor.value = null }

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
            // Remove from viewer list if open
            val vs = _viewerState.value ?: return@launch
            val newList = vs.photos.filter { it.id != photo.id }
            _viewerState.value = if (newList.isEmpty()) null
            else PhotoViewerState(newList, vs.initialIndex.coerceAtMost(newList.lastIndex))
        }
    }
}
