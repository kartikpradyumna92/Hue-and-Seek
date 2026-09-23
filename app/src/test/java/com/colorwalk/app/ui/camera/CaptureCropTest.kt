package com.colorwalk.app.ui.camera

import com.colorwalk.app.data.repository.NormalizedCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BUG-014: the preview's viewport crop, as handed to validation. */
class CaptureCropTest {

    @Test
    fun fullFrameCrop_meansValidateTheWholeImage() {
        assertNull(normalizedCropOf(0, 0, 4000, 3000, 4000, 3000))
    }

    @Test
    fun tallScreenCenterCrop_isExpressedAsFractions() {
        // A 20:9 portrait preview over a 4:3 sensor keeps the middle ~34% of the
        // long (x, unrotated) axis.
        val crop = normalizedCropOf(1330, 0, 2670, 3000, 4000, 3000)
        assertEquals(NormalizedCrop(1330f / 4000, 0f, 2670f / 4000, 1f), crop)
    }

    @Test
    fun cropOutsideTheImage_isClamped() {
        val crop = normalizedCropOf(-10, -10, 2000, 5000, 4000, 3000)!!
        assertEquals(0f, crop.left, 0f)
        assertEquals(0f, crop.top, 0f)
        assertEquals(0.5f, crop.right, 0f)
        assertEquals(1f, crop.bottom, 0f)
    }

    @Test
    fun degenerateOrUnknownSizes_fallBackToWholeImage() {
        assertNull(normalizedCropOf(100, 100, 100, 200, 4000, 3000))   // zero width
        assertNull(normalizedCropOf(0, 0, 10, 10, 0, 0))              // unknown size
    }
}
