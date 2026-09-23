package com.colorwalk.app.viewmodel

import android.net.Uri
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.ImportResult
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.data.repository.SaveResult
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.domain.colorForDay
import com.colorwalk.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repo: PhotoRepository
    private val jpegBytes = byteArrayOf(1, 2, 3)

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
    }

    private fun buildViewModel() = CameraViewModel(repo)

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun successValidation(hex: String = "#FF0000") = ColorValidator.ValidationResult(
        passed = true,
        dominantHex = hex,
        dominantName = "Red",
        matchPercent = 0.85f,
        actualDominantColor = "Red"
    )

    private fun failedValidation() = ColorValidator.ValidationResult(
        passed = false,
        dominantHex = "#00FF00",
        dominantName = "Green",
        matchPercent = 0.20f,
        actualDominantColor = "Green"
    )

    // ── targetColor ──────────────────────────────────────────────────────────

    @Test
    fun targetColor_matchesColorForDayToday() {
        val vm = buildViewModel()
        val expected = colorForDay(System.currentTimeMillis())
        assertEquals(
            "targetColor must equal colorForDay(now)",
            expected,
            vm.targetColor.value
        )
    }

    @Test
    fun refreshTargetColor_valueRemainsEqualToColorForDayToday() {
        val vm = buildViewModel()
        vm.refreshTargetColor()
        assertEquals(
            "After refresh, targetColor must still equal colorForDay(now)",
            colorForDay(System.currentTimeMillis()),
            vm.targetColor.value
        )
    }

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    fun initialCaptureState_isIdle() {
        val vm = buildViewModel()
        assertTrue(vm.captureState.value is CaptureState.Idle)
    }

    // ── onPhotoCaptured ──────────────────────────────────────────────────────

    @Test
    fun onPhotoCaptured_immediatelyTransitionsToProcessing() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(10_000)
            SaveResult.StorageError
        }

        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)

        assertEquals(CaptureState.Processing, vm.captureState.value)
    }

    @Test
    fun onPhotoCaptured_withSuccessResult_transitionsToAwaitingNoteWithCorrectHexAndId() = runTest {
        val dominantHex = "#FF0000"
        val photoId = 42L
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.Success(uri, successValidation(dominantHex), photoId)

        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()

        val state = vm.captureState.value
        assertTrue("Expected AwaitingNote state, got $state", state is CaptureState.AwaitingNote)
        with(state as CaptureState.AwaitingNote) {
            assertEquals(dominantHex, this.dominantHex)
            assertEquals(photoId, this.photoId)
        }
    }

    @Test
    fun onPhotoCaptured_withValidationFailedResult_transitionsToFailedWithCorrectValues() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.ValidationFailed(failedValidation())

        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()

        val state = vm.captureState.value
        assertTrue("Expected Failed state, got $state", state is CaptureState.Failed)
        with(state as CaptureState.Failed) {
            assertEquals(0.20f, matchPercent, 0.001f)
            assertEquals(vm.targetColor.value.name, targetColorName)
            assertEquals("Green", actualDominant)
        }
    }

    @Test
    fun onPhotoCaptured_withStorageError_transitionsToStorageError() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.StorageError

        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()

        assertEquals(CaptureState.StorageError, vm.captureState.value)
    }

    // ── onPhotoImported ──────────────────────────────────────────────────────

    @Test
    fun onPhotoImported_withSuccessResult_transitionsToAwaitingNoteWithCorrectId() = runTest {
        val photoId = 99L
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repo.importPhoto(any(), any()) } returns ImportResult.Success(uri, successValidation(), photoId)

        val vm = buildViewModel()
        vm.onPhotoImported(mockk(relaxed = true))
        advanceUntilIdle()

        val state = vm.captureState.value
        assertTrue("Expected AwaitingNote after import success", state is CaptureState.AwaitingNote)
        assertEquals(photoId, (state as CaptureState.AwaitingNote).photoId)
    }

    @Test
    fun onPhotoImported_withAlreadyImported_transitionsToImportDuplicate() = runTest {
        coEvery { repo.importPhoto(any(), any()) } returns ImportResult.AlreadyImported

        val vm = buildViewModel()
        vm.onPhotoImported(mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(CaptureState.ImportDuplicate, vm.captureState.value)
    }

    @Test
    fun onPhotoImported_withNoDateMetadata_transitionsToImportNoDate() = runTest {
        coEvery { repo.importPhoto(any(), any()) } returns ImportResult.NoDateMetadata

        val vm = buildViewModel()
        vm.onPhotoImported(mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(CaptureState.ImportNoDate, vm.captureState.value)
    }

    @Test
    fun onPhotoImported_withWrongDay_transitionsToImportWrongDay() = runTest {
        val ts = 1_700_000_000_000L
        coEvery { repo.importPhoto(any(), any()) } returns ImportResult.NotTakenToday(ts)

        val vm = buildViewModel()
        vm.onPhotoImported(mockk(relaxed = true))
        advanceUntilIdle()

        val state = vm.captureState.value
        assertTrue(state is CaptureState.ImportWrongDay)
        assertEquals(ts, (state as CaptureState.ImportWrongDay).dateTaken)
    }

    // ── saveNoteForPhoto ─────────────────────────────────────────────────────

    @Test
    fun saveNoteForPhoto_withMatchingPhoto_callsSaveDescriptionAndResetsToIdle() = runTest {
        val photoId = 7L
        val photo = PhotoEntity(
            id = photoId, filePath = "file:///photos/test.jpg",
            colorName = "Blue", colorHex = "#1E88E5", dateTaken = 0L, dayIndex = 0,
            latitude = null, longitude = null, locationName = null,
            dominantColorHex = "#1E88E5"
        )
        coEvery { repo.getPhotoById(photoId) } returns photo

        var doneCalled = false
        val vm = buildViewModel()
        vm.saveNoteForPhoto(photoId, "Great blue sky", onDone = { doneCalled = true })
        advanceUntilIdle()

        coVerify { repo.saveDescription(photo, "Great blue sky") }
        assertEquals(CaptureState.Idle, vm.captureState.value)
        assertTrue("onDone callback must be invoked", doneCalled)
    }

    @Test
    fun saveNoteForPhoto_withNoMatchingPhoto_stillResetsToIdleAndCallsDone() = runTest {
        coEvery { repo.getPhotoById(any()) } returns null

        var doneCalled = false
        val vm = buildViewModel()
        vm.saveNoteForPhoto(999L, "orphan note", onDone = { doneCalled = true })
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.saveDescription(any(), any()) }
        assertEquals(CaptureState.Idle, vm.captureState.value)
        assertTrue(doneCalled)
    }

    // ── dismissNotePromptIfPending ───────────────────────────────────────────

    @Test
    fun dismissNotePromptIfPending_whenAwaitingNote_resetsToIdle() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.Success(
            mockk(relaxed = true), successValidation(), photoId = 5L
        )
        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()
        assertTrue(vm.captureState.value is CaptureState.AwaitingNote)

        // Swiping off the Camera pane with the prompt open must clear it, or the
        // stale prompt re-mounts instead of the viewfinder on the next visit.
        vm.dismissNotePromptIfPending()
        assertEquals(CaptureState.Idle, vm.captureState.value)
    }

    @Test
    fun dismissNotePromptIfPending_whenNotAwaitingNote_leavesStateUntouched() {
        val vm = buildViewModel()
        vm.startCapture() // Processing — must survive the pane leaving composition
        vm.dismissNotePromptIfPending()
        assertEquals(CaptureState.Processing, vm.captureState.value)
    }

    // ── startCapture / onCaptureError ────────────────────────────────────────

    @Test
    fun startCapture_setsProcessingState() {
        val vm = buildViewModel()
        vm.startCapture()
        assertEquals(CaptureState.Processing, vm.captureState.value)
    }

    @Test
    fun onCaptureError_setsCaptureFailedState() {
        val vm = buildViewModel()
        vm.onCaptureError()
        assertEquals(CaptureState.CaptureFailed, vm.captureState.value)
    }

    @Test
    fun onCaptureError_fromProcessing_setsCaptureFailed() {
        val vm = buildViewModel()
        vm.startCapture()
        assertEquals(CaptureState.Processing, vm.captureState.value)
        vm.onCaptureError()
        assertEquals(CaptureState.CaptureFailed, vm.captureState.value)
    }

    // ── shutter time drives color and day (BUG-047) ──────────────────────────

    @Test
    fun capture_usesTheShutterInstant_forBothColorAndSavedTime() = runTest {
        val colorArg = io.mockk.slot<com.colorwalk.app.domain.WalkColor>()
        val atArg = io.mockk.slot<Long>()
        coEvery { repo.savePhoto(any(), capture(colorArg), any(), capture(atArg), any()) } returns SaveResult.StorageError

        val vm = buildViewModel()
        val before = System.currentTimeMillis()
        vm.startCapture()                  // shutter pressed
        vm.onPhotoCaptured(jpegBytes)      // callback lands later
        advanceUntilIdle()

        assertTrue("saved time is the shutter press", atArg.captured in before..System.currentTimeMillis())
        assertEquals("color is that same instant's", colorForDay(atArg.captured), colorArg.captured)
    }

    @Test
    fun captureCrop_isPassedThroughToValidation() = runTest {
        val crop = com.colorwalk.app.data.repository.NormalizedCrop(0.3f, 0f, 0.7f, 1f)
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.StorageError
        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes, crop = crop)
        advanceUntilIdle()
        coVerify { repo.savePhoto(any(), any(), any(), any(), crop) }
    }

    // ── specific failures, no crashes (BUG-021, BUG-049) ─────────────────────

    @Test
    fun saveThrowing_becomesAnErrorCard_notACrash() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } throws IllegalStateException("db closed")
        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()
        assertEquals(CaptureState.StorageError, vm.captureState.value)
    }

    @Test
    fun importThrowing_becomesAnErrorCard_notACrash() = runTest {
        coEvery { repo.importPhoto(any(), any()) } throws IllegalArgumentException("bad projection")
        val vm = buildViewModel()
        vm.onPhotoImported(mockk(relaxed = true))
        advanceUntilIdle()
        assertEquals(CaptureState.StorageError, vm.captureState.value)
    }

    @Test
    fun storageFull_andUnreadable_mapToTheirOwnStates() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.StorageFull
        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()
        assertEquals(CaptureState.StorageFull, vm.captureState.value)

        coEvery { repo.importPhoto(any(), any()) } returns ImportResult.Unreadable
        vm.onPhotoImported(mockk(relaxed = true))
        advanceUntilIdle()
        assertEquals(CaptureState.ImportUnreadable, vm.captureState.value)
    }

    // ── lens / zoom survive the pane (BUG-043) ───────────────────────────────

    @Test
    fun lensAndZoomChoices_liveInTheViewModel() {
        val vm = buildViewModel()
        vm.useFrontCamera = true
        vm.requestedZoom = 2f
        assertEquals(true, vm.useFrontCamera)
        assertEquals(2f, vm.requestedZoom, 0f)
    }

    // ── note draft on leaving the pane (BUG-048) ─────────────────────────────

    private suspend fun vmAwaitingNote(photoId: Long = 5L): CameraViewModel {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns
            SaveResult.Success(mockk(relaxed = true), successValidation(), photoId)
        return buildViewModel().also { it.onPhotoCaptured(jpegBytes) }
    }

    @Test
    fun leavingWithATypedNote_savesIt() = runTest {
        val photo = mockk<PhotoEntity>(relaxed = true)
        coEvery { repo.getPhotoById(5L) } returns photo
        val vm = vmAwaitingNote()
        advanceUntilIdle()

        vm.onNoteDraftChanged("  sunset by the pier ")
        vm.dismissNotePromptIfPending()
        advanceUntilIdle()

        assertEquals(CaptureState.Idle, vm.captureState.value)
        coVerify { repo.saveDescription(photo, "sunset by the pier") }
    }

    @Test
    fun leavingWithABlankNote_savesNothing() = runTest {
        val vm = vmAwaitingNote()
        advanceUntilIdle()
        vm.onNoteDraftChanged("   ")
        vm.dismissNotePromptIfPending()
        advanceUntilIdle()
        coVerify(exactly = 0) { repo.saveDescription(any(), any()) }
    }

    @Test
    fun explicitSkip_discardsTheDraft() = runTest {
        val vm = vmAwaitingNote()
        advanceUntilIdle()
        vm.onNoteDraftChanged("typed then skipped")
        vm.resetState()                 // Skip button
        vm.dismissNotePromptIfPending() // pane then leaves
        advanceUntilIdle()
        coVerify(exactly = 0) { repo.saveDescription(any(), any()) }
    }

    // ── capture abort / watchdog (BUG-001) ───────────────────────────────────

    @Test
    fun onCaptureAborted_fromProcessing_returnsToIdle() {
        val vm = buildViewModel()
        vm.startCapture()
        vm.onCaptureAborted()
        assertEquals(CaptureState.Idle, vm.captureState.value)
    }

    @Test
    fun onCaptureAborted_whenNotProcessing_leavesStateUntouched() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.StorageError
        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()
        vm.onCaptureAborted()
        assertEquals(CaptureState.StorageError, vm.captureState.value)
    }

    @Test
    fun startCapture_withNoCameraCallback_timesOutToCaptureFailed() = runTest {
        val vm = buildViewModel()
        vm.startCapture()
        advanceTimeBy(CAPTURE_CALLBACK_TIMEOUT_MS - 1)
        assertEquals(CaptureState.Processing, vm.captureState.value)
        advanceTimeBy(2)
        assertEquals(CaptureState.CaptureFailed, vm.captureState.value)
    }

    @Test
    fun watchdog_isCancelledOnceTheCameraCallsBack_evenIfSaveIsSlow() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(CAPTURE_CALLBACK_TIMEOUT_MS * 2)
            SaveResult.StorageError
        }
        val vm = buildViewModel()
        vm.startCapture()
        vm.onPhotoCaptured(jpegBytes)
        advanceTimeBy(CAPTURE_CALLBACK_TIMEOUT_MS + 1)
        assertEquals(
            "A slow save must not be cut short by the camera-callback watchdog",
            CaptureState.Processing, vm.captureState.value
        )
    }

    @Test
    fun watchdog_isCancelledByOnCaptureAborted() = runTest {
        val vm = buildViewModel()
        vm.startCapture()
        vm.onCaptureAborted()
        advanceTimeBy(CAPTURE_CALLBACK_TIMEOUT_MS + 1)
        assertEquals(CaptureState.Idle, vm.captureState.value)
    }

    // ── resetState ───────────────────────────────────────────────────────────

    @Test
    fun resetState_fromAwaitingNote_transitionsBackToIdle() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.Success(uri, successValidation(), 1L)

        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()
        assertTrue(vm.captureState.value is CaptureState.AwaitingNote)

        vm.resetState()

        assertEquals(CaptureState.Idle, vm.captureState.value)
    }

    @Test
    fun resetState_fromFailed_transitionsBackToIdle() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.ValidationFailed(failedValidation())

        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()
        assertTrue(vm.captureState.value is CaptureState.Failed)

        vm.resetState()

        assertEquals(CaptureState.Idle, vm.captureState.value)
    }

    @Test
    fun resetState_fromStorageError_transitionsBackToIdle() = runTest {
        coEvery { repo.savePhoto(any(), any(), any(), any(), any()) } returns SaveResult.StorageError

        val vm = buildViewModel()
        vm.onPhotoCaptured(jpegBytes)
        advanceUntilIdle()

        vm.resetState()

        assertEquals(CaptureState.Idle, vm.captureState.value)
    }
}
