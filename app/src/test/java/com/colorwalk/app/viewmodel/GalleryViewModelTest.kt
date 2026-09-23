package com.colorwalk.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
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
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repo: PhotoRepository
    private lateinit var photosFlow: MutableStateFlow<List<PhotoEntity>>

    private fun makePhoto(
        id: Long,
        colorName: String = "Red",
        colorHex: String = "#E53935",
        dateTaken: Long = System.currentTimeMillis(),
        locationName: String? = null
    ) = PhotoEntity(
        id = id,
        filePath = "file:///photos/$id.jpg",
        colorName = colorName,
        colorHex = colorHex,
        dateTaken = dateTaken,
        dayIndex = com.colorwalk.app.domain.StreakCalculator.epochMillisToDayIndex(dateTaken),
        latitude = null,
        longitude = null,
        locationName = locationName,
        dominantColorHex = colorHex
    )

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        photosFlow = MutableStateFlow(emptyList())

        every { repo.getAllPhotos() } returns photosFlow
        every { repo.getPhotosByColor(any()) } returns flowOf(emptyList())
    }

    private fun buildViewModel(saved: SavedStateHandle = SavedStateHandle()) =
        GalleryViewModel(repo, saved, mainDispatcherRule.testDispatcher)

    // ── open viewer follows live rows (BUG-063) ──────────────────────────────

    @Test
    fun openViewer_picksUpRowChanges_keepingItsOrder() = runTest {
        val a = makePhoto(1L); val b = makePhoto(2L)
        photosFlow.value = listOf(a, b)
        val vm = buildViewModel()
        vm.openPhoto(b, listOf(a, b))

        // A geocode lands for photo 2 while the viewer is open.
        photosFlow.value = listOf(a, b.copy(locationName = "Lisbon"))

        val vs = vm.viewerState.value!!
        assertEquals(listOf(1L, 2L), vs.photos.map { it.id })
        assertEquals("Lisbon", vs.photos[1].locationName)
        assertEquals(1, vs.initialIndex)
    }

    @Test
    fun openViewer_rowMissingFromTable_keepsItsSnapshot() = runTest {
        val vm = buildViewModel()
        val p = makePhoto(7L)
        vm.openPhoto(p, listOf(p))   // photosFlow is empty
        assertEquals(listOf(p), vm.viewerState.value!!.photos)
    }

    // ── date filters follow the day (BUG-063) ────────────────────────────────

    @Test
    fun thisWeekFilter_isRelativeToTheDayFedIn_notFrozenAtLastEmission() = runTest {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        photosFlow.value = listOf(makePhoto(1L, dateTaken = cal.timeInMillis))
        val vm = buildViewModel()
        vm.setDateFilter(DateFilter.THIS_WEEK)
        vm.allPhotos.test {
            var list = awaitItem()
            while (list.isEmpty()) list = awaitItem()
            assertEquals(1, list.size)          // yesterday is (normally) this week…
            // …but 30 days later it no longer is — without any new DB emission.
            vm.onDayChanged(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── process-death restore (BUG-008) ──────────────────────────────────────

    @Test
    fun openAlbumAndTab_areWrittenToSavedState() = runTest {
        val saved = SavedStateHandle()
        val vm = buildViewModel(saved)
        vm.setViewMode(GalleryViewMode.PLACE)
        vm.selectPlace("Lisbon")
        vm.openUntagged()

        // A new ViewModel on the restored handle — what process death recreates.
        val restored = buildViewModel(saved)
        assertEquals(GalleryViewMode.PLACE, restored.viewMode.value)
        assertEquals("Lisbon", restored.selectedPlace.value)
        assertTrue(restored.showingUntagged.value)
    }

    @Test
    fun selectedColor_survivesRecreation() = runTest {
        val saved = SavedStateHandle()
        buildViewModel(saved).selectColor("Blue")
        assertEquals("Blue", buildViewModel(saved).selectedColor.value)
    }

    // ── month sections off the main thread (BUG-041) ─────────────────────────

    @Test
    fun photosByMonth_groupsInDisplayOrder() = runTest {
        val sep = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 10, 12, 0) }.timeInMillis
        val aug = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 3, 12, 0) }.timeInMillis
        photosFlow.value = listOf(
            makePhoto(1, dateTaken = aug), makePhoto(2, dateTaken = sep), makePhoto(3, dateTaken = sep + 1000)
        )
        val vm = buildViewModel()
        vm.photosByMonth.test {
            var sections = awaitItem()
            while (sections.isEmpty()) sections = awaitItem()
            val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
            assertEquals(listOf(fmt.format(java.util.Date(sep)), fmt.format(java.util.Date(aug))), sections.map { it.first })
            assertEquals(listOf(3L, 2L), sections[0].second.map { it.id })   // newest first
            cancelAndIgnoreRemainingEvents()
        }
    }

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
        assertNotNull(vm.viewerState.value)

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

    // ── searchQuery ───────────────────────────────────────────────────────────
    // Note: stateIn(WhileSubscribed) requires an active subscriber to collect the
    // upstream combine. All filter/sort tests use Turbine's .test {} to subscribe
    // first, then trigger changes inside — matching the allPhotos_emitsListFromRepo pattern.

    @Test
    fun initialSearchQuery_isEmpty() = runTest {
        val vm = buildViewModel()
        assertEquals("", vm.searchQuery.value)
    }

    @Test
    fun setSearchQuery_filtersColorFolderCardsByName_caseInsensitive() = runTest {
        photosFlow.value = listOf(
            makePhoto(1, colorName = "Blue", colorHex = "#1E88E5"),
            makePhoto(2, colorName = "Red", colorHex = "#E53935")
        )
        val vm = buildViewModel()
        vm.colorFolderCards.test {
            var cards = awaitItem()
            while (cards.size != 2) cards = awaitItem()
            vm.setSearchQuery("blue")
            val filtered = awaitItem()
            assertEquals(listOf("Blue"), filtered.map { it.colorName })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setSearchQuery_emptyQuery_returnsAllFolderCards() = runTest {
        photosFlow.value = listOf(
            makePhoto(1, colorName = "Blue", colorHex = "#1E88E5"),
            makePhoto(2, colorName = "Red", colorHex = "#E53935")
        )
        val vm = buildViewModel()
        vm.colorFolderCards.test {
            var cards = awaitItem()
            while (cards.size != 2) cards = awaitItem()
            vm.setSearchQuery("xyz")
            assertEquals(0, awaitItem().size)
            vm.setSearchQuery("")
            assertEquals(2, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── dateFilter ────────────────────────────────────────────────────────────

    @Test
    fun initialDateFilter_isAll() = runTest {
        val vm = buildViewModel()
        assertEquals(DateFilter.ALL, vm.dateFilter.value)
    }

    @Test
    fun setDateFilter_thisMonth_excludesPhotosFromPreviousMonth() = runTest {
        // first-of-this-month at noon → always in THIS_MONTH
        val thisMonthTs = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // first-of-last-month at noon → always before THIS_MONTH cutoff
        val lastMonthTs = Calendar.getInstance().apply {
            add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val vm = buildViewModel()
        vm.allPhotos.test {
            awaitItem() // initial []
            photosFlow.value = listOf(
                makePhoto(1L, dateTaken = thisMonthTs),
                makePhoto(2L, dateTaken = lastMonthTs)
            )
            awaitItem() // [photo1, photo2] — ALL filter
            vm.setDateFilter(DateFilter.THIS_MONTH)
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals(1L, filtered[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setDateFilter_last3Months_excludesVeryOldPhotos() = runTest {
        val recentTs = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000   // 60 days ago
        val oldTs    = System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000  // 100 days ago

        val vm = buildViewModel()
        vm.allPhotos.test {
            awaitItem() // initial []
            photosFlow.value = listOf(
                makePhoto(1L, dateTaken = recentTs),
                makePhoto(2L, dateTaken = oldTs)
            )
            awaitItem() // [photo1, photo2] — ALL filter
            vm.setDateFilter(DateFilter.LAST_3_MONTHS)
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals(1L, filtered[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setDateFilter_all_returnsAllPhotos_afterPreviousFilter() = runTest {
        val oldTs = System.currentTimeMillis() - 200L * 24 * 60 * 60 * 1000  // 200 days ago

        val vm = buildViewModel()
        vm.allPhotos.test {
            awaitItem() // initial []
            // photo1 = 200 days ago (excluded by THIS_MONTH), photo2 = today (included)
            photosFlow.value = listOf(makePhoto(1L, dateTaken = oldTs), makePhoto(2L))
            awaitItem() // [photo1, photo2] — ALL filter
            vm.setDateFilter(DateFilter.THIS_MONTH)
            awaitItem() // [photo2] — only today's photo
            vm.setDateFilter(DateFilter.ALL)
            val all = awaitItem()
            assertEquals(2, all.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── albumSortOrder ────────────────────────────────────────────────────────

    @Test
    fun initialAlbumSortOrder_isNewest() = runTest {
        val vm = buildViewModel()
        assertEquals(AlbumSortOrder.NEWEST, vm.albumSortOrder.value)
    }

    @Test
    fun photosForColor_newest_sortedDescByDateTaken() = runTest {
        val older = makePhoto(1L, dateTaken = System.currentTimeMillis() - 10_000L)
        val newer = makePhoto(2L, dateTaken = System.currentTimeMillis())
        // Repo returns older first; ViewModel should flip to newest-first
        val photosForColorFlow = MutableStateFlow(listOf(older, newer))
        every { repo.getPhotosByColor("Red") } returns photosForColorFlow

        val vm = buildViewModel()
        vm.photosForColor.test {
            awaitItem() // initial []
            vm.selectColor("Red")
            val sorted = awaitItem()
            assertEquals(2, sorted.size)
            assertEquals(2L, sorted[0].id) // newer first
            assertEquals(1L, sorted[1].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun photosForColor_oldest_sortedAscByDateTaken() = runTest {
        val older = makePhoto(1L, dateTaken = System.currentTimeMillis() - 10_000L)
        val newer = makePhoto(2L, dateTaken = System.currentTimeMillis())
        // Repo returns newer first; ViewModel NEWEST keeps that order, OLDEST reverses it
        val photosForColorFlow = MutableStateFlow(listOf(newer, older))
        every { repo.getPhotosByColor("Red") } returns photosForColorFlow

        val vm = buildViewModel()
        vm.photosForColor.test {
            awaitItem() // initial []
            vm.selectColor("Red")
            awaitItem() // [newer, older] — default NEWEST sort
            vm.setAlbumSortOrder(AlbumSortOrder.OLDEST)
            val oldest = awaitItem()
            assertEquals(2, oldest.size)
            assertEquals(1L, oldest[0].id) // older first
            assertEquals(2L, oldest[1].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── dateSortOrder ─────────────────────────────────────────────────────────

    @Test
    fun initialDateSortOrder_isNewest() = runTest {
        val vm = buildViewModel()
        assertEquals(AlbumSortOrder.NEWEST, vm.dateSortOrder.value)
    }

    @Test
    fun allPhotos_default_sortedNewestFirst() = runTest {
        val older = makePhoto(1L, dateTaken = System.currentTimeMillis() - 10_000L)
        val newer = makePhoto(2L, dateTaken = System.currentTimeMillis())

        val vm = buildViewModel()
        vm.allPhotos.test {
            awaitItem() // initial []
            photosFlow.value = listOf(older, newer) // repo returns older first
            val sorted = awaitItem()
            assertEquals(2L, sorted[0].id) // newer first by default
            assertEquals(1L, sorted[1].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setDateSortOrder_oldest_sortsByDateAscending() = runTest {
        val older = makePhoto(1L, dateTaken = System.currentTimeMillis() - 10_000L)
        val newer = makePhoto(2L, dateTaken = System.currentTimeMillis())

        val vm = buildViewModel()
        vm.allPhotos.test {
            awaitItem() // initial []
            photosFlow.value = listOf(newer, older)
            awaitItem() // [newer, older] — default NEWEST sort
            vm.setDateSortOrder(AlbumSortOrder.OLDEST)
            val sorted = awaitItem()
            assertEquals(1L, sorted[0].id) // older first
            assertEquals(2L, sorted[1].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── THIS_WEEK filter ──────────────────────────────────────────────────────

    @Test
    fun setDateFilter_thisWeek_excludesPhotosOlderThanOneWeek() = runTest {
        val todayTs       = System.currentTimeMillis()
        val twoWeeksAgoTs = todayTs - 14L * 24 * 60 * 60 * 1000

        val vm = buildViewModel()
        vm.allPhotos.test {
            awaitItem() // initial []
            photosFlow.value = listOf(
                makePhoto(1L, dateTaken = todayTs),
                makePhoto(2L, dateTaken = twoWeeksAgoTs)
            )
            awaitItem() // [photo1, photo2] — ALL filter
            vm.setDateFilter(DateFilter.THIS_WEEK)
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals(1L, filtered[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── photosByPlace ─────────────────────────────────────────────────────────

    @Test
    fun photosByPlace_groupsByLocationName() = runTest {
        val vm = buildViewModel()
        vm.photosByPlace.test {
            awaitItem() // initial []
            photosFlow.value = listOf(
                makePhoto(1L, locationName = "Paris"),
                makePhoto(2L, locationName = "Paris"),
                makePhoto(3L, locationName = "Tokyo"),
                makePhoto(4L)  // no location — excluded
            )
            val places = awaitItem()
            assertEquals(2, places.size)
            val paris = places.find { it.locationName == "Paris" }
            val tokyo = places.find { it.locationName == "Tokyo" }
            assertNotNull(paris)
            assertNotNull(tokyo)
            assertEquals(2, paris!!.photoCount)
            assertEquals(1, tokyo!!.photoCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun photosByPlace_sortedByPhotoCountDescending() = runTest {
        val vm = buildViewModel()
        vm.photosByPlace.test {
            awaitItem() // initial []
            photosFlow.value = listOf(
                makePhoto(1L, locationName = "Tokyo"),
                makePhoto(2L, locationName = "Paris"),
                makePhoto(3L, locationName = "Paris"),
                makePhoto(4L, locationName = "Paris")
            )
            val places = awaitItem()
            assertEquals("Paris", places[0].locationName) // 3 photos — first
            assertEquals("Tokyo", places[1].locationName) // 1 photo — second
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun photosByPlace_excludesPhotosWithNoLocation() = runTest {
        val vm = buildViewModel()
        vm.photosByPlace.test {
            awaitItem() // initial []
            // Emit a photo with location so StateFlow reaches a non-empty state first
            photosFlow.value = listOf(makePhoto(1L, locationName = "Paris"))
            assertEquals(1, awaitItem().size)
            // Replace with photos that have no location — StateFlow transitions back to empty
            photosFlow.value = listOf(makePhoto(2L), makePhoto(3L))
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── selectPlace / clearPlaceSelection ────────────────────────────────────

    @Test
    fun selectPlace_setsSelectedPlace() = runTest {
        val vm = buildViewModel()
        vm.selectPlace("Paris")
        assertEquals("Paris", vm.selectedPlace.value)
    }

    @Test
    fun clearPlaceSelection_resetsToNull() = runTest {
        val vm = buildViewModel()
        vm.selectPlace("Paris")
        vm.clearPlaceSelection()
        assertNull(vm.selectedPlace.value)
    }

    @Test
    fun initialSelectedPlace_isNull() = runTest {
        val vm = buildViewModel()
        assertNull(vm.selectedPlace.value)
    }
}
