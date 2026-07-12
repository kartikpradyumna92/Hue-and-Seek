package com.colorwalk.app.data.repository

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * I-6: the sync's filename-date recovery — the fallback identity for MediaStore
 * rows whose DATE_TAKEN was lost. A wrong parse here either duplicates a photo
 * (dedup miss) or invents one on the wrong day. (The full three-pass sync itself
 * needs MediaStore and remains instrumented-territory; its DB-side dedup guards
 * are covered by PhotoDaoTest.)
 */
class GallerySynchronizerTest {

    // Collaborators are untouched by parseDateFromFilename — inert mocks suffice.
    private val sync = GallerySynchronizer(
        context = mockk(relaxed = true),
        dao = mockk(relaxed = true),
        db = mockk(relaxed = true),
        files = PhotoFileStore(mockk(relaxed = true))
    )

    private fun millisOf(pattern: String, value: String): Long =
        SimpleDateFormat(pattern, Locale.US).parse(value)!!.time

    @Test
    fun modernFilename_withMillisSuffix_parsesToExactMillis() {
        val expected = millisOf("yyyyMMdd_HHmmss", "20260711_183045") + 123
        assertEquals(expected, sync.parseDateFromFilename("ColorWalk_20260711_183045_123.jpg"))
    }

    @Test
    fun legacyFilename_withoutMillisSuffix_parsesToSecondPrecision() {
        val expected = millisOf("yyyyMMdd_HHmmss", "20260711_183045")
        assertEquals(expected, sync.parseDateFromFilename("ColorWalk_20260711_183045.jpg"))
    }

    @Test
    fun millisSuffixOutOfRange_isIgnoredNotAdded() {
        val expected = millisOf("yyyyMMdd_HHmmss", "20260711_183045")
        assertEquals(expected, sync.parseDateFromFilename("ColorWalk_20260711_183045_5000.jpg"))
    }

    @Test
    fun impossibleCalendarDate_isRejected_notRolledOver() {
        // L-4: lenient parsing would roll month 13 into January of the next year.
        assertNull(sync.parseDateFromFilename("ColorWalk_20261340_183045.jpg"))
    }

    @Test
    fun foreignNameWithColorWalkPrefix_isRejected() {
        assertNull(sync.parseDateFromFilename("ColorWalk_edited_copy.jpg"))
        assertNull(sync.parseDateFromFilename("ColorWalk_.jpg"))
    }

    @Test
    fun unrelatedFilename_isRejected() {
        assertNull(sync.parseDateFromFilename("IMG_20260711_183045.jpg"))
    }
}
