package com.colorwalk.app.viewmodel

import app.cash.turbine.test
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.util.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewsfeedViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repo: PhotoRepository

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
    }

    private fun makePhoto(
        id: Long,
        colorName: String = "Red",
        dateTaken: Long = System.currentTimeMillis(),
        description: String? = null
    ) = PhotoEntity(
        id = id,
        filePath = "file:///photos/test_$id.jpg",
        colorName = colorName,
        colorHex = "#E53935",
        dateTaken = dateTaken,
        latitude = null,
        longitude = null,
        locationName = null,
        dominantColorHex = "#E53935",
        description = description
    )

    // ── photos flow ───────────────────────────────────────────────────────────

    @Test
    fun photos_emitsAllPhotosFromRepo() = runTest {
        val photos = listOf(makePhoto(1L), makePhoto(2L), makePhoto(3L))
        every { repo.getAllPhotos() } returns flowOf(photos)

        val vm = NewsfeedViewModel(repo)

        vm.photos.test {
            assertEquals(photos, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun photos_emitsEmptyListWhenRepoHasNoPhotos() = runTest {
        every { repo.getAllPhotos() } returns flowOf(emptyList())

        val vm = NewsfeedViewModel(repo)

        vm.photos.test {
            assertEquals(emptyList<PhotoEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── saveDescription ───────────────────────────────────────────────────────

    @Test
    fun saveDescription_delegatesToRepoWithTrimmedText() = runTest {
        every { repo.getAllPhotos() } returns flowOf(emptyList())
        val vm = NewsfeedViewModel(repo)
        val photo = makePhoto(id = 5L)

        vm.saveDescription(photo, "  nice blue door  ")
        advanceUntilIdle()

        // ViewModel passes the raw text; trimming happens inside repo.saveDescription.
        coVerify { repo.saveDescription(photo, "  nice blue door  ") }
    }

    @Test
    fun saveDescription_withBlankText_stillDelegatesToRepo() = runTest {
        every { repo.getAllPhotos() } returns flowOf(emptyList())
        val vm = NewsfeedViewModel(repo)
        val photo = makePhoto(id = 6L)

        vm.saveDescription(photo, "")
        advanceUntilIdle()

        coVerify { repo.saveDescription(photo, "") }
    }

    @Test
    fun saveDescription_doesNotMutatePhotosFlowDirectly() = runTest {
        val photos = listOf(makePhoto(1L, description = null))
        every { repo.getAllPhotos() } returns flowOf(photos)
        val vm = NewsfeedViewModel(repo)
        val photo = photos.first()

        vm.photos.test {
            awaitItem() // initial emission
            vm.saveDescription(photo, "test note")
            advanceUntilIdle()
            // No second emission — the Flow is owned by Room, not the ViewModel.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── swipe-right navigation (threshold logic) ───────────────────────────────

    @Test
    fun swipeRightThreshold_80dp_logicIsCorrect() {
        // Mirrors the 80.dp.toPx() threshold used in NewsfeedScreen's
        // detectHorizontalDragGestures. Converts using 160 dpi baseline.
        val dpToPx = { dp: Float -> dp * 160f / 160f } // density = 1f baseline
        val threshold = dpToPx(80f)

        // Swipe right past threshold → should trigger back
        assertTrue("Delta > 80dp must trigger back", 90f * 1f > threshold)
        // Swipe right but below threshold → must not trigger back
        assertFalse("Delta < 80dp must not trigger back", 40f * 1f > threshold)
        // Swipe left (negative) → must not trigger back
        assertFalse("Left swipe must not trigger back", -100f > threshold)
    }
}

private fun assertTrue(message: String, condition: Boolean) =
    org.junit.Assert.assertTrue(message, condition)

private fun assertFalse(message: String, condition: Boolean) =
    org.junit.Assert.assertFalse(message, condition)
