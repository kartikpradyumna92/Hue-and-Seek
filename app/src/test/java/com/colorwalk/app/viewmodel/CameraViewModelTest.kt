package com.colorwalk.app.viewmodel

import android.graphics.Bitmap
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
    private lateinit var bitmap: Bitmap

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        bitmap = mockk(relaxed = true)
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
        coEvery { repo.savePhoto(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(10_000)
            SaveResult.StorageError
        }

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)

        assertEquals(CaptureState.Processing, vm.captureState.value)
    }

    @Test
    fun onPhotoCaptured_withSuccessResult_transitionsToAwaitingNoteWithCorrectHexAndId() = runTest {
        val dominantHex = "#FF0000"
        val photoId = 42L
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.Success(uri, successValidation(dominantHex), photoId)

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)
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
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.ValidationFailed(failedValidation())

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)
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
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.StorageError

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)
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
            colorName = "Blue", colorHex = "#1E88E5", dateTaken = 0L,
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
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.Success(
            mockk(relaxed = true), successValidation(), photoId = 5L
        )
        val vm = buildViewModel()
        vm.onPhotoCaptured(mockk(relaxed = true))
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
    fun onCaptureError_setsStorageErrorState() {
        val vm = buildViewModel()
        vm.onCaptureError()
        assertEquals(CaptureState.StorageError, vm.captureState.value)
    }

    @Test
    fun onCaptureError_fromProcessing_setsStorageError() {
        val vm = buildViewModel()
        vm.startCapture()
        assertEquals(CaptureState.Processing, vm.captureState.value)
        vm.onCaptureError()
        assertEquals(CaptureState.StorageError, vm.captureState.value)
    }

    // ── resetState ───────────────────────────────────────────────────────────

    @Test
    fun resetState_fromAwaitingNote_transitionsBackToIdle() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.Success(uri, successValidation(), 1L)

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)
        advanceUntilIdle()
        assertTrue(vm.captureState.value is CaptureState.AwaitingNote)

        vm.resetState()

        assertEquals(CaptureState.Idle, vm.captureState.value)
    }

    @Test
    fun resetState_fromFailed_transitionsBackToIdle() = runTest {
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.ValidationFailed(failedValidation())

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)
        advanceUntilIdle()
        assertTrue(vm.captureState.value is CaptureState.Failed)

        vm.resetState()

        assertEquals(CaptureState.Idle, vm.captureState.value)
    }

    @Test
    fun resetState_fromStorageError_transitionsBackToIdle() = runTest {
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.StorageError

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)
        advanceUntilIdle()

        vm.resetState()

        assertEquals(CaptureState.Idle, vm.captureState.value)
    }
}
