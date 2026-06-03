package com.colorwalk.app.viewmodel

import app.cash.turbine.test
import com.colorwalk.app.data.db.ColorSummary
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.data.repository.PhotoRepository
import com.colorwalk.app.util.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repo: PhotoRepository
    private lateinit var photosFlow: MutableStateFlow<List<PhotoEntity>>
    private lateinit var colorsFlow: MutableStateFlow<List<ColorSummary>>

    private fun makePhoto(
        id: Long,
        colorName: String = "Red",
        colorHex: String = "#E53935",
        dateTaken: Long = System.currentTimeMillis()
    ) = PhotoEntity(
        id = id,
        filePath = "file:///photos/$id.jpg",
        colorName = colorName,
        colorHex = colorHex,
        dateTaken = dateTaken,
        latitude = null,
        longitude = null,
        locationName = null,
        dominantColorHex = colorHex
    )

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        photosFlow = MutableStateFlow(emptyList())
        colorsFlow = MutableStateFlow(emptyList())

        every { repo.getAllPhotos() } returns photosFlow
        every { repo.getDistinctColors() } returns colorsFlow
        every { repo.getPhotosByColor(any()) } returns flowOf(emptyList())
    }

    private fun buildViewModel() = GalleryViewModel(repo)

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    fun initialAllPhotos_isEmptyList() = runTest {
        val vm = buildViewModel()
        vm.allPhotos.test {
            assertEquals(emptyList<PhotoEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun initialSelectedColor_isNull() = runTest {
        val vm = buildViewModel()
        assertNull(vm.selectedColor.value)
    }

    @Test
    fun initialViewerState_isNull() = runTest {
        val vm = buildViewModel()
        assertNull(vm.viewerState.value)
    }

    // ── selectColor / clearSelection ─────────────────────────────────────────

    @Test
    fun selectColor_setsSelectedColor() = runTest {
        val vm = buildViewModel()
        vm.selectColor("Blue")
        assertEquals("Blue", vm.selectedColor.value)
    }

    @Test
    fun clearSelection_resetsSelectedColorToNull() = runTest {
        val vm = buildViewModel()
        vm.selectColor("Green")
        vm.clearSelection()
        assertNull(vm.selectedColor.value)
    }

    @Test
    fun selectColor_thenSelectAnother_updatesToLatestColor() = runTest {
        val vm = buildViewModel()
        vm.selectColor("Red")
        vm.selectColor("Purple")
        assertEquals("Purple", vm.selectedColor.value)
    }

    // ── viewMode ─────────────────────────────────────────────────────────────

    @Test
    fun setViewMode_dateMode_updatesViewMode() = runTest {
        val vm = buildViewModel()
        vm.setViewMode(GalleryViewMode.DATE)
        assertEquals(GalleryViewMode.DATE, vm.viewMode.value)
    }

    @Test
    fun setViewMode_colorMode_isDefaultMode() = runTest {
        val vm = buildViewModel()
        assertEquals(GalleryViewMode.COLOR, vm.viewMode.value)
    }

    // ── openPhoto / closePhoto ────────────────────────────────────────────────

    @Test
    fun openPhoto_setsViewerStateWithCorrectInitialIndex() = runTest {
        val vm = buildViewModel()
        val photos = listOf(makePhoto(1L), makePhoto(2L), makePhoto(3L))
        val target = photos[1]

        vm.openPhoto(target, photos)

        val viewerState = vm.viewerState.value
        assertNotNull(viewerState)
        assertEquals(1, viewerState!!.initialIndex)
        assertEquals(photos, viewerState.photos)
    }

    @Test
    fun openPhoto_firstPhoto_setsInitialIndexZero() = runTest {
        val vm = buildViewModel()
        val photos = listOf(makePhoto(10L), makePhoto(11L))

        vm.openPhoto(photos[0], photos)

        assertEquals(0, vm.viewerState.value!!.initialIndex)
    }

    @Test
    fun openPhoto_withPhotoNotInList_doesNotSetViewerState() = runTest {
        val vm = buildViewModel()
        val photos = listOf(makePhoto(1L), makePhoto(2L))
        val notInList = makePhoto(99L)

        vm.openPhoto(notInList, photos)

        assertNull(vm.viewerState.value)
    }

    @Test
    fun closePhoto_clearsViewerState() = runTest {
        val vm = buildViewModel()
        val photos = listOf(makePhoto(1L))
        vm.openPhoto(photos[0], photos)
        assertNotNull(vm.viewerState.value) // confirm it was set

        vm.closePhoto()

        assertNull(vm.viewerState.value)
    }

    // ── deletePhoto ───────────────────────────────────────────────────────────

    @Test
    fun deletePhoto_callsRepoDeletePhoto() = runTest {
        val vm = buildViewModel()
        val photo = makePhoto(5L)

        vm.deletePhoto(photo)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.deletePhoto(photo) }
    }

    @Test
    fun deletePhoto_whenViewerOpen_removesPhotoFromViewerList() = runTest {
        val vm = buildViewModel()
        val p1 = makePhoto(1L)
        val p2 = makePhoto(2L)
        val p3 = makePhoto(3L)
        val photos = listOf(p1, p2, p3)

        vm.openPhoto(p1, photos)
        vm.deletePhoto(p2)
        advanceUntilIdle()

        val remaining = vm.viewerState.value?.photos
        assertNotNull(remaining)
        assertTrue(remaining!!.none { it.id == 2L })
        assertEquals(2, remaining.size)
    }

    @Test
    fun deletePhoto_lastPhotoInViewer_closesViewer() = runTest {
        val vm = buildViewModel()
        val photo = makePhoto(1L)

        vm.openPhoto(photo, listOf(photo))
        vm.deletePhoto(photo)
        advanceUntilIdle()

        assertNull("Viewer should close when last photo is deleted", vm.viewerState.value)
    }

    // ── allPhotos flow propagation ────────────────────────────────────────────

    @Test
    fun allPhotos_emitsListFromRepo() = runTest {
        val vm = buildViewModel()
        val newPhotos = listOf(makePhoto(42L, colorName = "Yellow"))

        vm.allPhotos.test {
            awaitItem() // empty initial
            photosFlow.value = newPhotos
            val emitted = awaitItem()
            assertEquals(1, emitted.size)
            assertEquals(42L, emitted[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
