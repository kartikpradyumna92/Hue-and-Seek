package com.colorwalk.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.colorwalk.app.data.repository.ImportResult
import com.colorwalk.app.data.repository.NormalizedCrop
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.data.repository.SaveResult
import com.colorwalk.app.domain.WalkColor
import com.colorwalk.app.domain.colorForDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    // BUG-049: specific, actionable failures instead of one "Something went wrong".
    /** The camera itself failed to take the picture. */
    object CaptureFailed : CaptureState()
    /** Not enough free space to save. */
    object StorageFull : CaptureState()
    // Import-specific failures
    object ImportNoDate : CaptureState()
    data class ImportWrongDay(val dateTaken: Long) : CaptureState()
    object ImportDuplicate : CaptureState()
    object ImportTampered : CaptureState()
    /** The picked file couldn't be opened as an image. */
    object ImportUnreadable : CaptureState()
}

internal const val CAPTURE_CALLBACK_TIMEOUT_MS = 15_000L

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

    // Guards the camera-callback phase only (tap -> onCaptureSuccess/onError). If
    // CameraX never calls back, the shutter must not stay replaced by a spinner.
    // Volatile: capture callbacks arrive on a background executor.
    @Volatile private var captureWatchdog: Job? = null

    // BUG-043: lens and zoom choices outlive the Camera pane's composition — a small
    // sideways nudge that tears the camera down used to reset both.
    var useFrontCamera by mutableStateOf(false)
    var requestedZoom by mutableFloatStateOf(1f)

    // BUG-047: when the shutter was pressed. The day's color AND the saved day both
    // come from this one instant, so a press at 23:59:59 can't be validated against
    // one day's color and saved on the next.
    @Volatile private var shutterAt: Long? = null

    fun startCapture() {
        shutterAt = System.currentTimeMillis()
        _captureState.value = CaptureState.Processing
        captureWatchdog?.cancel()
        captureWatchdog = viewModelScope.launch {
            delay(CAPTURE_CALLBACK_TIMEOUT_MS)
            if (_captureState.value is CaptureState.Processing) {
                _captureState.value = CaptureState.CaptureFailed
            }
        }
    }

    fun onCaptureError() {
        captureWatchdog?.cancel()
        _captureState.value = CaptureState.CaptureFailed
    }

    /** The capture was aborted because the camera closed (user left the pane). */
    fun onCaptureAborted() {
        captureWatchdog?.cancel()
        if (_captureState.value is CaptureState.Processing) {
            _captureState.value = CaptureState.Idle
        }
    }

    /**
     * [jpegBytes] is the camera HAL's original JPEG — saved verbatim so EXIF survives
     * (L-2). [mirror] is true for front-camera captures: the selfie is saved AS
     * PREVIEWED via an EXIF orientation flip (L-3).
     */
    fun onPhotoCaptured(jpegBytes: ByteArray, mirror: Boolean = false, crop: NormalizedCrop? = null) {
        captureWatchdog?.cancel()
        _captureState.value = CaptureState.Processing
        val at = shutterAt ?: System.currentTimeMillis()
        shutterAt = null
        val color = colorForDay(at)
        viewModelScope.launch {
            _captureState.value = guarded {
                when (val result = repo.savePhoto(jpegBytes, color, mirror, at, crop)) {
                    is SaveResult.Success          -> CaptureState.AwaitingNote(result.validation.dominantHex, result.photoId)
                    is SaveResult.ValidationFailed -> CaptureState.Failed(
                        result.validation.matchPercent, color.name,
                        result.validation.actualDominantColor,
                        result.validation.nearestColorName,
                        result.validation.nearestColorShare
                    )
                    SaveResult.StorageError        -> CaptureState.StorageError
                    SaveResult.StorageFull         -> CaptureState.StorageFull
                }
            }
        }
    }

    fun onPhotoImported(uri: Uri) {
        captureWatchdog?.cancel()
        _captureState.value = CaptureState.Processing
        val color = _targetColor.value
        viewModelScope.launch {
            _captureState.value = guarded {
                when (val result = repo.importPhoto(uri, color)) {
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
                    ImportResult.MetadataTampered    -> CaptureState.ImportTampered
                    ImportResult.StorageError        -> CaptureState.StorageError
                    ImportResult.StorageFull         -> CaptureState.StorageFull
                    ImportResult.Unreadable          -> CaptureState.ImportUnreadable
                }
            }
        }
    }

    /**
     * BUG-021: an unexpected exception in the save/import pipeline (a provider
     * rejecting a query, a full database) used to crash the app from viewModelScope.
     * It becomes the error card instead; cancellation still propagates.
     */
    private suspend fun guarded(block: suspend () -> CaptureState): CaptureState = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("CameraViewModel", "Save/import pipeline failed", e)
        CaptureState.StorageError
    }

    fun saveNoteForPhoto(photoId: Long, note: String, onDone: () -> Unit) {
        noteDraft = ""
        viewModelScope.launch {
            // Reuse the same EXIF + MediaStore write path as the newsfeed editor.
            val photo = repo.getPhotoById(photoId)
            if (photo != null) repo.saveDescription(photo, note)
            _captureState.value = CaptureState.Idle
            onDone()
        }
    }

    fun resetState() {
        noteDraft = ""
        _captureState.value = CaptureState.Idle
    }

    // Live text of the note prompt, so walking away can keep it (BUG-048).
    private var noteDraft = ""

    fun onNoteDraftChanged(text: String) { noteDraft = text }

    /**
     * Clears a note prompt the user walked away from (swiped off the Camera pane
     * with the prompt open). The photo is already saved; a note they had started
     * typing is saved too rather than silently discarded (BUG-048). ONLY
     * AwaitingNote is cleared — any other state (Processing, Failed, error cards) is
     * left untouched: Processing must survive the pane leaving composition, since
     * capture results land while the hub is mid-slide back to Home.
     */
    fun dismissNotePromptIfPending() {
        val awaiting = _captureState.value as? CaptureState.AwaitingNote ?: return
        val draft = noteDraft.trim()
        noteDraft = ""
        _captureState.value = CaptureState.Idle
        if (draft.isNotEmpty()) {
            viewModelScope.launch {
                repo.getPhotoById(awaiting.photoId)?.let { repo.saveDescription(it, draft) }
            }
        }
    }
}
