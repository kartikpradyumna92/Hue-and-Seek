package com.colorwalk.app.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Address
import android.location.Geocoder
import androidx.exifinterface.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.data.db.ColorSummary
import com.colorwalk.app.data.db.PhotoDao
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.WalkColor
import com.colorwalk.app.domain.colorForDay
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PhotoDao,
    private val db: AppDatabase
) {
    // Outlives any single caller: post-save geocode updates must complete even if
    // the capture screen (and its viewModelScope) is gone before the network does.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getAllPhotos(): Flow<List<PhotoEntity>> = dao.getAllPhotos()
    fun getPhotosByColor(colorName: String): Flow<List<PhotoEntity>> = dao.getPhotosByColor(colorName)
    fun getDistinctColors(): Flow<List<ColorSummary>> = dao.getDistinctColors()

    suspend fun getAllPhotosSnapshot(): List<PhotoEntity> = withContext(Dispatchers.IO) {
        dao.getAllPhotosSnapshot()
    }

    /**
     * One-time backfill: for every DB row with null lat/lon, find the corresponding
     * MediaStore entry by filename and read its GPS EXIF (written at capture time by
     * publishToSystemGallery). If GPS is found, update lat/lon and reverse-geocode
     * locationName. After all rows are filled this becomes a no-op.
     *
     * C1: Per-photo "attempted" markers are persisted in SharedPreferences so the
     * MediaStore query is skipped on subsequent Gallery opens once every missing photo
     * has been tried (whether GPS was found or not).
     *
     * C4: All DB location updates are batched in a single transaction.
     */
    suspend fun backfillLocationData() = withContext(Dispatchers.IO) {
        val allMissing = dao.getAllPhotosSnapshot()
            .filter { photo ->
                val lat = photo.latitude
                val lon = photo.longitude
                (lat == null && lon == null) ||
                (lat != null && lon != null && Math.abs(lat) < 0.001 && Math.abs(lon) < 0.001)
            }
        if (allMissing.isEmpty()) return@withContext

        val attemptedIds = getAttemptedBackfillIds()
        val toAttempt = allMissing.filter { it.id !in attemptedIds }
        if (toAttempt.isEmpty()) return@withContext

        // Build filename → GPS map from MediaStore EXIF in one pass
        val locationByFilename = mutableMapOf<String, Pair<Double, Double>>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE 'ColorWalk_%'"
        val backfillCursor = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, null
            )
        } catch (_: Exception) { null }   // SecurityException if READ_MEDIA_IMAGES denied — skip gracefully
        backfillCursor?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val mediaId  = cursor.getLong(idCol)
                val mediaUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId
                )
                val (lat, lon) = readPhotoLocation(mediaUri)
                if (lat != null && lon != null) locationByFilename[name] = Pair(lat, lon)
            }
        }

        // Resolve location names outside the transaction (network calls)
        data class LocationUpdate(val id: Long, val lat: Double, val lon: Double, val name: String?)
        val updates = mutableListOf<LocationUpdate>()
        for (photo in toAttempt) {
            val filename = java.io.File(photo.filePath).name
                .takeIf { it.startsWith("ColorWalk_") } ?: continue
            val (lat, lon) = locationByFilename[filename] ?: continue
            val locationName = reverseGeocode(lat, lon)
            updates.add(LocationUpdate(photo.id, lat, lon, locationName))
        }

        // Batch DB writes in one transaction (C4)
        if (updates.isNotEmpty()) {
            db.withTransaction {
                for (u in updates) dao.updateLocation(u.id, u.lat, u.lon, u.name)
            }
        }

        // Mark all attempted IDs so this scan never repeats for these photos (C1)
        markBackfillAttempted(toAttempt.map { it.id })
    }

    suspend fun tagPhotoLocation(id: Long, locationName: String) = withContext(Dispatchers.IO) {
        val photos = dao.getAllPhotosSnapshot()
        val photo = photos.firstOrNull { it.id == id } ?: return@withContext

        // A photo with a usable GPS fix keeps its own coordinates — naming it must
        // never overwrite real data (B7). Only photos without a fix inherit lat/lon
        // from a same-named photo so coordinate clustering still works.
        if (hasRealFix(photo.latitude, photo.longitude)) {
            dao.updateLocation(id, photo.latitude, photo.longitude, locationName)
        } else {
            val ref = photos.firstOrNull {
                it.id != id && it.locationName == locationName && hasRealFix(it.latitude, it.longitude)
            }
            dao.updateLocation(id, ref?.latitude, ref?.longitude, locationName)
        }
    }

    /** True for coordinates that are present and not the (0,0) "null island" bad fix. */
    private fun hasRealFix(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && !(Math.abs(lat) < 0.001 && Math.abs(lon) < 0.001)

    suspend fun getStreak(): Int = withContext(Dispatchers.IO) {
        StreakCalculator.compute(dao.getAllPhotoDates())
    }

    /** Day indices (local-tz) for all photos — used by history strip and review check. */
    suspend fun getCapturedDayIndices(): Set<Int> = withContext(Dispatchers.IO) {
        dao.getAllPhotoDates()
            .map { StreakCalculator.epochMillisToDayIndex(it) }
            .toHashSet()
    }

    suspend fun hasCapturedToday(): Boolean = withContext(Dispatchers.IO) {
        val midnight = StreakCalculator.todayMidnightMs()
        val tomorrowMidnight = midnight + 24L * 60 * 60 * 1000
        dao.getPhotoForDay(midnight, tomorrowMidnight) != null
    }

    /** Captures a photo. Only saves if color validation passes. */
    suspend fun savePhoto(bitmap: Bitmap, targetColor: WalkColor): SaveResult =
        withContext(Dispatchers.IO) {
            // Kick off the GPS request immediately so the fix warms up while color
            // validation runs — usually zero added latency for the user.
            val locationDeferred = async { getFreshLocation() }

            val validation = ColorValidator.validate(bitmap, targetColor)
            if (!validation.passed) {
                locationDeferred.cancel()
                bitmap.recycle()  // C3: release before returning
                return@withContext SaveResult.ValidationFailed(validation)
            }

            val now = System.currentTimeMillis()
            // Include millis so two captures in the same second never share a filename.
            val filename = "ColorWalk_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))}_${now % 1000}.jpg"

            // Primary: save to app-private files dir — always readable without permissions.
            val privateFile = saveToPrivateStorage(bitmap, filename)
            if (privateFile == null) {
                locationDeferred.cancel()
                bitmap.recycle()  // C3: release before returning
                return@withContext SaveResult.StorageError
            }

            val (lat, lon) = locationDeferred.await()

            // Secondary: publish to system gallery with GPS EXIF so Google Photos shows location.
            publishToSystemGallery(bitmap, filename, now, lat, lon)
            bitmap.recycle()  // C3: done with the pixel data — private file and MediaStore copy are written

            val id = dao.insert(
                PhotoEntity(
                    filePath = privateFile.absolutePath,   // absolute path — passed as File to Coil
                    colorName = targetColor.name,
                    colorHex = targetColor.hex,
                    dateTaken = now,
                    latitude = lat,
                    longitude = lon,
                    locationName = null,   // resolved asynchronously below (B11)
                    dominantColorHex = validation.dominantHex
                )
            )
            // A re-captured photo is a deliberate re-add — drop any matching tombstone.
            DeletionTombstones.clear(context, filename, now)
            // Reverse geocoding hits the network — the success card must not wait on it.
            resolveLocationNameAsync(id, lat, lon)
            SaveResult.Success(Uri.fromFile(privateFile), validation)
        }

    /** Imports a photo from device gallery. Only indexes if taken today + color passes. */
    suspend fun importPhoto(uri: Uri, targetColor: WalkColor): ImportResult =
        withContext(Dispatchers.IO) {
            val dateTaken = readPhotoDate(uri) ?: return@withContext ImportResult.NoDateMetadata
            if (!isToday(dateTaken)) return@withContext ImportResult.NotTakenToday(dateTaken)

            // Re-importing the same photo would insert a second row pointing at the
            // same file (B5) — exact-millis timestamp match identifies it cheaply.
            if (dao.countByDateTaken(dateTaken) > 0) return@withContext ImportResult.AlreadyImported

            val bitmap = decodeBitmapFromUri(uri) ?: return@withContext ImportResult.StorageError
            val validation = ColorValidator.validate(bitmap, targetColor)
            if (!validation.passed) {
                bitmap.recycle()  // C3: release before returning
                return@withContext ImportResult.ValidationFailed(validation)
            }

            // Photo picker URIs are temporary — copy to private storage so display always works.
            // Millis suffix matches savePhoto: two photos taken the same second must not
            // collide on filename and silently overwrite each other (B5).
            val filename = "ColorWalk_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(dateTaken))}_${dateTaken % 1000}.jpg"
            val privateFile = saveToPrivateStorage(bitmap, filename)
            bitmap.recycle()  // C3: done after the single compress; safe before the null check
            if (privateFile == null) return@withContext ImportResult.StorageError

            val (lat, lon) = readPhotoLocation(uri)
            val id = dao.insert(
                PhotoEntity(
                    filePath = privateFile.absolutePath,
                    colorName = targetColor.name,
                    colorHex = targetColor.hex,
                    dateTaken = dateTaken,
                    latitude = lat,
                    longitude = lon,
                    locationName = null,   // resolved asynchronously below (B11)
                    dominantColorHex = validation.dominantHex
                )
            )
            // Re-importing a previously deleted photo is a deliberate re-add — drop
            // any matching tombstone so future syncs can recover it again.
            DeletionTombstones.clear(context, filename, dateTaken)
            resolveLocationNameAsync(id, lat, lon)
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
     *
     * C4: file I/O (content copies) happens outside any transaction; the resulting DB
     * writes are batched in two single transactions (one per pass) to reduce commit overhead.
     */
    suspend fun syncGalleryWithDatabase() = withContext(Dispatchers.IO) {

        // ── Pass 0: deduplicate existing DB rows by filePath ─────────────────────────
        // Fixes duplicates already in the DB (e.g. from a sync that ran before the
        // filename-based dedup guard was in place). For each filePath that appears more
        // than once, keep only the row with the lowest id.
        dao.deleteFilepathDuplicates()

        // ── Pass 1: directly migrate content:// URIs already stored in the DB ────────
        // Collect (id, newPath) pairs first so file I/O runs outside the transaction.
        val allDbPhotos = dao.getAllPhotosSnapshot()
        val pass1Updates = mutableListOf<Pair<Long, String>>()
        for (photo in allDbPhotos) {
            val uri = Uri.parse(photo.filePath)
            if (uri.scheme != "content") continue          // already file:// — skip

            val dest = File(context.filesDir, "photos/photo_${photo.id}.jpg")
                .also { it.parentFile?.mkdirs() }

            if (dest.exists() && dest.length() > 0L) {
                pass1Updates.add(Pair(photo.id, dest.absolutePath))
                continue
            }

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }
                if (dest.exists() && dest.length() > 0L) {
                    pass1Updates.add(Pair(photo.id, dest.absolutePath))
                }
            } catch (_: Exception) {
                // URI no longer accessible (e.g. fresh reinstall with different UID).
                // Pass 2 will attempt recovery via the MediaStore query.
            }
        }
        // Apply Pass 1 writes in one transaction (C4)
        if (pass1Updates.isNotEmpty()) {
            db.withTransaction {
                for ((id, path) in pass1Updates) dao.updateFilePath(id, path)
            }
        }

        // ── Pass 2: MediaStore scan — recovery for fresh reinstalls ─────────────────
        // Split DB rows into those whose private file is present on disk vs. missing.
        // A row with a content:// path is treated as "existing" — Pass 1 above handles it.
        val (existingRows, missingRows) = allDbPhotos.partition { photo ->
            !photo.filePath.startsWith("/") || File(photo.filePath).exists()
        }

        // Skip MediaStore entries whose file is already on disk.
        val existingFilenames = existingRows
            .map { File(it.filePath).name }
            .filter { it.startsWith("ColorWalk_") }
            .toHashSet()

        // For rows whose private file is gone, map filename → entity so we can
        // re-copy from MediaStore and UPDATE the existing row instead of inserting a duplicate.
        val missingByFilename: Map<String, PhotoEntity> = missingRows
            .filter { it.filePath.startsWith("/") }
            .associateBy { File(it.filePath).name }
            .filterKeys { it.startsWith("ColorWalk_") }

        // existingTimestamps: dedup guard so a re-sync never inserts the same photo twice.
        val existingTimestamps = existingRows.map { it.dateTaken }.toHashSet()
        // B6: DATE_TAKEN sometimes only survives at *second* precision (filename
        // fallback, OEM quirks), while DB rows hold millis — also dedup on
        // second-truncated timestamps so a precision mismatch can't duplicate a photo.
        val existingSeconds = existingRows.map { it.dateTaken / 1000 }.toHashSet()

        // A2 guard: photos the user explicitly deleted must never be re-imported, even
        // when their MediaStore copy couldn't be removed (non-owned after a reinstall).
        val deletedNames = DeletionTombstones.deletedFilenames(context)
        val deletedDates = DeletionTombstones.deletedDates(context)

        val allExistingDates = dao.getAllPhotoDates()
        // existingDays: still needed to trigger content:// URI migration for known days.
        val existingDays = allExistingDates
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

        // Collect all Pass 2 file I/O results before writing to DB (C4)
        val pass2PathUpdates = mutableListOf<Pair<Long, String>>()   // (id, recoveredPath)
        val pass2Inserts     = mutableListOf<PhotoEntity>()

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

                // Never resurrect a photo the user explicitly deleted (A2) — and never
                // use its MediaStore entry as a migration source either.
                if (name in deletedNames || dateTaken in deletedDates) continue

                val day = StreakCalculator.epochMillisToDayIndex(dateTaken)
                val mediaId = c.getLong(idCol)
                val mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)

                // For days already in the DB, migrate any photo row that still holds a
                // stale content:// URI. Only migrate once per day (LIMIT 1 query).
                // migrateToPrivateStorage handles its own DB write inline (rare path).
                if (day in existingDays) {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = dateTaken
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val midnight = cal.timeInMillis
                    val tomorrowMidnight = midnight + 24 * 60 * 60 * 1000L
                    val existingId = dao.getContentUriPhotoIdForDay(midnight, tomorrowMidnight)
                    if (existingId != null) migrateToPrivateStorage(existingId, name, mediaUri)
                }

                // File already on disk → nothing to do.
                if (name in existingFilenames) continue

                // DB row exists but private file was lost (e.g. reinstall wiped filesDir).
                // Re-copy from MediaStore and collect UPDATE; never insert a duplicate.
                val missingEntity = missingByFilename[name]
                if (missingEntity != null) {
                    val recovered = copyMediaUriToPrivateStorage(name, mediaUri)
                    if (recovered != null) {
                        pass2PathUpdates.add(Pair(missingEntity.id, recovered.absolutePath))
                        existingFilenames.add(name)
                        existingTimestamps.add(missingEntity.dateTaken)
                        existingSeconds.add(missingEntity.dateTaken / 1000)
                    }
                    continue
                }

                // Skip if this timestamp is already in the DB (primary dedup guard) —
                // exact millis first, second-precision as the tolerance fallback (B6).
                if (dateTaken in existingTimestamps || dateTaken / 1000 in existingSeconds) continue

                // Genuinely new photo — recover it (fresh reinstall, or first sync).
                val privateFile = copyMediaUriToPrivateStorage(name, mediaUri)
                val color = colorForDay(dateTaken)
                pass2Inserts.add(
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
                existingTimestamps.add(dateTaken)
                existingSeconds.add(dateTaken / 1000)
                existingDays.add(day)
            }
        }

        // Apply all Pass 2 DB writes in one transaction (C4)
        if (pass2PathUpdates.isNotEmpty() || pass2Inserts.isNotEmpty()) {
            db.withTransaction {
                for ((id, path) in pass2PathUpdates) dao.updateFilePath(id, path)
                for (entity in pass2Inserts) dao.insert(entity)
            }
        }
    }

    /**
     * Rotates a photo 90° clockwise by updating the EXIF orientation tag in place —
     * no pixel re-encoding, no generation loss, no OOM risk (C2). Coil 2.x respects
     * the EXIF orientation tag when loading from file paths.
     */
    suspend fun rotatePhoto(photo: PhotoEntity) = withContext(Dispatchers.IO) {
        if (!photo.filePath.startsWith("/")) return@withContext
        try {
            val exif = ExifInterface(photo.filePath)
            val current = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val next = when (current) {
                ExifInterface.ORIENTATION_NORMAL     -> ExifInterface.ORIENTATION_ROTATE_90
                ExifInterface.ORIENTATION_ROTATE_90  -> ExifInterface.ORIENTATION_ROTATE_180
                ExifInterface.ORIENTATION_ROTATE_180 -> ExifInterface.ORIENTATION_ROTATE_270
                ExifInterface.ORIENTATION_ROTATE_270 -> ExifInterface.ORIENTATION_NORMAL
                else                                 -> ExifInterface.ORIENTATION_ROTATE_90
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, next.toString())
            exif.saveAttributes()
        } catch (_: Exception) { }
    }

    /** Deletes a photo from the app DB, private storage, and MediaStore. */
    suspend fun deletePhoto(photo: PhotoEntity) = withContext(Dispatchers.IO) {
        // Tombstone FIRST: if the MediaStore copy can't be removed (it is owned by a
        // previous install after a reinstall), syncGalleryWithDatabase() would otherwise
        // re-import the photo on the next launch — the A2 "resurrection" bug.
        DeletionTombstones.record(context, resolveFilename(photo.filePath), photo.dateTaken)
        // Clear the backfill-attempted marker so a re-import gets a fresh GPS attempt (C1).
        clearBackfillAttempted(photo.id)
        try {
            val path = photo.filePath
            when {
                path.startsWith("/") -> {
                    // Bare absolute path (the common case since v1.8.0)
                    val file = File(path)
                    file.delete()
                    // Remove the matching MediaStore entry so sync doesn't re-insert it.
                    deleteFromMediaStore(file.name)
                }
                else -> {
                    val uri = Uri.parse(path)
                    when (uri.scheme) {
                        "file" -> uri.path?.let { File(it).delete() }
                        "content" -> context.contentResolver.delete(uri, null, null)
                    }
                }
            }
        } catch (_: Exception) {}
        dao.deleteById(photo.id)
    }

    /** Best-effort display filename for any of the three filePath shapes we store. */
    private fun resolveFilename(path: String): String? = try {
        when {
            path.startsWith("/") -> File(path).name
            else -> {
                val uri = Uri.parse(path)
                when (uri.scheme) {
                    "file" -> uri.lastPathSegment
                    "content" -> context.contentResolver.query(
                        uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    else -> null
                }
            }
        }
    } catch (_: Exception) { null }

    private fun deleteFromMediaStore(filename: String) {
        if (!filename.startsWith("ColorWalk_")) return
        try {
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(filename)
            )
        } catch (_: Exception) {}
    }

    // ── C1: per-photo backfill-attempted markers ─────────────────────────────
    // Stored in "app_prefs" SharedPreferences alongside tombstones. After a photo
    // is attempted for GPS backfill (whether GPS was found or not) its ID is added
    // here so the full MediaStore EXIF scan is skipped on subsequent Gallery opens.

    private fun getAttemptedBackfillIds(): Set<Long> {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("backfill_attempted_ids", emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toHashSet() ?: emptySet()
    }

    private fun markBackfillAttempted(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val existing = HashSet(prefs.getStringSet("backfill_attempted_ids", emptySet()) ?: emptySet())
        existing.addAll(ids.map { it.toString() })
        prefs.edit().putStringSet("backfill_attempted_ids", existing).apply()
    }

    private fun clearBackfillAttempted(id: Long) {
        val key = id.toString()
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getStringSet("backfill_attempted_ids", null) ?: return
        if (key !in existing) return
        prefs.edit()
            .putStringSet("backfill_attempted_ids", HashSet(existing).apply { remove(key) })
            .apply()
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Recovers a timestamp from "ColorWalk_yyyyMMdd_HHmmss[_SSS].jpg". Filenames carry
     * a millis suffix since v1.12 — restoring it keeps the parsed value identical to
     * the DB's millisecond dateTaken so the sync dedup guard matches exactly (B6).
     */
    private fun parseDateFromFilename(name: String): Long? = try {
        val parts = name.removePrefix("ColorWalk_").removeSuffix(".jpg").split("_")
        if (parts.size < 2) null
        else SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .parse("${parts[0]}_${parts[1]}")?.time
            ?.plus(parts.getOrNull(2)?.toLongOrNull()?.takeIf { it in 0..999 } ?: 0L)
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
    private fun publishToSystemGallery(bitmap: Bitmap, filename: String, now: Long, lat: Double?, lon: Double?) {
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
                if (lat != null && lon != null) {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        writeGpsExif(exif, lat, lon)
                        exif.saveAttributes()
                    }
                }
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ColorWalk")
                dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                // bitmap.compress writes no EXIF — set the capture time so the media
                // scanner indexes a correct DATE_TAKEN, plus GPS when available.
                val exif = ExifInterface(file.absolutePath)
                val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(now))
                exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                if (lat != null && lon != null) writeGpsExif(exif, lat, lon)
                exif.saveAttributes()
                // Without an explicit scan the file is invisible to the system gallery
                // and to our own MediaStore-based sync/delete queries (A5).
                MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null
                )
            }
        } catch (_: Exception) { /* best-effort — never block the capture flow */ }
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

    private fun readPhotoLocation(uri: Uri): Pair<Double?, Double?> {
        // On Android 10+ MediaStore redacts GPS EXIF from opened streams unless the
        // caller holds ACCESS_MEDIA_LOCATION AND opens the URI via setRequireOriginal
        // (A4). Try the original first; fall back to the plain stream for URIs that
        // don't support it (photo picker) or when the permission is missing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return readExifLatLon(MediaStore.setRequireOriginal(uri))
            } catch (_: Exception) { /* fall through to the (possibly redacted) stream */ }
        }
        return try { readExifLatLon(uri) } catch (_: Exception) { Pair(null, null) }
    }

    private fun readExifLatLon(uri: Uri): Pair<Double?, Double?> =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val latLon = FloatArray(2)
            if (exif.getLatLong(latLon)) Pair(latLon[0].toDouble(), latLon[1].toDouble())
            else Pair(null, null)
        } ?: Pair(null, null)

    /**
     * Decodes a bitmap from a URI at reduced resolution to avoid OOM on high-MP imports (C2).
     * Uses a two-pass BitmapFactory approach: first read dimensions only, then decode at
     * the appropriate inSampleSize to cap the result at 4096×4096 px — sufficient for
     * color classification and private-storage saves.
     */
    private fun decodeBitmapFromUri(uri: Uri): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, 4096, 4096)
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
    } catch (_: Exception) { null }

    /** Returns the largest power-of-2 inSampleSize that keeps the decoded image within maxW×maxH. */
    private fun calculateInSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var inSampleSize = 1
        if (height > maxHeight || width > maxWidth) {
            val halfHeight = height / 2
            val halfWidth  = width / 2
            while (halfHeight / inSampleSize >= maxHeight && halfWidth / inSampleSize >= maxWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun isToday(epochMillis: Long): Boolean {
        // A strict calendar-day comparison fails for two real-world cases:
        //  (1) Travel: photo taken at 11pm in timezone A; by the time the user
        //      imports it the phone is in timezone B where that same moment is
        //      already "yesterday".
        //  (2) OEM MediaStore oddities: some manufacturers store DATE_TAKEN as
        //      local-naive millis (dropping the UTC offset), shifting timestamps
        //      by the device's UTC offset (up to ±14 h).
        // A ±4 h grace window around local midnight covers case (1) for most
        // flight-length timezone changes and is generous enough for case (2) on
        // typical UTC+/-12 devices while still blocking clearly wrong-day imports.
        val GRACE_MS = 4L * 60 * 60 * 1000 // 4 hours
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayEnd = todayStart + 24L * 60 * 60 * 1000
        return epochMillis in (todayStart - GRACE_MS) until (todayEnd + GRACE_MS)
    }

    /**
     * Actively requests a fresh GPS fix (bounded at 5s), falling back to the
     * passive last-known cache. The old implementation read ONLY the cache, which
     * is empty whenever no app recently obtained a fix — so photos silently saved
     * without coordinates even with location permission granted.
     */
    private suspend fun getFreshLocation(): Pair<Double?, Double?> = try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val fresh = try {
            withTimeoutOrNull(5_000L) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            }
        } finally {
            cts.cancel()   // stop the hardware request if we timed out or were cancelled
        }
        val loc = fresh ?: client.lastLocation.await()
        Pair(loc?.latitude, loc?.longitude)
    } catch (_: Exception) { Pair(null, null) }

    /**
     * Fills in locationName after the photo row already exists and the result has
     * been returned to the UI — geocoding is a network call that used to block the
     * "Color Match!" card for seconds (B11). Room Flows re-emit on the update, so
     * the By Place gallery picks the name up whenever it lands.
     */
    private fun resolveLocationNameAsync(id: Long, lat: Double?, lon: Double?) {
        if (lat == null || lon == null) return
        repoScope.launch {
            val name = reverseGeocode(lat, lon) ?: return@launch
            dao.updateLocation(id, lat, lon, name)
        }
    }

    private suspend fun reverseGeocode(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) =
                            cont.resume(addresses.firstOrNull())
                        override fun onError(errorMessage: String?) = cont.resume(null)
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            }
            addr?.let {
                listOfNotNull(it.subLocality ?: it.locality, it.adminArea)
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
    object AlreadyImported : ImportResult()
    object StorageError : ImportResult()
}
