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
 *
 * SCOPE (L-5): these heuristics are tamper-EVIDENT, not tamper-proof. They exist to
 * stop casual date editing (the realistic threat for a streak game); an editor that
 * rewrites DateTimeDigitized and touches the file mtime defeats them by design.
 * Do not present them to users as cryptographic verification.
 */
object ExifIntegrity {

    /**
     * ISO-BMFF brands that identify a HEIF-family IMAGE. A bare 'ftyp' box also
     * matches MP4/MOV video containers (brands like isom/mp42/qt) — those must NOT
     * sniff as images (L-5).
     */
    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis",   // HEVC-coded stills
        "hevc", "hevx", "hevm", "hevs",   // HEVC sequences
        "mif1", "msf1",                   // structural HEIF brands
        "avif", "avis"                    // AV1 image format
    )

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

        // HEIF/HEIC/AVIF family: ISO-BMFF "ftyp" box at offset 4, restricted to
        // known IMAGE brands — plain 'ftyp' alone would also accept MP4 video (L-5).
        if (b(4) == 'f'.code && b(5) == 't'.code && b(6) == 'y'.code && b(7) == 'p'.code) {
            val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
            return if (brand in HEIF_BRANDS) Format.HEIF else null
        }

        return null
    }

    /** Largest real-world UTC offset spread (UTC-12 … UTC+14 → at most 14 h either way). */
    private const val MAX_ZONE_SHIFT_MS = 14L * 60 * 60 * 1000
    /** Every real UTC offset is a multiple of 15 min (+5:30 India, +5:45 Nepal, …). */
    private const val ZONE_STEP_MS = 15L * 60 * 1000

    /**
     * Cross-checks the photo's claimed capture time against its other timestamps.
     *
     * BUG-017: the capture time can come from two sources that are each wrong in a
     * known, legitimate way — MediaStore DATE_TAKEN (some OEMs store local-naive
     * millis, shifted by the UTC offset) and the zone-less EXIF DateTimeOriginal
     * (parsed in the device's zone, shifted by any travel since). A genuine photo
     * has at least one right; a forged date is wrong in both, because MediaStore
     * derives DATE_TAKEN from the (edited) EXIF. So each check fails only when EVERY
     * available source fails it, and an original-vs-digitized gap shaped like a UTC
     * offset (a multiple of 15 min, ≤ 14 h) is a timezone artifact, not an edit.
     *
     * @param captureMillis      claimed capture time (MediaStore DATE_TAKEN, or EXIF when absent)
     * @param digitizedMillis    EXIF DateTimeDigitized, when present
     * @param fileModifiedMillis filesystem/MediaStore last-modified time, when known (≤0 = unknown)
     * @param nowMillis          current device time
     * @param exifOriginalMillis EXIF DateTimeOriginal parsed the same way as digitized, when present
     */
    fun evaluate(
        captureMillis: Long,
        digitizedMillis: Long?,
        fileModifiedMillis: Long,
        nowMillis: Long,
        exifOriginalMillis: Long? = null
    ): Verdict {
        val sources = listOfNotNull(captureMillis, exifOriginalMillis)
        val earliest = sources.min()
        if (earliest > nowMillis + FUTURE_CAPTURE_TOLERANCE_MS) {
            return Verdict.Tampered("capture time is in the future")
        }
        if (digitizedMillis != null && sources.none { consistent(it, digitizedMillis) }) {
            return Verdict.Tampered("original and digitized timestamps disagree")
        }
        if (fileModifiedMillis > 0 &&
            fileModifiedMillis < earliest - MTIME_BEFORE_CAPTURE_TOLERANCE_MS
        ) {
            return Verdict.Tampered("file predates its claimed capture time")
        }
        return Verdict.Ok
    }

    /** Within burst tolerance, allowing a timezone-shaped shift (15-min steps, ≤ 14 h). */
    private fun consistent(a: Long, b: Long): Boolean {
        val diff = kotlin.math.abs(a - b)
        if (diff <= ORIGINAL_VS_DIGITIZED_TOLERANCE_MS) return true
        if (diff > MAX_ZONE_SHIFT_MS + ORIGINAL_VS_DIGITIZED_TOLERANCE_MS) return false
        val offStep = diff % ZONE_STEP_MS
        return offStep <= ORIGINAL_VS_DIGITIZED_TOLERANCE_MS ||
            ZONE_STEP_MS - offStep <= ORIGINAL_VS_DIGITIZED_TOLERANCE_MS
    }
}
