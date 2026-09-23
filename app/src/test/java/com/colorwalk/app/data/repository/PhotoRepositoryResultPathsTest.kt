package com.colorwalk.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.data.db.PhotoDao
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.WALK_COLORS
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * I-6: JVM coverage for savePhoto/importPhoto RESULT paths — which outcome the
 * repository returns for each failure/success shape, with the collaborators faked
 * through the internal test constructor. (Pixel-level behavior is ColorValidator's
 * own suite; disk/MediaStore behavior belongs to the instrumented tests.)
 */
class PhotoRepositoryResultPathsTest {

    private val target = WALK_COLORS.first()

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var dao: PhotoDao
    private lateinit var files: PhotoFileStore
    private lateinit var location: LocationResolver
    private lateinit var mediaGallery: MediaStoreGallery
    private lateinit var repo: PhotoRepository

    @Before
    fun setUp() {
        resolver = mockk(relaxed = true) {
            every { query(any(), any(), any(), any(), any()) } returns null
            every { openInputStream(any()) } returns null
            every { openFileDescriptor(any(), any()) } returns null
        }
        context = mockk(relaxed = true) {
            every { contentResolver } returns resolver
            // A real directory: the free-space check (BUG-049) reads usableSpace.
            every { filesDir } returns java.nio.file.Files.createTempDirectory("cw").toFile()
        }
        dao = mockk(relaxed = true)
        files = mockk(relaxed = true)
        location = mockk(relaxed = true) {
            coEvery { getFreshLocation() } returns Pair(null, null)
        }
        mediaGallery = mockk(relaxed = true)
        repo = PhotoRepository(
            context, dao, mockk<AppDatabase>(relaxed = true),
            files, location, mediaGallery, mockk<GallerySynchronizer>(relaxed = true)
        )
        mockkObject(ColorValidator)
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk()
    }

    @After
    fun tearDown() = unmockkAll()

    private fun validation(passed: Boolean) = ColorValidator.ValidationResult(
        passed = passed,
        dominantHex = "#AA0000",
        dominantName = target.name,
        matchPercent = if (passed) 0.5f else 0.05f,
        actualDominantColor = target.name
    )

    private fun row(dateTaken: Long, size: Long?) = PhotoEntity(
        id = 1L, filePath = "/photos/x.jpg", colorName = target.name, colorHex = target.hex,
        dateTaken = dateTaken, dayIndex = StreakCalculator.epochMillisToDayIndex(dateTaken),
        latitude = null, longitude = null, locationName = null,
        dominantColorHex = target.hex, originalSizeBytes = size
    )

    // ── savePhoto ────────────────────────────────────────────────────────────

