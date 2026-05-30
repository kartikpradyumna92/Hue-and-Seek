package com.colorwalk.app.data.repository

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

    suspend fun hasCapturedToday(): Boolean = withContext(Dispatchers.IO) {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        dao.getPhotoForDay(midnight / 1000) != null
    }

    /** Captures a photo. Only saves to gallery + DB if color validation passes. */
    suspend fun savePhoto(bitmap: Bitmap, targetColor: WalkColor): SaveResult =
        withContext(Dispatchers.IO) {
            val validation = ColorValidator.validate(bitmap, targetColor)

            // Reject before touching storage
            if (!validation.passed) return@withContext SaveResult.ValidationFailed(validation)

            val uri = saveBitmapToGallery(bitmap) ?: return@withContext SaveResult.StorageError
            val (lat, lon) = getLastLocation()
            val locationName = reverseGeocode(lat, lon)
            dao.insert(
                PhotoEntity(
                    filePath = uri.toString(),
                    colorName = targetColor.name,
                    colorHex = targetColor.hex,
                    dateTaken = System.currentTimeMillis(),
                    latitude = lat,
                    longitude = lon,
                    locationName = locationName,
                    dominantColorHex = validation.dominantHex
                )
            )
            SaveResult.Success(uri, validation)
        }

    /** Imports a photo from device gallery. Only indexes in DB if taken today + color passes. */
    suspend fun importPhoto(uri: Uri, targetColor: WalkColor): ImportResult =
        withContext(Dispatchers.IO) {
            val dateTaken = readPhotoDate(uri) ?: return@withContext ImportResult.NoDateMetadata
            if (!isToday(dateTaken)) return@withContext ImportResult.NotTakenToday(dateTaken)

            val bitmap = decodeBitmapFromUri(uri) ?: return@withContext ImportResult.StorageError
            val validation = ColorValidator.validate(bitmap, targetColor)

            if (!validation.passed) return@withContext ImportResult.ValidationFailed(validation)

            val (lat, lon) = readPhotoLocation(uri)
            val locationName = reverseGeocode(lat, lon)
            dao.insert(
                PhotoEntity(
                    filePath = uri.toString(),
                    colorName = targetColor.name,
                    colorHex = targetColor.hex,
                    dateTaken = dateTaken,
                    latitude = lat,
                    longitude = lon,
                    locationName = locationName,
                    dominantColorHex = validation.dominantHex
                )
            )
            ImportResult.Success(uri, validation)
        }

    /** Deletes a photo from both the app DB and the device gallery. */
    suspend fun deletePhoto(photo: PhotoEntity) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(Uri.parse(photo.filePath), null, null)
        } catch (_: Exception) {}
        dao.deleteById(photo.id)
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val filename = "ColorWalk_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ColorWalk")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
            uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ColorWalk")
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            Uri.fromFile(file)
        }
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
