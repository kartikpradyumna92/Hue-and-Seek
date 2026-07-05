package com.colorwalk.app.domain

/**
 * Forgery checks for imported photos — pure Kotlin so every rule is JVM-testable.
 *
 * The import flow already rejects photos with no date or the wrong date. This layer
 * catches photos whose date *looks* right but was manufactured:
 *
 *  1. **Container sniffing** ([sniffFormat]): the first bytes of the file must carry a
 *     real image magic number (JPEG/PNG/WebP/HEIF). A renamed or hand-assembled file
 *     whose header matches no known container is rejected outright — extension and
 *     MIME type are attacker-controlled, leading bytes are not.
 *
 *  2. **Internal timestamp consistency** ([evaluate]): in-camera files write
 *     DateTimeOriginal (shutter press) and DateTimeDigitized (sensor readout) within
 *     moments of each other. Casual EXIF editors typically rewrite only
 *     DateTimeOriginal, leaving the pair disagreeing by hours or years.
 *
 *  3. **Physical ordering**: a file cannot have been *last modified* before it was
 *     *captured*. Copying/moving a real photo keeps or advances the filesystem
 *     mtime, so mtime ≥ capture (minus clock-skew tolerance) always holds for
 *     genuine files — while backdating-resistant mtime exposes an old file whose
 *     EXIF was edited forward to today.
 *
 *  4. **Future captures**: a capture time meaningfully ahead of the device clock is
 *     impossible.
 *
 * Every check degrades gracefully: a missing field simply skips its rule (real-world
 * photos legitimately lack fields — screenshots, messaging apps strip EXIF — and
 * those already fail the separate no-date gate).
 */
object ExifIntegrity {

    /** Recognized image containers, by leading magic bytes. */
    enum class Format { JPEG, PNG, WEBP, HEIF }

    sealed class Verdict {
        object Ok : Verdict()
        data class Tampered(val reason: String) : Verdict()
    }

    /** Original and digitized clocks may legitimately differ by burst/HDR latency. */
    internal const val ORIGINAL_VS_DIGITIZED_TOLERANCE_MS = 2L * 60 * 1000

    /** Clock-skew allowance between the camera clock and the filesystem clock. */
    internal const val MTIME_BEFORE_CAPTURE_TOLERANCE_MS = 60L * 60 * 1000

    /** Device clocks drift; a capture this far in the future cannot be genuine. */
    internal const val FUTURE_CAPTURE_TOLERANCE_MS = 10L * 60 * 1000

    /**
     * Identifies the container from the file's leading bytes, or null when they match
     * no known image format. Callers should treat null as a spoofed/corrupt file.
     * Requires at least 12 bytes for a reliable verdict.
     */
    fun sniffFormat(header: ByteArray): Format? {
        if (header.size < 12) return null
        fun b(i: Int) = header[i].toInt() and 0xFF

        // JPEG: FF D8 FF
        if (b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return Format.JPEG

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 &&
            b(4) == 0x0D && b(5) == 0x0A && b(6) == 0x1A && b(7) == 0x0A
        ) return Format.PNG

        // WebP: "RIFF" .... "WEBP"
        if (b(0) == 'R'.code && b(1) == 'I'.code && b(2) == 'F'.code && b(3) == 'F'.code &&
            b(8) == 'W'.code && b(9) == 'E'.code && b(10) == 'B'.code && b(11) == 'P'.code
        ) return Format.WEBP

        // HEIF/HEIC/AVIF family: ISO-BMFF "ftyp" box at offset 4.
        if (b(4) == 'f'.code && b(5) == 't'.code && b(6) == 'y'.code && b(7) == 'p'.code)
            return Format.HEIF

        return null
    }

    /**
     * Cross-checks the photo's claimed capture time against its other timestamps.
     *
     * @param captureMillis      claimed capture time (EXIF DateTimeOriginal or MediaStore DATE_TAKEN)
     * @param digitizedMillis    EXIF DateTimeDigitized, when present
     * @param fileModifiedMillis filesystem/MediaStore last-modified time, when known (≤0 = unknown)
     * @param nowMillis          current device time
     */
    fun evaluate(
        captureMillis: Long,
        digitizedMillis: Long?,
        fileModifiedMillis: Long,
        nowMillis: Long
    ): Verdict {
        if (captureMillis > nowMillis + FUTURE_CAPTURE_TOLERANCE_MS) {
            return Verdict.Tampered("capture time is in the future")
        }
        if (digitizedMillis != null &&
            kotlin.math.abs(captureMillis - digitizedMillis) > ORIGINAL_VS_DIGITIZED_TOLERANCE_MS
        ) {
            return Verdict.Tampered("original and digitized timestamps disagree")
        }
        if (fileModifiedMillis > 0 &&
            fileModifiedMillis < captureMillis - MTIME_BEFORE_CAPTURE_TOLERANCE_MS
        ) {
            return Verdict.Tampered("file predates its claimed capture time")
        }
        return Verdict.Ok
    }
}
