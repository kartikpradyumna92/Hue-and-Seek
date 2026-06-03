package com.colorwalk.app.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.colorwalk.app.data.db.ColorSummary
import com.colorwalk.app.data.db.PhotoDao
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.WalkColor
import com.colorwalk.app.domain.colorForDay
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PhotoDao
) {
    fun getAllPhotos(): Flow<List<PhotoEntity>> = dao.getAllPhotos()
    fun getPhotosByColor(colorName: String): Flow<List<PhotoEntity>> = dao.getPhotosByColor(colorName)
    fun getDistinctColors(): Flow<List<ColorSummary>> = dao.getDistinctColors()

    suspend fun getStreak(): Int = withContext(Dispatchers.IO) {
        StreakCalculator.compute(dao.getRecentPhotoDates())
    }

    /** Day indices (local-tz) for all photos in the last 60 days — used by history strip and review check. */
    suspend fun getCapturedDayIndices(): Set<Int> = withContext(Dispatchers.IO) {
        dao.getRecentPhotoDates()
            .map { StreakCalculator.epochMillisToDayIndex(it) }
            .toHashSet()
    }

    suspend fun hasCapturedToday(): Boolean = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val midnight = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val tomorrowMidnight = cal.timeInMillis
        dao.getPhotoForDay(midnight, tomorrowMidnight) != null
    }

    /** Captures a photo. Only saves if color validation passes. */
    suspend fun savePhoto(bitmap: Bitmap, targetColor: WalkColor): SaveResult =
        withContext(Dispatchers.IO) {
            val validation = ColorValidator.validate(bitmap, targetColor)
            if (!validation.passed) return@withContext SaveResult.ValidationFailed(validation)

            val now = System.currentTimeMillis()
            // Include millis so two captures in the same second never share a filename.
            val filename = "ColorWalk_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))}_${now % 1000}.jpg"

            // Primary: save to app-private files dir — always readable without permissions.
            val privateFile = saveToPrivateStorage(bitmap, filename)
                ?: return@withContext SaveResult.StorageError

            // Secondary: also publish to the system gallery (best-effort; display doesn't depend on this).
            publishToSystemGallery(bitmap, filename, now)

            val (lat, lon) = getLastLocation()
            val locationName = reverseGeocode(lat, lon)
            dao.insert(
                PhotoEntity(
                    filePath = privateFile.absolutePath,   // absolute path — passed as File to Coil
                    colorName = targetColor.name,
                    colorHex = targetColor.hex,
                    dateTaken = now,
                    latitude = lat,
                    longitude = lon,
                    locationName = locationName,
                    dominantColorHex = validation.dominantHex
                )
            )
            SaveResult.Success(Uri.fromFile(privateFile), validation)
        }

    /** Imports a photo from device gallery. Only indexes if taken today + color passes. */
    suspend fun importPhoto(uri: Uri, targetColor: WalkColor): ImportResult =
        withContext(Dispatchers.IO) {
            val dateTaken = readPhotoDate(uri) ?: return@withContext ImportResult.NoDateMetadata
            if (!isToday(dateTaken)) return@withContext ImportResult.NotTakenToday(dateTaken)

            val bitmap = decodeBitmapFromUri(uri) ?: return@withContext ImportResult.StorageError
            val validation = ColorValidator.validate(bitmap, targetColor)
            if (!validation.passed) return@withContext ImportResult.ValidationFailed(validation)

            // Photo picker URIs are temporary — copy to private storage so display always works.
            val filename = "ColorWalk_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(dateTaken))}.jpg"
            val privateFile = saveToPrivateStorage(bitmap, filename)
                ?: return@withContext ImportResult.StorageError

            val (lat, lon) = readPhotoLocation(uri)
            val locationName = reverseGeocode(lat, lon)
            dao.insert(
                PhotoEntity(
                    filePath = privateFile.absolutePath,
                    colorName = targetColor.name,
                    colorHex = targetColor.hex,
                    dateTaken = dateTaken,
                    latitude = lat,
                    longitude = lon,
                    locationName = locationName,
                    dominantColorHex = validation.dominantHex
                )
            )
            ImportResult.Success(Uri.fromFile(privateFile), validation)
        }

    /**
     * Syncs MediaStore with the local DB on startup.
     *
     * Pass 1 — direct migration: for every DB row still pointing at a content:// URI,
     * open that URI directly (the app owns it so no extra permission needed) and copy
     * the bytes to private storage. This is the reliable path for update installs.
     *
     * Pass 2 — MediaStore scan: query for ColorWalk files to recover photos after a
     * fresh reinstall (new UID, no implicit content:// access). Wrapped in try/catch so
     * a SecurityException (READ_MEDIA_IMAGES denied) never silently kills the whole sync.
     */
    suspend fun syncGalleryWithDatabase() = withContext(Dispatchers.IO) {

        // ── Pass 1: directly migrate content:// URIs already stored in the DB ────────
        val allDbPhotos = dao.getAllPhotosSnapshot()
        for (photo in allDbPhotos) {
            val uri = Uri.parse(photo.filePath)
            if (uri.scheme != "content") continue          // already file:// — skip

            val dest = File(context.filesDir, "photos/photo_${photo.id}.jpg")
                .also { it.parentFile?.mkdirs() }

            if (dest.exists() && dest.length() > 0L) {
                // Already copied on a previous run — just point the DB row here.
                dao.updateFilePath(photo.id, dest.absolutePath)
                continue
            }

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }
                if (dest.exists() && dest.length() > 0L) {
                    dao.updateFilePath(photo.id, dest.absolutePath)
                }
            } catch (_: Exception) {
                // URI no longer accessible (e.g. fresh reinstall with different UID).
                // Pass 2 will attempt recovery via the MediaStore query.
            }
        }

        // ── Pass 2: MediaStore scan — recovery for fresh reinstalls ─────────────────
        val existingDays = dao.getAllPhotoDates()
            .map { StreakCalculator.epochMillisToDayIndex(it) }
            .toHashSet()

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }

        val cursor = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DISPLAY_NAME),
                selection,
                arrayOf("%ColorWalk%"),
                null
            )
        } catch (_: Exception) {
            null   // SecurityException if READ_MEDIA_IMAGES denied — skip Pass 2 gracefully
        }

        cursor?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (!name.startsWith("ColorWalk_")) continue

                var dateTaken = c.getLong(dateCol)
                if (dateTaken < 1_000_000_000_000L) {
                    dateTaken = parseDateFromFilename(name) ?: continue
                }

                val day = StreakCalculator.epochMillisToDayIndex(dateTaken)
                val mediaId = c.getLong(idCol)
                val mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)

                if (day in existingDays) {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = dateTaken
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val midnight = cal.timeInMillis
                    val tomorrowMidnight = midnight + 24 * 60 * 60 * 1000L
                    val existingId = dao.getPhotoIdForDay(midnight, tomorrowMidnight)
                    if (existingId != null) migrateToPrivateStorage(existingId, name, mediaUri)
                    continue
                }

                // New day (fresh-reinstall recovery) — copy to private storage and insert.
                val privateFile = copyMediaUriToPrivateStorage(name, mediaUri)
                val color = colorForDay(dateTaken)
                dao.insert(
                    PhotoEntity(
                        filePath = privateFile?.absolutePath ?: mediaUri.toString(),
                        colorName = color.name,
                        colorHex = color.hex,
                        dateTaken = dateTaken,
                        latitude = null,
                        longitude = null,
                        locationName = null,
                        dominantColorHex = color.hex
                    )
                )
                existingDays.add(day)
            }
        }
    }

    /** Deletes a photo from the app DB and storage (private file or MediaStore). */
    suspend fun deletePhoto(photo: PhotoEntity) = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(photo.filePath)
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) File(path).delete()
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {}
        dao.deleteById(photo.id)
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun parseDateFromFilename(name: String): Long? = try {
        val stem = name.removePrefix("ColorWalk_").removeSuffix(".jpg")
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stem)?.time
    } catch (_: Exception) { null }

    /** Saves a bitmap to the app's private files directory. Always succeeds or returns null. */
    private fun saveToPrivateStorage(bitmap: Bitmap, filename: String): File? = try {
        val dir = File(context.filesDir, "photos").also { it.mkdirs() }
        val file = File(dir, filename)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        file
    } catch (_: Exception) { null }

    /**
     * Publishes a copy of the photo to the system gallery (MediaStore) for visibility
     * in the Photos app. This is best-effort — the app's display doesn't depend on it.
     */
    private fun publishToSystemGallery(bitmap: Bitmap, filename: String, now: Long) {
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
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ColorWalk")
                dir.mkdirs()
                FileOutputStream(File(dir, filename)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
        } catch (_: Exception) { /* best-effort — never block the capture flow */ }
    }

    /**
     * Copies a MediaStore URI's bytes to private storage.
     * Used during sync to make existing photos available without permission complications.
     * Returns null (silently) if the URI can't be read — caller keeps the content:// URI.
     */
    private fun copyMediaUriToPrivateStorage(filename: String, mediaUri: Uri): File? {
        return try {
            val dir = File(context.filesDir, "photos").also { it.mkdirs() }
            val dest = File(dir, filename)
            if (dest.exists() && dest.length() > 0L) return dest
            context.contentResolver.openInputStream(mediaUri)?.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            if (dest.exists() && dest.length() > 0L) dest else null
        } catch (_: Exception) { null }
    }

    /**
     * Migrates a DB entry whose filePath may be a stale content:// URI to a private file.
     * If the copy succeeds, updates the DB row so Coil can always load it.
     * Silent no-op on any failure — existing filePath is kept.
     */
    private suspend fun migrateToPrivateStorage(photoId: Long, filename: String, mediaUri: Uri) {
        // copyMediaUriToPrivateStorage already handles the "already exists" check internally,
        // so no separate exist check is needed here — avoids the TOCTOU race condition.
        val privateFile = copyMediaUriToPrivateStorage(filename, mediaUri) ?: return
        try { dao.updateFilePath(photoId, privateFile.absolutePath) } catch (_: Exception) { }
    }

    private fun readPhotoDate(uri: Uri): Long? {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                if (col >= 0) { val ms = cursor.getLong(col); if (ms > 0) return ms }
            }
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                dateStr?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }
            }
        } catch (_: Exception) { null }
    }

    private fun readPhotoLocation(uri: Uri): Pair<Double?, Double?> = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val latLon = FloatArray(2)
            if (exif.getLatLong(latLon)) Pair(latLon[0].toDouble(), latLon[1].toDouble())
            else Pair(null, null)
        } ?: Pair(null, null)
    } catch (_: Exception) { Pair(null, null) }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    private fun isToday(epochMillis: Long): Boolean {
        val p = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val t = Calendar.getInstance()
        return p.get(Calendar.YEAR) == t.get(Calendar.YEAR) &&
                p.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
    }

    private suspend fun getLastLocation(): Pair<Double?, Double?> = try {
        val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
        Pair(loc?.latitude, loc?.longitude)
    } catch (_: Exception) { Pair(null, null) }

    private fun reverseGeocode(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.let { addr ->
                listOfNotNull(addr.subLocality ?: addr.locality, addr.adminArea)
                    .joinToString(", ").ifBlank { null }
            }
        } catch (_: Exception) { null }
    }
}

sealed class SaveResult {
    data class Success(val uri: Uri, val validation: ColorValidator.ValidationResult) : SaveResult()
    data class ValidationFailed(val validation: ColorValidator.ValidationResult) : SaveResult()
    object StorageError : SaveResult()
}

sealed class ImportResult {
    data class Success(val uri: Uri, val validation: ColorValidator.ValidationResult) : ImportResult()
    data class ValidationFailed(val validation: ColorValidator.ValidationResult) : ImportResult()
    object NoDateMetadata : ImportResult()
    data class NotTakenToday(val dateTaken: Long) : ImportResult()
    object StorageError : ImportResult()
}
