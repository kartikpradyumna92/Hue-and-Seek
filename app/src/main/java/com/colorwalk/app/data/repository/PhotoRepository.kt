package com.colorwalk.app.data.repository

import android.content.ContentUris
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.data.db.ColorSummary
import com.colorwalk.app.data.db.PhotoDao
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.domain.ExifIntegrity
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.WalkColor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

import javax.inject.Inject
import javax.inject.Singleton

// M-8: IO failures in here used to be swallowed with empty catch blocks — which is
// exactly how the live-meter bug shipped (every frame threw, nothing was ever
// logged). Expected/per-design fallbacks stay quiet or log at DEBUG; anything that
// loses user-visible work logs at WARN or ERROR.
private const val TAG = "PhotoRepository"

@Singleton
class PhotoRepository internal constructor(
    private val context: Context,
    private val dao: PhotoDao,
    private val db: AppDatabase,
    // I-6: overridable collaborators so save/import result paths are JVM-testable
    // with fakes; production always passes null and gets the real implementations.
    filesOverride: PhotoFileStore?,
    locationOverride: LocationResolver?,
    mediaGalleryOverride: MediaStoreGallery?,
    gallerySyncOverride: GallerySynchronizer?
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        dao: PhotoDao,
        db: AppDatabase
    ) : this(context, dao, db, null, null, null, null)

    // Outlives any single caller: post-save geocode updates must complete even if
    // the capture screen (and its viewModelScope) is gone before the network does.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // I-1: focused collaborators — the repository orchestrates; storage, MediaStore,
    // location, and startup sync each live in their own independently testable unit.
    private val files = filesOverride ?: PhotoFileStore(context)
    private val location = locationOverride ?: LocationResolver(context)
    private val mediaGallery = mediaGalleryOverride ?: MediaStoreGallery(context)
    private val gallerySync = gallerySyncOverride ?: GallerySynchronizer(context, dao, db, files)

    fun getAllPhotos(): Flow<List<PhotoEntity>> = dao.getAllPhotos()
    fun getPhotosByColor(colorName: String): Flow<List<PhotoEntity>> = dao.getPhotosByColor(colorName)
    fun getDistinctColors(): Flow<List<ColorSummary>> = dao.getDistinctColors()

    /**
     * Persists a user-written description to the DB, to the private JPEG file,
     * and to the matching MediaStore copy so Google Photos picks up the note
     * regardless of when the user adds or edits it.
     */
    suspend fun saveDescription(photo: PhotoEntity, text: String?) = withContext(Dispatchers.IO) {
        val trimmed = text?.trim()?.ifBlank { null }
        dao.updateDescription(photo.id, trimmed)

        val tag = trimmed ?: ""

        // 1. Write to the private file.
        if (photo.filePath.startsWith("/")) {
            try {
                val exif = ExifInterface(photo.filePath)
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, tag)
                exif.saveAttributes()
            } catch (e: Exception) {
                // Note survives in the DB either way; only the EXIF copy is lost.
                android.util.Log.w(TAG, "EXIF description write failed for ${photo.filePath}", e)
            }
        }

        // 2. Write to the MediaStore copy so Google Photos sees the updated EXIF
        //    even if it already backed up an earlier version of the file.
        val filename = resolveFilename(photo.filePath)
        if (filename != null && filename.startsWith("ColorWalk_")) {
            mediaGallery.writeDescription(filename, tag)
        }
    }

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
            val locationName = location.reverseGeocode(lat, lon)
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

    suspend fun getPhotoById(id: Long): PhotoEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun tagPhotoLocation(id: Long, locationName: String) = withContext(Dispatchers.IO) {
        val photo = dao.getById(id) ?: return@withContext

        // A photo with a usable GPS fix keeps its own coordinates — naming it must
        // never overwrite real data (B7). Only photos without a fix inherit lat/lon
        // from a same-named photo so coordinate clustering still works.
        if (hasRealFix(photo.latitude, photo.longitude)) {
            dao.updateLocation(id, photo.latitude, photo.longitude, locationName)
        } else {
            val ref = dao.getLocationDonor(locationName, excludeId = id)
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

    /**
     * Captures a photo. Only saves if color validation passes.
     *
     * Takes the camera HAL's original JPEG BYTES, not a decoded bitmap (L-2): the
     * bytes are written to disk verbatim, so the photo keeps its full EXIF block
     * (orientation, capture time, device tags) and never pays a re-encode
     * generation. Validation runs on a bounded decode (H-3's 4096px cap); pixel
     * rotation is unnecessary — Coil honors the EXIF orientation tag for display,
     * and the validator's spatial weighting is symmetric under 90° rotations.
     *
     * [mirrorHorizontally] (front camera, L-3): the flip is composed into the EXIF
     * orientation tag on both saved copies — same zero-re-encode principle, and the
     * validator doesn't care (its weighting is mirror-symmetric too).
     */
    suspend fun savePhoto(
        jpegBytes: ByteArray,
        targetColor: WalkColor,
        mirrorHorizontally: Boolean = false
    ): SaveResult =
        withContext(Dispatchers.IO) {
            // Kick off the GPS request immediately so the fix warms up while color
            // validation runs. On repoScope, NOT this scope: withContext waits for
            // its children, and the whole point of L-8 is that savePhoto returns
            // without waiting for the fix.
            val locationDeferred = repoScope.async { location.getFreshLocation() }

            val bitmap = files.decodeBounded(jpegBytes)
            if (bitmap == null) {
                locationDeferred.cancel()
                return@withContext SaveResult.StorageError
            }
            val validation = ColorValidator.validate(bitmap, targetColor)
            bitmap.recycle()  // C3: pixel data no longer needed — disk writes use the bytes
            if (!validation.passed) {
                locationDeferred.cancel()
                return@withContext SaveResult.ValidationFailed(validation)
            }

            val now = System.currentTimeMillis()
            // Include millis so two captures in the same second never share a filename.
            val filename = "ColorWalk_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))}_${now % 1000}.jpg"

            // Primary: save to app-private files dir — always readable without permissions.
            val privateFile = files.saveBytes(jpegBytes, filename)
            if (privateFile == null) {
                locationDeferred.cancel()
                return@withContext SaveResult.StorageError
            }
            if (mirrorHorizontally) {
                try {
                    ExifInterface(privateFile.absolutePath).apply {
                        flipHorizontally()
                        saveAttributes()
                    }
                } catch (e: Exception) {
                    // Un-mirrored selfie is a cosmetic miss, not a lost capture.
                    android.util.Log.w(TAG, "Selfie mirror flip failed for $filename", e)
                }
            }

            val id = dao.insert(
                PhotoEntity(
                    filePath = privateFile.absolutePath,   // absolute path — passed as File to Coil
                    colorName = targetColor.name,
                    colorHex = targetColor.hex,
                    dateTaken = now,
                    latitude = null,       // patched asynchronously below (L-8)
                    longitude = null,
                    locationName = null,   // resolved asynchronously below (B11)
                    dominantColorHex = validation.dominantHex
                )
            )
            // (No tombstone clear here — a fresh capture always has a brand-new
            // filename and timestamp, so it can never match an old tombstone; only
            // the import path re-adds previously deleted content (L-6).)

            // L-8: the "Color Match!" card must not wait on a GPS fix (up to 5s on a
            // cold radio). The row exists and Success returns NOW; the location, the
            // best-effort gallery publish (which wants the GPS for its EXIF), and the
            // geocode all land afterwards on repoScope, which outlives the caller.
            repoScope.launch {
                val (lat, lon) = try { locationDeferred.await() } catch (_: Exception) { Pair(null, null) }
                if (lat != null && lon != null) {
                    dao.updateLocation(id, lat, lon, null)
                }
                mediaGallery.publish(
                    jpegBytes, filename, now, lat, lon,
                    targetColor.name, validation.dominantHex, mirrorHorizontally
                )
                resolveLocationNameAsync(id, lat, lon)
            }
            SaveResult.Success(Uri.fromFile(privateFile), validation, id)
        }

    /** Imports a photo from device gallery. Only indexes if taken today + color passes. */
    suspend fun importPhoto(uri: Uri, targetColor: WalkColor): ImportResult =
        withContext(Dispatchers.IO) {
            val dateTaken = readPhotoDate(uri) ?: return@withContext ImportResult.NoDateMetadata
            // Forgery gate BEFORE the day check: a tampered "today" date must be
            // reported as tampering, not accepted as today or excused as wrong-day.
            if (checkImportIntegrity(uri, dateTaken) is ExifIntegrity.Verdict.Tampered) {
                return@withContext ImportResult.MetadataTampered
            }
            if (!isToday(dateTaken)) return@withContext ImportResult.NotTakenToday(dateTaken)

            // Re-importing the same photo must not insert a second row (B5/M-4).
            // Exact-millis matching had two failure modes: EXIF dates are second-
            // precision while MediaStore dates carry millis (same photo, different
            // precision → duplicate slipped through), and two burst shots sharing a
            // second-precision timestamp collided (different photos → second one
            // falsely rejected). So: hunt candidates at SECOND granularity, then
            // disambiguate by the original source file's byte size. A candidate with
            // unknown size (in-app capture, legacy row) stays a duplicate — never
            // trade a false accept for a false reject on old data.
            val incomingSize = queryContentSize(uri)
            val sameSecond = dao.getByDateTakenSecond(dateTaken / 1000)
            val isDuplicate = sameSecond.any { row ->
                row.originalSizeBytes == null || incomingSize <= 0L ||
                    row.originalSizeBytes == incomingSize
            }
            if (isDuplicate) return@withContext ImportResult.AlreadyImported

            val bitmap = files.decodeBoundedFromUri(uri) ?: return@withContext ImportResult.StorageError
            val validation = ColorValidator.validate(bitmap, targetColor)
            if (!validation.passed) {
                bitmap.recycle()  // C3: release before returning
                return@withContext ImportResult.ValidationFailed(validation)
            }

            // Photo picker URIs are temporary — copy to private storage so display always works.
            // Millis suffix matches savePhoto: two photos taken the same second must not
            // collide on filename and silently overwrite each other (B5).
            // L-2: copy the ORIGINAL bytes (full EXIF, no re-encode generation); only if
            // the raw copy fails, fall back to compressing the already-decoded bitmap.
            val filename = "ColorWalk_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(dateTaken))}_${dateTaken % 1000}.jpg"
            val privateFile = files.copyFromUri(filename, uri)
                ?: files.saveBitmap(bitmap, filename)
            bitmap.recycle()  // C3: safe before the null check
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
                    dominantColorHex = validation.dominantHex,
                    originalSizeBytes = incomingSize.takeIf { it > 0L }   // M-4 dedup identity
                )
            )
            // Re-importing a previously deleted photo is a deliberate re-add — drop
            // any matching tombstone so future syncs can recover it again.
            DeletionTombstones.clear(context, filename, dateTaken)
            resolveLocationNameAsync(id, lat, lon)
            ImportResult.Success(Uri.fromFile(privateFile), validation, id)
        }

    /** Startup MediaStore↔DB reconciliation — see [GallerySynchronizer]. */
    suspend fun syncGalleryWithDatabase() = gallerySync.sync()

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
        } catch (e: Exception) {
            android.util.Log.w(TAG, "rotatePhoto failed for ${photo.filePath}", e)
        }
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
                    mediaGallery.delete(file.name)
                }
                else -> {
                    val uri = Uri.parse(path)
                    when (uri.scheme) {
                        "file" -> uri.path?.let { File(it).delete() }
                        "content" -> context.contentResolver.delete(uri, null, null)
                    }
                }
            }
        } catch (e: Exception) {
            // DB row still goes away below; the orphaned file is the only leak.
            android.util.Log.w(TAG, "deletePhoto file cleanup failed for ${photo.filePath}", e)
        }
        dao.deleteById(photo.id)
    }

    /**
     * Resolves a photo to a private file suitable for FileProvider sharing (M-12).
     * Legacy content:// rows are copied into private storage — completing the
     * migration sync Pass 1 would do — and the DB row is updated so every future
     * load/share uses the private copy. Returns null when the bytes are gone.
     */
    suspend fun resolveShareFile(photo: PhotoEntity): File? = withContext(Dispatchers.IO) {
        if (photo.filePath.startsWith("/")) {
            return@withContext File(photo.filePath).takeIf { it.exists() && it.length() > 0L }
        }
        val file = files.copyFromUri("photo_${photo.id}.jpg", Uri.parse(photo.filePath))
            ?: return@withContext null
        try {
            dao.updateFilePath(photo.id, file.absolutePath)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Share migration update failed for photo ${photo.id}", e)
        }
        file
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

    /** Byte size of the content behind [uri], or -1 when it can't be determined. */
    private fun queryContentSize(uri: Uri): Long = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) { -1L }

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
                parseExifTimestamp(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                    exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
                )
            }
        } catch (_: Exception) { null }
    }

    /**
     * EXIF datetimes are zone-naive wall-clock strings. When the photo carries its
     * own UTC offset (TAG_OFFSET_TIME_ORIGINAL, e.g. "+05:30"), interpret the wall
     * time in THAT zone — a photo taken abroad must not shift days just because the
     * import happens back home. Without an offset, the device's local zone is
     * assumed (the historical behavior).
     */
    private fun parseExifTimestamp(dateStr: String?, offset: String?): Long? {
        if (dateStr == null) return null
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        // L-4: strict parsing — these dates feed the forgery checks, and lenient
        // SimpleDateFormat rolls garbage like "2026:99:99" into a valid-looking date.
        fmt.isLenient = false
        if (!offset.isNullOrBlank()) {
            try { fmt.timeZone = TimeZone.getTimeZone("GMT$offset") } catch (_: Exception) { }
        }
        return try { fmt.parse(dateStr)?.time } catch (_: Exception) { null }
    }

    /**
     * Forgery checks for imports — container magic bytes plus timestamp
     * cross-validation ([ExifIntegrity]). Every signal is read defensively: an
     * unreadable stream or missing column skips its check rather than rejecting a
     * legitimate photo.
     */
    private fun checkImportIntegrity(uri: Uri, captureMillis: Long): ExifIntegrity.Verdict {
        // 1. The leading bytes must be a real image container — extension/MIME lie,
        //    magic numbers don't.
        try {
            val header = ByteArray(16)
            val read = context.contentResolver.openInputStream(uri)?.use { it.read(header) } ?: -1
            if (read >= 12 && ExifIntegrity.sniffFormat(header) == null) {
                return ExifIntegrity.Verdict.Tampered("unrecognized image container")
            }
        } catch (_: Exception) { /* unreadable header → let the decoder decide later */ }

        // 2. DateTimeDigitized for the internal-consistency check.
        val digitized = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                parseExifTimestamp(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
                    exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
                        ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                )
            }
        } catch (_: Exception) { null }

        // 3. Filesystem mtime (MediaStore stores seconds). 0 = unknown → check skipped.
        var fileModifiedMillis = 0L
        try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Images.Media.DATE_MODIFIED), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    if (col >= 0) {
                        val sec = cursor.getLong(col)
                        if (sec > 0) fileModifiedMillis = sec * 1000
                    }
                }
            }
        } catch (_: Exception) { }

        return ExifIntegrity.evaluate(captureMillis, digitized, fileModifiedMillis, System.currentTimeMillis())
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
     * Fills in locationName after the photo row already exists and the result has
     * been returned to the UI — geocoding is a network call that used to block the
     * "Color Match!" card for seconds (B11). Room Flows re-emit on the update, so
     * the By Place gallery picks the name up whenever it lands.
     */
    private fun resolveLocationNameAsync(id: Long, lat: Double?, lon: Double?) {
        if (lat == null || lon == null) return
        repoScope.launch {
            val name = location.reverseGeocode(lat, lon) ?: return@launch
            dao.updateLocation(id, lat, lon, name)
        }
    }

}

sealed class SaveResult {
    data class Success(val uri: Uri, val validation: ColorValidator.ValidationResult, val photoId: Long) : SaveResult()
    data class ValidationFailed(val validation: ColorValidator.ValidationResult) : SaveResult()
    object StorageError : SaveResult()
}

sealed class ImportResult {
    data class Success(val uri: Uri, val validation: ColorValidator.ValidationResult, val photoId: Long) : ImportResult()
    data class ValidationFailed(val validation: ColorValidator.ValidationResult) : ImportResult()
    object NoDateMetadata : ImportResult()
    data class NotTakenToday(val dateTaken: Long) : ImportResult()
    object AlreadyImported : ImportResult()
    object StorageError : ImportResult()
    /** The file's metadata failed forgery checks ([ExifIntegrity]) — date can't be trusted. */
    object MetadataTampered : ImportResult()
}
