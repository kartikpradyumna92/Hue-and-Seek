package com.colorwalk.app.data.repository

import com.colorwalk.app.data.repository.PhotoFileStore.Companion.MAX_DECODE_DIM
import com.colorwalk.app.data.repository.PhotoFileStore.Companion.calculateInSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-003: the decode cap must bound the LONGEST side for every sensor size. */
class PhotoFileStoreTest {

    private fun assertWithinCap(w: Int, h: Int) {
        val s = calculateInSampleSize(w, h)
        assertTrue("${w}x$h -> $s leaves longest side ${maxOf(w, h) / s}", maxOf(w, h) / s <= MAX_DECODE_DIM)
        if (s > 1) {
            assertTrue("${w}x$h -> $s is not the smallest valid sample", maxOf(w, h) / (s / 2) > MAX_DECODE_DIM)
        }
    }

    @Test
    fun smallImage_decodesAtFullSize() {
        assertEquals(1, calculateInSampleSize(1600, 1200))
        assertEquals(1, calculateInSampleSize(MAX_DECODE_DIM, MAX_DECODE_DIM))
    }

    @Test
    fun fiftyMegapixel_isDownsampled() {
        // Old logic returned 1 here (short side < 8192) -> ~200 MB ARGB decode.
        assertEquals(4, calculateInSampleSize(8160, 6120))
        assertEquals(4, calculateInSampleSize(6120, 8160)) // portrait
    }

    @Test
    fun twoHundredMegapixel_isDownsampled() {
        assertEquals(8, calculateInSampleSize(16320, 12240))
    }

    @Test
    fun panorama_isBoundedByLongestSide() {
        // Old logic: short side 2000 < 8192 -> no downsampling of a 20000 px panorama.
        assertWithinCap(20000, 2000)
    }

    @Test
    fun commonSensorSizes_alwaysWithinCapAndMinimal() {
        listOf(
            4032 to 3024, 4000 to 3000, 8160 to 6120, 12000 to 9000,
            16320 to 12240, 20000 to 2000, 2049 to 100, 3000 to 4000
        ).forEach { (w, h) -> assertWithinCap(w, h) }
    }

    @Test
    fun failedBoundsDecode_fallsBackToOne() {
        // BitmapFactory reports -1 when it can't read the header.
        assertEquals(1, calculateInSampleSize(-1, -1))
    }
}
