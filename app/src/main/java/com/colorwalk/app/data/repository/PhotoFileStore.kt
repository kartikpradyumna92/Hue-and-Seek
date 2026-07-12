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

    private companion object {
        const val TAG = "PhotoFileStore"
        const val MAX_DECODE_DIM = 4096
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

    /** Writes original JPEG bytes verbatim — full EXIF preserved, no re-encode (L-2). */
    fun saveBytes(jpegBytes: ByteArray, filename: String): File? = try {
        val file = safeDestination(filename) ?: throw IllegalArgumentException("unsafe filename")
        file.writeBytes(jpegBytes)
        file
    } catch (e: Exception) {
        // ERROR: this is the primary copy — failing here loses the capture.
        Log.e(TAG, "saveBytes failed for $filename", e)
        null
    }

    /** Fallback save via re-encode — used only when the source bytes are unreadable. */
    fun saveBitmap(bitmap: Bitmap, filename: String): File? = try {
        val file = safeDestination(filename) ?: throw IllegalArgumentException("unsafe filename")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        file
    } catch (e: Exception) {
        Log.e(TAG, "saveBitmap failed for $filename", e)
        null
    }

    /**
     * Copies a content URI's bytes into the store. Returns the existing file when it
     * is already present and non-empty; null when the URI can't be read.
     */
    fun copyFromUri(filename: String, uri: Uri): File? {
        return try {
            val dest = safeDestination(filename) ?: return null
            if (dest.exists() && dest.length() > 0L) return dest
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            if (dest.exists() && dest.length() > 0L) dest else null
        } catch (e: Exception) {
            Log.w(TAG, "URI copy to private storage failed for $filename", e)
            null
        }
    }

    /** Bounded decode of an in-memory JPEG — 4096px cap so 50MP+ sensors can't OOM (H-3). */
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
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle()
            bmp = rotated
        }
        bmp
    } catch (e: Exception) {
        // Surfaces to the user as StorageError — keep the cause findable.
        Log.w(TAG, "decodeBoundedFromUri failed for $uri", e)
        null
    }

    /** Largest power-of-2 inSampleSize keeping the decode within the cap. */
    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var inSampleSize = 1
        if (height > MAX_DECODE_DIM || width > MAX_DECODE_DIM) {
            val halfHeight = height / 2
            val halfWidth  = width / 2
            while (halfHeight / inSampleSize >= MAX_DECODE_DIM && halfWidth / inSampleSize >= MAX_DECODE_DIM) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
