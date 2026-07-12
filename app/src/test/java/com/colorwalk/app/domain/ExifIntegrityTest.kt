package com.colorwalk.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forgery-detection rules for imported photos: container magic-byte sniffing and
 * timestamp cross-validation. All pure Kotlin — these are the exact rules the import
 * path runs, exercised on the JVM.
 */
class ExifIntegrityTest {

    // ── container sniffing ────────────────────────────────────────────────────

    private fun bytes(vararg v: Int) = ByteArray(16) { i -> (v.getOrElse(i) { 0 }).toByte() }

    @Test
    fun sniff_jpegMagic_isJpeg() {
        assertEquals(
            ExifIntegrity.Format.JPEG,
            ExifIntegrity.sniffFormat(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46))
        )
    }

    @Test
    fun sniff_pngMagic_isPng() {
        assertEquals(
            ExifIntegrity.Format.PNG,
            ExifIntegrity.sniffFormat(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        )
    }

    @Test
    fun sniff_webpMagic_isWebp() {
        val header = ByteArray(16)
        "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
        assertEquals(ExifIntegrity.Format.WEBP, ExifIntegrity.sniffFormat(header))
    }

    @Test
    fun sniff_heicFtypBox_isHeif() {
        val header = ByteArray(16)
        header[3] = 0x18 // box size
        "ftypheic".forEachIndexed { i, c -> header[4 + i] = c.code.toByte() }
        assertEquals(ExifIntegrity.Format.HEIF, ExifIntegrity.sniffFormat(header))
    }

    @Test
    fun sniff_mp4VideoFtypBrands_areRejected() {
        // L-5: a bare 'ftyp' box is also how MP4/MOV video starts — only known
        // HEIF-image brands may sniff as images.
        for (videoBrand in listOf("isom", "mp42", "qt  ")) {
            val header = ByteArray(16)
            "ftyp$videoBrand".forEachIndexed { i, c -> header[4 + i] = c.code.toByte() }
            assertNull(
                "'$videoBrand' is a video brand and must not sniff as HEIF",
                ExifIntegrity.sniffFormat(header)
            )
        }
    }

    @Test
    fun sniff_avifFtypBox_isHeif() {
        val header = ByteArray(16)
        "ftypavif".forEachIndexed { i, c -> header[4 + i] = c.code.toByte() }
        assertEquals(ExifIntegrity.Format.HEIF, ExifIntegrity.sniffFormat(header))
    }

    @Test
    fun sniff_textFileRenamedToJpg_isRejected() {
        // "Hello world…" bytes — no image container regardless of what the filename claims.
        val header = "Hello world, not an image".toByteArray().copyOf(16)
        assertNull(ExifIntegrity.sniffFormat(header))
    }

    @Test
    fun sniff_truncatedHeader_isRejected() {
        assertNull(ExifIntegrity.sniffFormat(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    // ── timestamp cross-validation ────────────────────────────────────────────

    private val NOON = 1_750_000_000_000L // arbitrary fixed "now"
    private val HOUR = 60L * 60 * 1000
    private val MIN = 60L * 1000

    @Test
    fun evaluate_cleanCameraFile_isOk() {
        // Shot an hour ago; digitized within a second; file written moments after capture.
        val capture = NOON - HOUR
        val verdict = ExifIntegrity.evaluate(
            captureMillis = capture,
            digitizedMillis = capture + 800,
            fileModifiedMillis = capture + 3_000,
            nowMillis = NOON
        )
        assertEquals(ExifIntegrity.Verdict.Ok, verdict)
    }

    @Test
    fun evaluate_editedOriginalLeavesDigitizedBehind_isTampered() {
        // The classic EXIF-editor forgery: DateTimeOriginal rewritten to "today",
        // DateTimeDigitized still carrying last month's real timestamp.
        val verdict = ExifIntegrity.evaluate(
            captureMillis = NOON - HOUR,
            digitizedMillis = NOON - 30 * 24 * HOUR,
            fileModifiedMillis = NOON,
            nowMillis = NOON
        )
        assertTrue(verdict is ExifIntegrity.Verdict.Tampered)
    }

    @Test
    fun evaluate_fileOlderThanClaimedCapture_isTampered() {
        // File last written a year ago but "captured" an hour ago — impossible:
        // an old photo whose EXIF was pushed forward to pass the today check.
        val verdict = ExifIntegrity.evaluate(
            captureMillis = NOON - HOUR,
            digitizedMillis = null,
            fileModifiedMillis = NOON - 365 * 24 * HOUR,
            nowMillis = NOON
        )
        assertTrue(verdict is ExifIntegrity.Verdict.Tampered)
    }

    @Test
    fun evaluate_captureInTheFuture_isTampered() {
        val verdict = ExifIntegrity.evaluate(
            captureMillis = NOON + 2 * HOUR,
            digitizedMillis = null,
            fileModifiedMillis = 0,
            nowMillis = NOON
        )
        assertTrue(verdict is ExifIntegrity.Verdict.Tampered)
    }

    @Test
    fun evaluate_burstLatencyBetweenOriginalAndDigitized_isOk() {
        // HDR/burst pipelines can digitize tens of seconds after the shutter — within
        // tolerance, never flagged.
        val capture = NOON - HOUR
        val verdict = ExifIntegrity.evaluate(
            captureMillis = capture,
            digitizedMillis = capture + 45_000,
            fileModifiedMillis = capture + MIN,
            nowMillis = NOON
        )
        assertEquals(ExifIntegrity.Verdict.Ok, verdict)
    }

    @Test
    fun evaluate_clockSkewBetweenCameraAndFilesystem_isOk() {
        // mtime 20 minutes BEFORE capture: inside the 1-hour skew tolerance (camera
        // clock ahead of the filesystem clock), must not be flagged.
        val capture = NOON - HOUR
        val verdict = ExifIntegrity.evaluate(
            captureMillis = capture,
            digitizedMillis = null,
            fileModifiedMillis = capture - 20 * MIN,
            nowMillis = NOON
        )
        assertEquals(ExifIntegrity.Verdict.Ok, verdict)
    }

    @Test
    fun evaluate_missingOptionalFields_skipsTheirChecks() {
        // No digitized tag, unknown mtime (0): only the future check applies → Ok.
        val verdict = ExifIntegrity.evaluate(
            captureMillis = NOON - HOUR,
            digitizedMillis = null,
            fileModifiedMillis = 0,
            nowMillis = NOON
        )
        assertEquals(ExifIntegrity.Verdict.Ok, verdict)
    }

    @Test
    fun evaluate_smallFutureDrift_isOk() {
        // Device clocks drift a few minutes; a capture 5 min "ahead" is legitimate.
        val verdict = ExifIntegrity.evaluate(
            captureMillis = NOON + 5 * MIN,
            digitizedMillis = null,
            fileModifiedMillis = 0,
            nowMillis = NOON
        )
        assertEquals(ExifIntegrity.Verdict.Ok, verdict)
    }
}
