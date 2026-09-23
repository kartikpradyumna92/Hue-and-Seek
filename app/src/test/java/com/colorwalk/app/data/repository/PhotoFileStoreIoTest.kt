package com.colorwalk.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** Storage write-path guarantees on real files (BUG-019, BUG-026). */
class PhotoFileStoreIoTest {

    private lateinit var filesDir: File
    private lateinit var resolver: ContentResolver
    private lateinit var store: PhotoFileStore
    private val uri = mockk<Uri>()

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("colorwalk-files").toFile()
        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true) {
            every { this@mockk.filesDir } returns this@PhotoFileStoreIoTest.filesDir
            every { contentResolver } returns resolver
        }
        store = PhotoFileStore(context)
    }

    @After
    fun tearDown() { filesDir.deleteRecursively() }

    private fun photos() = File(filesDir, "photos")

    private fun stubSource(bytes: ByteArray, statSize: Long = bytes.size.toLong()) {
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        every { resolver.openFileDescriptor(uri, "r") } returns mockk<ParcelFileDescriptor>(relaxed = true) {
            every { this@mockk.statSize } returns statSize
        }
    }

    @Test
    fun saveBytes_writesContent_andLeavesNoTempFile() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = store.saveBytes(bytes, "ColorWalk_a.jpg")!!
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(photos().listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun copyFromUri_withoutReuse_neverHandsBackAnotherPhotosFile() {
        store.saveBytes(byteArrayOf(9, 9, 9), "ColorWalk_x.jpg")
        stubSource(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(store.copyFromUri("ColorWalk_x.jpg", uri, reuseExisting = false))
        assertArrayEquals("existing photo untouched", byteArrayOf(9, 9, 9), File(photos(), "ColorWalk_x.jpg").readBytes())
    }

    @Test
    fun copyFromUri_withReuse_keepsACompleteExistingCopy() {
        val existing = store.saveBytes(byteArrayOf(1, 2, 3), "ColorWalk_y.jpg")!!
        stubSource(byteArrayOf(1, 2, 3))
        val result = store.copyFromUri("ColorWalk_y.jpg", uri)
        assertEquals(existing.absolutePath, result!!.absolutePath)
    }

    @Test
    fun copyFromUri_withReuse_replacesATruncatedLeftover() {
        // A partial copy from an interrupted earlier attempt (pre-fix) — 2 of 5 bytes.
        photos().mkdirs()
        File(photos(), "ColorWalk_z.jpg").writeBytes(byteArrayOf(1, 2))
        val full = byteArrayOf(1, 2, 3, 4, 5)
        stubSource(full)
        val result = store.copyFromUri("ColorWalk_z.jpg", uri)!!
        assertArrayEquals(full, result.readBytes())
    }

    @Test
    fun copyFromUri_unreadableSource_leavesNoPartialFile() {
        every { resolver.openInputStream(uri) } answers {
            object : java.io.InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 3) 7 else throw java.io.IOException("source died")
            }
        }
        assertNull(store.copyFromUri("ColorWalk_w.jpg", uri, reuseExisting = false))
        assertFalse(File(photos(), "ColorWalk_w.jpg").exists())
        assertTrue(photos().listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun sweepTempFiles_removesOnlyTempFiles() {
        val kept = store.saveBytes(byteArrayOf(1), "ColorWalk_k.jpg")!!
        File(photos(), "ColorWalk_k.jpg.tmp").writeBytes(byteArrayOf(0))
        store.sweepTempFiles()
        assertTrue(kept.exists())
        assertFalse(File(photos(), "ColorWalk_k.jpg.tmp").exists())
    }

    @Test
    fun exists_reflectsStoredFiles() {
        assertFalse(store.exists("ColorWalk_e.jpg"))
        store.saveBytes(byteArrayOf(1), "ColorWalk_e.jpg")
        assertTrue(store.exists("ColorWalk_e.jpg"))
    }
}
