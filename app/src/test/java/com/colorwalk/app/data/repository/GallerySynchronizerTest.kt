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

    // ── dayIndexFromFilename (BUG-024) ───────────────────────────────────────
    // Expected values match MIGRATION_3_4's SQL (verified with sqlite3 during the
    // fix: 2026-09-22 -> 20718, 1970-01-01 -> 0, 2024-02-29 -> 19782).

    @Test
    fun dayIndex_isTheFilenamesCalendarDate_independentOfDeviceZone() {
        assertEquals(20718, sync.dayIndexFromFilename("ColorWalk_20260922_222000_123.jpg"))
        assertEquals(0, sync.dayIndexFromFilename("ColorWalk_19700101_000000_0.jpg"))
        assertEquals(19782, sync.dayIndexFromFilename("ColorWalk_20240229_120000_0.jpg"))
    }

    @Test
    fun dayIndex_acceptsTheBurstCollisionSuffix() {
        // BUG-019 names same-second imports "..._SSS_2.jpg".
        assertEquals(20718, sync.dayIndexFromFilename("ColorWalk_20260922_222000_0_2.jpg"))
    }

    @Test
    fun dayIndex_rejectsInvalidOrForeignNames() {
        assertNull(sync.dayIndexFromFilename("ColorWalk_20261340_120000_0.jpg"))
        assertNull(sync.dayIndexFromFilename("ColorWalk_edited_copy.jpg"))
        assertNull(sync.dayIndexFromFilename("photo_17.jpg"))
        assertNull(sync.dayIndexFromFilename("IMG_20260922_222000.jpg"))
    }

    @Test
    fun burstSuffix_doesNotChangeTheParsedTimestamp() {
        assertEquals(
            sync.parseDateFromFilename("ColorWalk_20260711_183045_0.jpg"),
            sync.parseDateFromFilename("ColorWalk_20260711_183045_0_2.jpg")
        )
    }
}
