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
        context = mockk(relaxed = true) { every { contentResolver } returns resolver }
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
        dateTaken = dateTaken, latitude = null, longitude = null, locationName = null,
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

        assertEquals(ImportResult.StorageError, repo.importPhoto(mockk(), target))
        verify { files.decodeBoundedFromUri(any()) } // proof the gate was passed
    }
}
