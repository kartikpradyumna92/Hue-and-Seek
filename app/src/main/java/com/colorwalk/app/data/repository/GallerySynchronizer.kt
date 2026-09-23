package com.colorwalk.app.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.data.db.PhotoDao
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.domain.PhotoProvenance
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.WALK_COLORS
import com.colorwalk.app.domain.colorForDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * Startup MediaStore↔DB reconciliation (I-1: extracted from PhotoRepository so the
 * app's most intricate logic is an independently constructible, testable unit).
 *
 * Pass 0 — dedupe DB rows sharing a filePath (legacy sync bug cleanup).
 * Pass 1 — direct migration: rows still holding content:// URIs are copied to
 *          private storage (reliable path for update installs).
 * Pass 2 — MediaStore scan: recovery after a fresh reinstall (new UID, no implicit
 *          content:// access), guarded by deletion tombstones (A2) and dedup on
 *          exact + second-truncated timestamps (B6).
 *
 * C4: file I/O happens outside any transaction; DB writes are batched per pass.
 */
internal class GallerySynchronizer(
    private val context: Context,
    private val dao: PhotoDao,
    private val db: AppDatabase,
    private val files: PhotoFileStore,
    private val mediaGallery: MediaStoreGallery = MediaStoreGallery(context)
) {

    private companion object {
        const val TAG = "GallerySynchronizer"
        const val KEY_REPUBLISHED_IDS = "album_republished_ids"
        // A private file younger than this may still have its own publish in flight
        // (the capture path defers publishing behind the GPS fix).
        const val REPUBLISH_MIN_AGE_MS = 10L * 60 * 1000
    }

    suspend fun sync() = withContext(Dispatchers.IO) {

        // Leftovers of writes interrupted by process death (BUG-026).
        files.sweepTempFiles()

        // ── Pass 0: deduplicate existing DB rows by filePath ─────────────────────────
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
            } catch (e: Exception) {
                // URI no longer accessible (e.g. fresh reinstall with different UID).
                // Pass 2 will attempt recovery via the MediaStore query.
                Log.d(TAG, "Pass 1 migration copy failed for photo ${photo.id}: $e")
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

        // existingDays: still needed to trigger content:// URI migration for known days.
        // Reads the frozen per-photo dayIndex (T-1), not a live re-derivation.
        val existingDays = dao.getAllPhotoDayIndices().toHashSet()

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
        } catch (e: Exception) {
            // SecurityException if READ_MEDIA_IMAGES denied — skip Pass 2 gracefully.
            Log.w(TAG, "Pass 2 MediaStore query failed — recovery skipped", e)
            null
        }

        // Collect all Pass 2 file I/O results before writing to DB (C4)
        val pass2PathUpdates = mutableListOf<Pair<Long, String>>()   // (id, recoveredPath)
        val pass2Inserts     = mutableListOf<PhotoEntity>()

        // Every ColorWalk row the scan actually sees — feeds tombstone pruning (M-10).
        val liveMediaNames = HashSet<String>()
        val liveMediaDates = HashSet<Long>()

        cursor?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (!name.startsWith("ColorWalk_")) continue
                liveMediaNames.add(name)

                var dateTaken = c.getLong(dateCol)
                if (dateTaken < 1_000_000_000_000L) {
                    dateTaken = parseDateFromFilename(name) ?: continue
                }
                liveMediaDates.add(dateTaken)

                // Never resurrect a photo the user explicitly deleted (A2) — and never
                // use its MediaStore entry as a migration source either.
                if (name in deletedNames || dateTaken in deletedDates) continue

                // BUG-024: the filename's date was written in the zone the photo was
                // taken in; re-deriving from dateTaken would use the zone the phone
                // is in NOW, which may differ (recovery after traveling).
                val day = dayIndexFromFilename(name) ?: StreakCalculator.epochMillisToDayIndex(dateTaken)
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
                    val recovered = files.copyFromUri(name, mediaUri)
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
                val privateFile = files.copyFromUri(name, mediaUri)
                // Recover what the JPEG itself carries: the user's note
                // (ImageDescription) and the capture provenance (UserComment, M-9 —
                // the color the photo was ACTUALLY captured for plus its measured
                // dominant hex). Photos published before provenance stamping fall
                // back to deriving the color from the date, as before.
                var recoveredDescription: String? = null
                var provenance: PhotoProvenance.Tag? = null
                privateFile?.let { file ->
                    try {
                        val exif = ExifInterface(file.absolutePath)
                        recoveredDescription = exif
                            .getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                            ?.trim()?.ifBlank { null }
                        provenance = PhotoProvenance.parse(
                            exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "EXIF recovery read failed for $name", e)
                    }
                }
                val fallbackColor = colorForDay(dateTaken)
                val colorName = provenance?.colorName ?: fallbackColor.name
                val colorHex = WALK_COLORS.firstOrNull { it.name == colorName }?.hex ?: fallbackColor.hex
                pass2Inserts.add(
                    PhotoEntity(
                        filePath = privateFile?.absolutePath ?: mediaUri.toString(),
                        colorName = colorName,
                        colorHex = colorHex,
                        dateTaken = dateTaken,
                        dayIndex = day,
                        latitude = null,
                        longitude = null,
                        locationName = null,
                        dominantColorHex = provenance?.dominantHex ?: fallbackColor.hex,
                        description = recoveredDescription
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
                for (entity in pass2Inserts) {
                    // BUG-052: the dedup sets came from a snapshot taken before this
                    // pass; a capture landing meanwhile must not be inserted twice.
                    if (dao.getByDateTakenSecond(entity.dateTaken / 1000).isNotEmpty()) continue
                    dao.insert(entity)
                }
            }
        }

        // Only a scan that could see EVERY album copy is evidence of absence. A null
        // cursor saw nothing; and under partial media access (no permission on API
        // 29+, or Android 14 "Selected photos") the query succeeds yet omits copies
        // left by a previous install (BUG-027).
        val completeScan = cursor != null && hasFullMediaAccess()
        if (completeScan) {
            // Tombstones whose MediaStore ghost is gone protect nothing — drop them
            // so the prefs sets stay bounded (M-10).
            DeletionTombstones.pruneOrphaned(context, liveMediaNames, liveMediaDates)
            republishMissingAlbumCopies(liveMediaNames, deletedNames, deletedDates)
        }
    }

    /**
     * BUG-020/BUG-046: rows whose public album copy never landed — imports before
     * they were published, or a capture whose deferred publish died with the
     * process — have nothing to recover from after a reinstall or cloud restore.
     * Publish them from the private file. Each row gets ONE attempt ever, so a copy
     * the user deliberately removed from their system gallery isn't re-added on
     * every launch.
     */
    private suspend fun republishMissingAlbumCopies(
        liveNames: Set<String>,
        deletedNames: Set<String>,
        deletedDates: Set<Long>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val rows = dao.getAllPhotosSnapshot()
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val attempted = prefs.getStringSet(KEY_REPUBLISHED_IDS, emptySet())
            ?.mapNotNullTo(HashSet()) { it.toLongOrNull() } ?: HashSet()
        val cutoff = System.currentTimeMillis() - REPUBLISH_MIN_AGE_MS

        for (row in rows) {
            if (row.id in attempted || !row.filePath.startsWith("/")) continue
            val file = File(row.filePath)
            val name = file.name
            if (!name.startsWith("ColorWalk_") || name in liveNames) continue
            if (name in deletedNames || row.dateTaken in deletedDates) continue
            if (!file.exists() || file.length() == 0L || file.lastModified() > cutoff) continue
            val published = mediaGallery.publishFile(
                file, name, row.dateTaken, row.latitude, row.longitude,
                row.colorName, row.dominantColorHex
            )
            // A transient failure (storage full) keeps its attempt for next launch.
            if (published) attempted += row.id
        }
        // Keep only ids that still exist, so the set stays bounded by the table.
        val liveIds = rows.mapTo(HashSet()) { it.id }
        prefs.edit()
            .putStringSet(KEY_REPUBLISHED_IDS, attempted.filter { it in liveIds }.mapTo(HashSet()) { it.toString() })
            .apply()
    }

    private fun hasFullMediaAccess(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        // With READ_MEDIA_VISUAL_USER_SELECTED declared (manifest), an Android 14+
        // "Selected photos" grant leaves READ_MEDIA_IMAGES denied — so this is false.
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Migrates a DB entry whose filePath may be a stale content:// URI to a private file.
     * If the copy succeeds, updates the DB row so Coil can always load it.
     */
    private suspend fun migrateToPrivateStorage(photoId: Long, filename: String, mediaUri: Uri) {
        val privateFile = files.copyFromUri(filename, mediaUri) ?: return
        try {
            dao.updateFilePath(photoId, privateFile.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "filePath migration update failed for photo $photoId", e)
        }
    }

    /**
     * Recovers a timestamp from "ColorWalk_yyyyMMdd_HHmmss[_SSS].jpg". Filenames carry
     * a millis suffix since v1.12 — restoring it keeps the parsed value identical to
     * the DB's millisecond dateTaken so the sync dedup guard matches exactly (B6).
     */
    /**
     * Local-calendar epoch day encoded in "ColorWalk_yyyyMMdd_…" — the date in the
     * zone the photo was written in, i.e. the day it belongs to (BUG-024). Must
     * agree with MIGRATION_3_4's SQL derivation of the same field.
     */
    internal fun dayIndexFromFilename(name: String): Int? = try {
        val datePart = name.removePrefix("ColorWalk_").substringBefore('_')
        if (!name.startsWith("ColorWalk_") || datePart.length != 8) null
        else LocalDate.parse(datePart, DateTimeFormatter.BASIC_ISO_DATE).toEpochDay().toInt()
    } catch (_: Exception) { null }

    internal fun parseDateFromFilename(name: String): Long? = try {
        val parts = name.removePrefix("ColorWalk_").removeSuffix(".jpg").split("_")
        if (parts.size < 2) null
        else SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .apply { isLenient = false } // L-4: a foreign "ColorWalk_" name must not roll into a fake date
            .parse("${parts[0]}_${parts[1]}")?.time
            ?.plus(parts.getOrNull(2)?.toLongOrNull()?.takeIf { it in 0..999 } ?: 0L)
    } catch (_: Exception) { null }
}
