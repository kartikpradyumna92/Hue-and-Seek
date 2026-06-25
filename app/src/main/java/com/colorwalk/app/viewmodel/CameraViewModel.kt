package com.colorwalk.app.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colorwalk.app.data.repository.ImportResult
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.data.repository.SaveResult
import com.colorwalk.app.domain.WalkColor
import com.colorwalk.app.domain.colorForDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.flow.asStateFlow

sealed class CaptureState {
    object Idle : CaptureState()
    object Processing : CaptureState()
    // Shown after a successful save — user can add a note or skip straight to Home.
    data class AwaitingNote(val dominantHex: String, val photoId: Long) : CaptureState()
    data class Failed(
        val matchPercent: Float,
        val targetColorName: String,
        val actualDominant: String,
        val nearestColorName: String? = null,  // highest non-target color found (for hint)
        val nearestColorShare: Float = 0f
    ) : CaptureState()
    object StorageError : CaptureState()
    // Import-specific failures
    object ImportNoDate : CaptureState()
    data class ImportWrongDay(val dateTaken: Long) : CaptureState()
    object ImportDuplicate : CaptureState()
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val repo: PhotoRepository
) : ViewModel() {

    private val _targetColor = MutableStateFlow(colorForDay(System.currentTimeMillis()))
    val targetColor: StateFlow<WalkColor> = _targetColor.asStateFlow()

    fun refreshTargetColor() {
        _targetColor.value = colorForDay(System.currentTimeMillis())
    }

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    fun startCapture() {
        _captureState.value = CaptureState.Processing
    }

    fun onCaptureError() {
        _captureState.value = CaptureState.StorageError
    }

    fun onPhotoCaptured(bitmap: Bitmap) {
        _captureState.value = CaptureState.Processing
        val color = _targetColor.value
        viewModelScope.launch {
            _captureState.value = when (val result = repo.savePhoto(bitmap, color)) {
                is SaveResult.Success          -> CaptureState.AwaitingNote(result.validation.dominantHex, result.photoId)
                is SaveResult.ValidationFailed -> CaptureState.Failed(
                    result.validation.matchPercent, color.name,
                    result.validation.actualDominantColor,
                    result.validation.nearestColorName,
                    result.validation.nearestColorShare
                )
                SaveResult.StorageError        -> CaptureState.StorageError
            }
        }
    }

    fun onPhotoImported(uri: Uri) {
        _captureState.value = CaptureState.Processing
        val color = _targetColor.value
        viewModelScope.launch {
            _captureState.value = when (val result = repo.importPhoto(uri, color)) {
                is ImportResult.Success          -> CaptureState.AwaitingNote(result.validation.dominantHex, result.photoId)
                is ImportResult.ValidationFailed -> CaptureState.Failed(
                    result.validation.matchPercent, color.name,
                    result.validation.actualDominantColor,
                    result.validation.nearestColorName,
                    result.validation.nearestColorShare
                )
                ImportResult.NoDateMetadata      -> CaptureState.ImportNoDate
                is ImportResult.NotTakenToday    -> CaptureState.ImportWrongDay(result.dateTaken)
                ImportResult.AlreadyImported     -> CaptureState.ImportDuplicate
                ImportResult.StorageError        -> CaptureState.StorageError
            }
        }
    }

    fun saveNoteForPhoto(photoId: Long, note: String, onDone: () -> Unit) {
        viewModelScope.launch {
            // Reuse the same EXIF + MediaStore write path as the newsfeed editor.
            val photo = repo.getAllPhotosSnapshot().firstOrNull { it.id == photoId }
            if (photo != null) repo.saveDescription(photo, note)
            _captureState.value = CaptureState.Idle
            onDone()
        }
    }

    fun resetState() { _captureState.value = CaptureState.Idle }
}
