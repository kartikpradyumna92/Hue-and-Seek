package com.colorwalk.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * The app-private photo store under filesDir/photos — the single source of truth
 * for display (I-1: extracted from PhotoRepository). Owns writing (bytes, bitmaps,
 * URI copies) and the bounded decodes used for validation.
 */
internal class PhotoFileStore(private val context: Context) {

    internal companion object {
        private const val TAG = "PhotoFileStore"

        /**
         * Longest-side cap for validation decodes. ColorValidator samples down to
         * 80×80, so 2048 is ample; it bounds a decode to ~16 MB ARGB (~32 MB during
         * the EXIF-rotate copy) regardless of sensor size.
         */
        const val MAX_DECODE_DIM = 2048

        /** Longest-side cap when a non-JPEG import must be re-encoded for storage. */
        const val MAX_STORE_DIM = 4096

        private const val TMP_SUFFIX = ".tmp"

        /** Must match the cache-path in res/xml/file_paths.xml. */
        private const val SHARE_DIR = "shared"
        private const val SHARE_COPY_TTL_MS = 60L * 60 * 1000

        private val PRESERVED_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_IMAGE_DESCRIPTION
        )

        private val exifLock = Any()

        /** Smallest power-of-2 inSampleSize that brings the LONGEST side within [maxDim]. */
        fun calculateInSampleSize(width: Int, height: Int, maxDim: Int = MAX_DECODE_DIM): Int {
            val longest = maxOf(width, height)
            var inSampleSize = 1
            while (longest / inSampleSize > maxDim) inSampleSize *= 2
            return inSampleSize
        }
    }

    private fun photosDir(): File = File(context.filesDir, "photos").also { it.mkdirs() }

    /**
     * L-11: destination paths are always dir/filename with names that either come
     * from our own generator or from MediaStore DISPLAY_NAME. MediaStore forbids
     * path separators in practice, but a canonical-path containment check closes
     * the traversal hole outright.
     */
    private fun safeDestination(filename: String): File? {
        val dir = photosDir()
        val dest = File(dir, filename)
        return try {
            if (dest.canonicalPath.startsWith(dir.canonicalPath + File.separator)) dest else {
                Log.w(TAG, "Rejected unsafe filename: $filename")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Canonical path check failed for $filename", e)
            null
        }
    }

    fun exists(filename: String): Boolean = safeDestination(filename)?.exists() == true

    /**
     * BUG-026: every write lands in a sibling ".tmp" and is renamed into place, so an
     * interrupted write (process kill, full disk) can never leave a truncated file
     * under a final name — where the reuse checks below would trust it forever.
     */
    private fun writeAtomically(dest: File, write: (FileOutputStream) -> Unit): File? {
        val tmp = File(dest.parentFile, dest.name + TMP_SUFFIX)
        return try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.fd.sync()
            }
            if (tmp.length() > 0L && tmp.renameTo(dest)) dest else null
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Writes original JPEG bytes verbatim — full EXIF preserved, no re-encode (L-2). */
    fun saveBytes(jpegBytes: ByteArray, filename: String): File? = try {
        val file = safeDestination(filename) ?: throw IllegalArgumentException("unsafe filename")
        writeAtomically(file) { it.write(jpegBytes) }
    } catch (e: Exception) {
        // ERROR: this is the primary copy — failing here loses the capture.
        Log.e(TAG, "saveBytes failed for $filename", e)
        null
    }

    /** Fallback save via re-encode — used only when the source bytes are unreadable. */
    fun saveBitmap(bitmap: Bitmap, filename: String): File? = try {
        val file = safeDestination(filename) ?: throw IllegalArgumentException("unsafe filename")
        writeAtomically(file) { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    } catch (e: Exception) {
        Log.e(TAG, "saveBitmap failed for $filename", e)
        null
    }

    /**
     * Copies a content URI's bytes into the store; null when the URI can't be read.
     *
     * With [reuseExisting] (recovery/migration of a photo we already own), a file
     * already present under [filename] is returned as-is — unless the source's size
     * is known and differs, in which case it's a leftover partial copy and is
     * replaced (BUG-026). Imports pass false: an existing file there belongs to a
     * DIFFERENT photo and must never be handed back for a new row (BUG-019).
     */
    fun copyFromUri(filename: String, uri: Uri, reuseExisting: Boolean = true): File? {
        return try {
            val dest = safeDestination(filename) ?: return null
            if (dest.exists() && dest.length() > 0L) {
                if (!reuseExisting) return null
                val sourceSize = try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                } catch (_: Exception) { -1L }
                if (sourceSize <= 0L || sourceSize == dest.length()) return dest
                Log.w(TAG, "Replacing partial copy of $filename (${dest.length()} of $sourceSize bytes)")
            }
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { src -> writeAtomically(dest) { src.copyTo(it) } }
        } catch (e: Exception) {
            Log.w(TAG, "URI copy to private storage failed for $filename", e)
            null
        }
    }

    /**
     * BUG-045: stores a non-JPEG import (HEIC/PNG/WebP) as a real JPEG. Everything
     * downstream — EXIF notes and rotation, the gallery copy's provenance tags,
     * share MIME, decoding on API 26-27 — assumes JPEG, and the old ".jpg"-named
     * HEIC silently broke all of them. Decodes with a [MAX_STORE_DIM] longest-side
     * cap (no pixel rotation — the orientation tag is carried over instead), then
     * copies the capture metadata that matters (time, offset, GPS, orientation).
     */
    fun transcodeToJpeg(uri: Uri, filename: String): File? = try {
        val dest = safeDestination(filename) ?: throw IllegalArgumentException("unsafe filename")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, MAX_STORE_DIM)
        opts.inJustDecodeBounds = false
        val bmp = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) null else {
            val out = try {
                writeAtomically(dest) { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } finally {
                bmp.recycle()
            }
            out?.also { copyCaptureMetadata(uri, it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "transcodeToJpeg failed for $uri", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "transcodeToJpeg ran out of memory for $uri", e)
        null
    }

    private fun copyCaptureMetadata(source: Uri, target: File) {
        try {
            val src = context.contentResolver.openInputStream(source)?.use { ExifInterface(it) } ?: return
            editExif(target.absolutePath) { dst ->
                for (tag in PRESERVED_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            }
        } catch (e: Exception) {
            // The JPEG is still valid; only its capture metadata is missing.
            Log.w(TAG, "EXIF carry-over failed for ${target.name}", e)
        }
    }

    /**
     * BUG-031: EXIF edits on the ONLY copy of a photo. Serialized (a note save and a
     * rotate used to rewrite the same file concurrently) and atomic: the edit is made
     * on a temp copy that replaces the original only once fully written, so a crash
     * mid-save can't truncate the photo.
     */
    fun editExif(path: String, edit: (ExifInterface) -> Unit) {
        synchronized(exifLock) {
            val file = File(path)
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            try {
                file.copyTo(tmp, overwrite = true)
                ExifInterface(tmp.absolutePath).apply {
                    edit(this)
                    saveAttributes()
                }
                if (!tmp.renameTo(file)) throw java.io.IOException("rename failed for ${file.name}")
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    /**
     * BUG-062: a copy of [source] with every GPS tag removed, in cacheDir/shared/
     * (the only other FileProvider root), for the share sheet. Returns [source]
     * itself when it carries no location to begin with, and null — fail closed —
     * when location is present but can't be removed (e.g. a legacy non-JPEG row).
     */
    fun locationFreeShareCopy(source: File): File? {
        val hasLocation = try {
            ExifLocation.GPS_TAGS.any { ExifInterface(source.absolutePath).getAttribute(it) != null }
        } catch (_: Exception) { true }   // unreadable: assume the worst
        if (!hasLocation) return source

        val dir = File(context.cacheDir, SHARE_DIR).also { it.mkdirs() }
        // Earlier share copies: the receiving app has long since read them.
        val cutoff = System.currentTimeMillis() - SHARE_COPY_TTL_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }

        val copy = File(dir, source.name)
        return try {
            source.copyTo(copy, overwrite = true)
            ExifInterface(copy.absolutePath).apply {
                ExifLocation.strip(this)
                saveAttributes()
            }
            copy
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't strip location for sharing ${source.name}; not sharing", e)
            copy.delete()
            null
        }
    }

    /** Removes temp files left behind by a write interrupted by process death. */
    fun sweepTempFiles() {
        photosDir().listFiles { f -> f.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
    }

    /** Bounded decode of an in-memory JPEG — longest side capped so 50MP+ sensors can't OOM (H-3). */
    fun decodeBounded(jpegBytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
    } catch (e: Exception) {
        Log.e(TAG, "Captured JPEG failed to decode", e)
        null
    } catch (e: OutOfMemoryError) {
        // Not an Exception — without this the whole app crashes (BUG-003).
        Log.e(TAG, "Captured JPEG decode ran out of memory", e)
        null
    }

    /**
     * Bounded decode from a URI (imports, C2), EXIF-rotated so validation sees the
     * upright frame. Two-pass: dimensions first, then inSampleSize decode.
     */
    fun decodeBoundedFromUri(uri: Uri): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight)
        opts.inJustDecodeBounds = false

        var bmp = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = try {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            } finally {
                bmp.recycle()   // also on OOM — don't hold the source while unwinding
            }
            bmp = rotated
        }
        bmp
    } catch (e: Exception) {
        // Surfaces to the user as StorageError — keep the cause findable.
        Log.w(TAG, "decodeBoundedFromUri failed for $uri", e)
        null
    } catch (e: OutOfMemoryError) {
        // Not an Exception — without this the whole app crashes (BUG-003).
        Log.e(TAG, "decodeBoundedFromUri ran out of memory for $uri", e)
        null
    }

}
