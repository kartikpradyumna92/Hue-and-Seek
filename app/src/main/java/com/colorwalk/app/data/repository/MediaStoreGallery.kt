package com.colorwalk.app.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.colorwalk.app.domain.PhotoProvenance
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's public Pictures/ColorWalk album in MediaStore (I-1: extracted from
 * PhotoRepository). Everything here is best-effort — the app displays from private
 * storage; this copy exists for Google Photos visibility and reinstall recovery.
 */
internal class MediaStoreGallery(private val context: Context) {

    private companion object { const val TAG = "MediaStoreGallery" }

    /**
     * Publishes the original JPEG bytes (L-2: full EXIF, no re-encode) with
     * provenance (M-9), GPS when available, and the selfie mirror flag (L-3).
     */
    fun publish(
        jpegBytes: ByteArray,
        filename: String,
        now: Long,
        lat: Double?,
        lon: Double?,
        colorName: String,
        dominantHex: String,
        mirrorHorizontally: Boolean = false
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ColorWalk")
                    put(MediaStore.Images.Media.DATE_TAKEN, now)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
                resolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    exif.setAttribute(
                        ExifInterface.TAG_USER_COMMENT,
                        PhotoProvenance.encode(colorName, dominantHex)
                    )
                    if (lat != null && lon != null) writeGpsExif(exif, lat, lon)
                    if (mirrorHorizontally) exif.flipHorizontally()
                    exif.saveAttributes()
                }
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ColorWalk")
                dir.mkdirs()
                val file = File(dir, filename)
                file.writeBytes(jpegBytes)
                val exif = ExifInterface(file.absolutePath)
                // HAL JPEGs normally carry their own capture time; only backfill it
                // when absent so the media scanner still indexes a correct DATE_TAKEN.
                if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                    val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(now))
                    exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                }
                exif.setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    PhotoProvenance.encode(colorName, dominantHex)
                )
                if (lat != null && lon != null) writeGpsExif(exif, lat, lon)
                if (mirrorHorizontally) exif.flipHorizontally() // L-3: selfie as previewed
                exif.saveAttributes()
                // Without an explicit scan the file is invisible to the system gallery
                // and to our own MediaStore-based sync/delete queries (A5).
                MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null
                )
            }
        } catch (e: Exception) {
            // Never block the capture flow (M-8: logged, was silent).
            Log.w(TAG, "publish failed for $filename", e)
        }
    }

    /**
     * Writes [description] into the album copy's EXIF ImageDescription so Google
     * Photos sees note edits. No-op if the entry doesn't exist or isn't writable.
     */
    fun writeDescription(filename: String, description: String) {
        val resolver = context.contentResolver
        // M-6: DISPLAY_NAME alone is only unique per directory — constrain to the
        // app's own album so a same-named copy elsewhere is never modified.
        val (selection, args) = albumSelection(filename)
        val cursor = try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                args,
                null
            )
        } catch (_: Exception) { return }

        val uri = cursor?.use { c ->
            if (c.moveToFirst())
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            else null
        } ?: return

        try {
            // IS_PENDING must be set to 1 before editing on API 29+ to get write access.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pending = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }
                resolver.update(uri, pending, null, null)
            }
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "description write failed for $filename", e)
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val notPending = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                try { resolver.update(uri, notPending, null, null) } catch (_: Exception) { }
            }
        }
    }

    /** Removes the album copy so sync doesn't re-insert a deleted photo. */
    fun delete(filename: String) {
        if (!filename.startsWith("ColorWalk_")) return
        try {
            // M-6: only rows inside Pictures/ColorWalk are ours to remove.
            val (selection, args) = albumSelection(filename)
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                selection,
                args
            )
        } catch (e: Exception) {
            // Expected for rows owned by a previous install (tombstones cover those).
            Log.d(TAG, "delete failed for $filename: $e")
        }
    }

    /**
     * Selection matching [filename] ONLY within the app's Pictures/ColorWalk album
     * (M-6). RELATIVE_PATH exists from API 29; older devices match on the DATA path.
     */
    private fun albumSelection(filename: String): Pair<String, Array<String>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to
                arrayOf(filename, "${Environment.DIRECTORY_PICTURES}/ColorWalk%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.DATA} LIKE ?" to
                arrayOf(filename, "%/${Environment.DIRECTORY_PICTURES}/ColorWalk/%")
        }

    private fun writeGpsExif(exif: ExifInterface, lat: Double, lon: Double) {
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDmsRational(Math.abs(lat)))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDmsRational(Math.abs(lon)))
    }

    private fun toDmsRational(decimal: Double): String {
        val deg = decimal.toInt()
        val minFull = (decimal - deg) * 60.0
        val min = minFull.toInt()
        val secNum = Math.round((minFull - min) * 60.0 * 1000).toInt()
        return "$deg/1,$min/1,$secNum/1000"
    }
}