    @Test
    fun savePhoto_undecodableBytes_isStorageError_andNothingIsWritten() = runTest {
        every { files.decodeBounded(any()) } returns null

        assertEquals(SaveResult.StorageError, repo.savePhoto(byteArrayOf(1), target))

        verify(exactly = 0) { files.saveBytes(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun savePhoto_validationFails_isValidationFailed_andNothingIsWritten() = runTest {
        every { files.decodeBounded(any()) } returns mockk<Bitmap>(relaxed = true)
        every { ColorValidator.validate(any(), any()) } returns validation(passed = false)

        val result = repo.savePhoto(byteArrayOf(1), target)

        assertTrue(result is SaveResult.ValidationFailed)
        verify(exactly = 0) { files.saveBytes(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun savePhoto_privateWriteFails_isStorageError_andNoRowIsInserted() = runTest {
        every { files.decodeBounded(any()) } returns mockk<Bitmap>(relaxed = true)
        every { ColorValidator.validate(any(), any()) } returns validation(passed = true)
        every { files.saveBytes(any(), any()) } returns null

        assertEquals(SaveResult.StorageError, repo.savePhoto(byteArrayOf(1), target))
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun savePhoto_happyPath_insertsRowAndPublishesInBackground() = runTest {
        every { files.decodeBounded(any()) } returns mockk<Bitmap>(relaxed = true)
        every { ColorValidator.validate(any(), any()) } returns validation(passed = true)
        every { files.saveBytes(any(), any()) } returns File.createTempFile("colorwalk", ".jpg").apply { deleteOnExit() }
        coEvery { dao.insert(any()) } returns 7L

        val result = repo.savePhoto(byteArrayOf(1), target)

        assertTrue(result is SaveResult.Success)
        assertEquals(7L, (result as SaveResult.Success).photoId)
        coVerify { dao.insert(match { it.colorName == target.name && it.latitude == null }) }
        // Publish is L-8-deferred to the repo scope — wait for it rather than racing it.
        verify(timeout = 3000) {
            mediaGallery.publish(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ── importPhoto ──────────────────────────────────────────────────────────

    @Test
    fun importPhoto_noReadableDate_isNoDateMetadata() = runTest {
        assertEquals(ImportResult.NoDateMetadata, repo.importPhoto(mockk(), target))
    }

    private fun stubDateCursor(dateMillis: Long) {
        val cursor = mockk<Cursor>(relaxed = true) {
            every { moveToFirst() } returns true
            every { getColumnIndex(any()) } returns 0
            every { getLong(0) } returns dateMillis
        }
        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
    }

    @Test
    fun importPhoto_sameSecondRowWithUnknownSize_isAlreadyImported() = runTest {
        val now = System.currentTimeMillis()
        stubDateCursor(now)
        coEvery { dao.getByDateTakenSecond(now / 1000) } returns listOf(row(now - 200, size = null))

        assertEquals(ImportResult.AlreadyImported, repo.importPhoto(mockk(), target))
        verify(exactly = 0) { files.decodeBoundedFromUri(any()) }
    }

    @Test
    fun importPhoto_sameSecondBurstWithDifferentKnownSize_passesDedup() = runTest {
        val now = System.currentTimeMillis()
        stubDateCursor(now)
        // Incoming photo is 222 bytes; the existing same-second row was 111 —
        // a distinct burst shot, so dedup must NOT reject it (M-4).
        every { resolver.openFileDescriptor(any(), "r") } returns mockk<ParcelFileDescriptor>(relaxed = true) {
            every { statSize } returns 222L
        }
        coEvery { dao.getByDateTakenSecond(now / 1000) } returns listOf(row(now - 200, size = 111L))
        every { files.decodeBoundedFromUri(any()) } returns null // stop right after the dedup gate

        assertEquals(ImportResult.Unreadable, repo.importPhoto(mockk(), target))
        verify { files.decodeBoundedFromUri(any()) } // proof the gate was passed
    }

    // ── storage fixes ────────────────────────────────────────────────────────

    /** Stubs a passing import of a photo taken at [dateMillis]; returns the stored file. */
    private fun stubPassingImport(dateMillis: Long): File {
        stubDateCursor(dateMillis)
        every { files.decodeBoundedFromUri(any()) } returns mockk(relaxed = true)
        every { ColorValidator.validate(any(), any()) } returns validation(passed = true)
        val stored = File.createTempFile("colorwalk", ".jpg").apply { deleteOnExit() }
        every { files.copyFromUri(any(), any(), false) } returns stored
        every { files.transcodeToJpeg(any(), any()) } returns stored
        coEvery { dao.insert(any()) } returns 11L
        return stored
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun importPhoto_graceWindowPhotoFromLateYesterday_isCreditedToToday() = runTest {
        // BUG-018: 23:00 yesterday is inside isToday()'s ±4 h window and validated
        // against TODAY's color — so it must count for today, not back-fill yesterday.
        val lateYesterday = startOfToday() - 60 * 60 * 1000L
        stubPassingImport(lateYesterday)

        assertTrue(repo.importPhoto(mockk(), target) is ImportResult.Success)
        val today = StreakCalculator.epochMillisToDayIndex(System.currentTimeMillis())
        coVerify { dao.insert(match { it.dayIndex == today && it.dateTaken == lateYesterday }) }
    }

    @Test
    fun importPhoto_publishesAnAlbumCopyForRecovery() = runTest {
        // BUG-020: without a Pictures/ColorWalk copy, a reinstall can't recover it.
        val now = System.currentTimeMillis()
        val stored = stubPassingImport(now)

        repo.importPhoto(mockk(), target)

        verify(timeout = 3000) {
            mediaGallery.publishFile(stored, any(), now, any(), any(), target.name, any())
        }
    }

    @Test
    fun importPhoto_nameAlreadyTaken_usesASuffixedName_andNeverReusesTheFile() = runTest {
        // BUG-019: a same-second burst shot must get its own file.
        val now = System.currentTimeMillis() / 1000 * 1000   // EXIF-style, ms = 0
        stubPassingImport(now)
        every { files.exists(any()) } returnsMany listOf(true, false)

        repo.importPhoto(mockk(), target)

        verify { files.copyFromUri(match { it.endsWith("_0_2.jpg") }, any(), false) }
        verify(exactly = 0) { files.copyFromUri(any(), any(), true) }
    }

    @Test
    fun importPhoto_heifSource_isTranscodedToJpeg() = runTest {
        // BUG-045: HEIC bytes must not be stored under a .jpg name.
        stubPassingImport(System.currentTimeMillis())
        val heifHeader = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(4)
        every { resolver.openInputStream(any()) } answers { java.io.ByteArrayInputStream(heifHeader) }

        assertTrue(repo.importPhoto(mockk(), target) is ImportResult.Success)
        verify { files.transcodeToJpeg(any(), any()) }
        verify(exactly = 0) { files.copyFromUri(any(), any(), any()) }
    }

    // ── storage full (BUG-049) ───────────────────────────────────────────────

    @Test
    fun savePhoto_withNoFreeSpace_isStorageFull_andWritesNothing() = runTest {
        every { context.filesDir } returns mockk<File> { every { usableSpace } returns 1024L }
        assertEquals(SaveResult.StorageFull, repo.savePhoto(byteArrayOf(1, 2, 3), target))
        verify(exactly = 0) { files.saveBytes(any(), any()) }
    }

    // ── rotate (BUG-059, BUG-060) ────────────────────────────────────────────

    @Test
    fun rotatePhoto_privateFile_succeeds_andMirrorsOrientationToTheAlbumCopy() = runTest {
        val photo = row(System.currentTimeMillis(), size = null)
            .copy(filePath = "/data/files/photos/ColorWalk_20260922_222000_0.jpg")

        assertTrue(repo.rotatePhoto(photo))
        verify { mediaGallery.writeOrientation("ColorWalk_20260922_222000_0.jpg", any()) }
    }

    @Test
    fun rotatePhoto_unresolvableLegacyRow_reportsFailure() = runTest {
        // content:// row whose bytes are gone: nothing was rotated, and the UI must
        // be told instead of the old silent no-op.
        every { files.copyFromUri(any(), any(), any()) } returns null
        val legacy = row(System.currentTimeMillis(), size = null)
            .copy(filePath = "content://media/external/images/media/42")
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)

        assertEquals(false, repo.rotatePhoto(legacy))
        verify(exactly = 0) { files.editExif(any(), any()) }
    }

    @Test
    fun rotatePhoto_exifWriteFailure_reportsFailure_andLeavesTheAlbumCopyAlone() = runTest {
        every { files.editExif(any(), any()) } throws java.io.IOException("read-only")
        val photo = row(System.currentTimeMillis(), size = null)
            .copy(filePath = "/data/files/photos/ColorWalk_20260922_222000_0.jpg")

        assertEquals(false, repo.rotatePhoto(photo))
        verify(exactly = 0) { mediaGallery.writeOrientation(any(), any()) }
    }

    @Test
    fun savePhoto_dbInsertFailure_isStorageError_andDoesNotOrphanTheFile() = runTest {
        // BUG-046: a failed insert used to crash and leave the private file behind.
        every { files.decodeBounded(any()) } returns mockk<Bitmap>(relaxed = true)
        every { ColorValidator.validate(any(), any()) } returns validation(passed = true)
        val written = File.createTempFile("colorwalk", ".jpg")
        every { files.saveBytes(any(), any()) } returns written
        coEvery { dao.insert(any()) } throws android.database.sqlite.SQLiteFullException("disk full")

        assertEquals(SaveResult.StorageError, repo.savePhoto(byteArrayOf(1), target))
        assertTrue("private file must be deleted", !written.exists())
        verify(exactly = 0) { mediaGallery.publish(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
