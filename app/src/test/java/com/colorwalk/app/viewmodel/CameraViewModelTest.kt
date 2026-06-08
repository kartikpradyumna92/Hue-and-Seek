package com.colorwalk.app.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.data.repository.SaveResult
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.domain.colorForDay
import com.colorwalk.app.util.MainDispatcherRule
import io.mockk.coEvery
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

    // ── targetColor ──────────────────────────────────────────────────────────

    @Test
    fun targetColor_matchesColorForDayToday() {
        val vm = buildViewModel()
        val expected = colorForDay(System.currentTimeMillis())
        assertEquals(
            "targetColor must equal colorForDay(now)",
            expected,
            vm.targetColor.value   // targetColor is now a StateFlow
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
        // Block savePhoto until after we check the state
        coEvery { repo.savePhoto(any(), any()) } coAnswers {
            // Suspend long enough for us to observe Processing first
            kotlinx.coroutines.delay(10_000)
            SaveResult.StorageError
        }

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)

        // Before advanceUntilIdle, Processing is the immediate state
        assertEquals(CaptureState.Processing, vm.captureState.value)
    }

    @Test
    fun onPhotoCaptured_withSuccessResult_transitionsToSuccessWithCorrectHex() = runTest {
        val dominantHex = "#FF0000"
        val validation = ColorValidator.ValidationResult(
            passed = true,
            dominantHex = dominantHex,
            dominantName = "Red",
            matchPercent = 0.85f,
            actualDominantColor = "Red"
        )
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.Success(uri, validation)

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)
        advanceUntilIdle()

        val state = vm.captureState.value
        assertTrue("Expected Success state, got $state", state is CaptureState.Success)
        assertEquals(dominantHex, (state as CaptureState.Success).dominantHex)
    }

    @Test
    fun onPhotoCaptured_withValidationFailedResult_transitionsToFailedWithCorrectValues() = runTest {
        val validation = ColorValidator.ValidationResult(
            passed = false,
            dominantHex = "#00FF00",
            dominantName = "Green",
            matchPercent = 0.20f,
            actualDominantColor = "Green"
        )
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.ValidationFailed(validation)

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

    // ── resetState ───────────────────────────────────────────────────────────

    @Test
    fun resetState_fromSuccess_transitionsBackToIdle() = runTest {
        val validation = ColorValidator.ValidationResult(
            passed = true,
            dominantHex = "#1E88E5",
            dominantName = "Blue",
            matchPercent = 0.9f,
            actualDominantColor = "Blue"
        )
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.Success(mockk(relaxed = true), validation)

        val vm = buildViewModel()
        vm.onPhotoCaptured(bitmap)
        advanceUntilIdle()
        assertTrue(vm.captureState.value is CaptureState.Success)

        vm.resetState()

        assertEquals(CaptureState.Idle, vm.captureState.value)
    }

    @Test
    fun resetState_fromFailed_transitionsBackToIdle() = runTest {
        val validation = ColorValidator.ValidationResult(
            passed = false,
            dominantHex = "#808080",
            dominantName = "Gray",
            matchPercent = 0.05f,
            actualDominantColor = "Gray"
        )
        coEvery { repo.savePhoto(any(), any()) } returns SaveResult.ValidationFailed(validation)

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
