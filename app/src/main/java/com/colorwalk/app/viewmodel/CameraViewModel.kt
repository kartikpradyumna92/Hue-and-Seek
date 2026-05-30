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

sealed class CaptureState {
    object Idle : CaptureState()
    object Processing : CaptureState()
    data class Success(val dominantHex: String) : CaptureState()
    data class Failed(val matchPercent: Float, val targetColorName: String) : CaptureState()
    object StorageError : CaptureState()
    // Import-specific failures
    object ImportNoDate : CaptureState()
    data class ImportWrongDay(val dateTaken: Long) : CaptureState()
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val repo: PhotoRepository
) : ViewModel() {

    val targetColor: WalkColor = colorForDay(System.currentTimeMillis())

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    fun onPhotoCaptured(bitmap: Bitmap) {
        _captureState.value = CaptureState.Processing
        viewModelScope.launch {
            _captureState.value = when (val result = repo.savePhoto(bitmap, targetColor)) {
                is SaveResult.Success         -> CaptureState.Success(result.validation.dominantHex)
                is SaveResult.ValidationFailed -> CaptureState.Failed(result.validation.matchPercent, targetColor.name)
                SaveResult.StorageError       -> CaptureState.StorageError
            }
        }
    }

    fun onPhotoImported(uri: Uri) {
        _captureState.value = CaptureState.Processing
        viewModelScope.launch {
            _captureState.value = when (val result = repo.importPhoto(uri, targetColor)) {
                is ImportResult.Success          -> CaptureState.Success(result.validation.dominantHex)
                is ImportResult.ValidationFailed -> CaptureState.Failed(result.validation.matchPercent, targetColor.name)
                ImportResult.NoDateMetadata      -> CaptureState.ImportNoDate
                is ImportResult.NotTakenToday    -> CaptureState.ImportWrongDay(result.dateTaken)
                ImportResult.StorageError        -> CaptureState.StorageError
            }
        }
    }

    fun resetState() { _captureState.value = CaptureState.Idle }
}
