package com.colorwalk.app.data.repository

import android.content.Context

/**
 * Persistent record of photos the user explicitly deleted.
 *
 * Why this exists (bug A2): deleting a photo removes the DB row, the private file,
 * and — best-effort — the MediaStore copy. After a reinstall the MediaStore rows are
 * owned by the previous install's UID, so that delete silently fails, and the next
 * syncGalleryWithDatabase() recovery pass would re-import ("resurrect") the photo.
 * The sync consults these tombstones and never re-imports a deleted photo.
 *
 * Stored in "app_prefs", which is included in the Auto Backup rules, so tombstones
 * survive backup/restore alongside the photo database. Filenames are the primary
 * key (unique ColorWalk_* names); the exact dateTaken millis is a secondary guard
 * for rows whose private filename no longer matches the MediaStore name (e.g. the
 * photo_<id>.jpg files produced by the legacy content-URI migration).
 */
internal object DeletionTombstones {
    private const val PREFS = "app_prefs"
    private const val KEY_NAMES = "deleted_photo_filenames"
    private const val KEY_DATES = "deleted_photo_dates"

    fun record(context: Context, filename: String?, dateTaken: Long) {
        val prefs = prefs(context)
        // getStringSet's return value must never be mutated — always copy.
        val names = HashSet(prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet())
        val dates = HashSet(prefs.getStringSet(KEY_DATES, emptySet()) ?: emptySet())
        if (filename != null) names.add(filename)
        dates.add(dateTaken.toString())
        prefs.edit()
            .putStringSet(KEY_NAMES, names)
            .putStringSet(KEY_DATES, dates)
            .apply()
    }

    /** Un-tombstones a photo the user deliberately re-captured or re-imported. */
    fun clear(context: Context, filename: String, dateTaken: Long) {
        val prefs = prefs(context)
        val names = prefs.getStringSet(KEY_NAMES, null)
        val dates = prefs.getStringSet(KEY_DATES, null)
        val dateStr = dateTaken.toString()
        val hasName = names?.contains(filename) == true
        val hasDate = dates?.contains(dateStr) == true
        if (!hasName && !hasDate) return
        val editor = prefs.edit()
        if (hasName) editor.putStringSet(KEY_NAMES, HashSet(names!!).apply { remove(filename) })
        if (hasDate) editor.putStringSet(KEY_DATES, HashSet(dates!!).apply { remove(dateStr) })
        editor.apply()
    }

    /**
     * Drops tombstones that no longer guard anything (M-10 — the sets grew without
     * bound). A tombstone's ONLY job is to stop syncGalleryWithDatabase() from
     * re-importing a matching MediaStore row; once no such row exists, the tombstone
     * is inert and safe to drop. (Age-based pruning would be wrong: a non-owned
     * MediaStore copy from a previous install can outlive any retention window, and
     * dropping its tombstone would resurrect the A2 bug.)
     *
     * Call ONLY with a complete, successful MediaStore scan — [liveNames]/[liveDates]
     * from a failed or permission-denied query would wipe every tombstone.
     */
    fun pruneOrphaned(context: Context, liveNames: Set<String>, liveDates: Set<Long>) {
        val prefs = prefs(context)
        val names = prefs.getStringSet(KEY_NAMES, null)
        val dates = prefs.getStringSet(KEY_DATES, null)
        if (names.isNullOrEmpty() && dates.isNullOrEmpty()) return
        val keptNames = names?.filterTo(HashSet()) { it in liveNames }
        val keptDates = dates?.filterTo(HashSet()) { it.toLongOrNull() in liveDates }
        val editor = prefs.edit()
        var dirty = false
        if (names != null && keptNames!!.size != names.size) {
            editor.putStringSet(KEY_NAMES, keptNames); dirty = true
        }
        if (dates != null && keptDates!!.size != dates.size) {
            editor.putStringSet(KEY_DATES, keptDates); dirty = true
        }
        if (dirty) editor.apply()
    }

    fun deletedFilenames(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_NAMES, emptySet())?.toHashSet() ?: emptySet()

    fun deletedDates(context: Context): Set<Long> =
        prefs(context).getStringSet(KEY_DATES, emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toHashSet() ?: emptySet()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
