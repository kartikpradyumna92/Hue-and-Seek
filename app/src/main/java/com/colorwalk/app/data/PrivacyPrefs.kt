package com.colorwalk.app.data

import android.content.Context

/**
 * User-facing location privacy choices (BUG-054, BUG-062). Stored in "app_prefs",
 * which Auto Backup already covers.
 */
object PrivacyPrefs {
    private const val PREFS = "app_prefs"
    private const val KEY_LOCATION_IN_GALLERY = "privacy_location_in_gallery"
    private const val KEY_LOCATION_IN_SHARES  = "privacy_location_in_shares"

    /**
     * Write GPS into the public Pictures/ColorWalk copy. Default ON: it's how
     * places are recovered after a reinstall (location backfill reads it back), and
     * turning it off silently would regress existing users — but it's now a visible,
     * reversible choice, since any app with media + media-location access can read it.
     */
    fun saveLocationInGallery(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCATION_IN_GALLERY, true)

    fun setSaveLocationInGallery(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCATION_IN_GALLERY, enabled).apply()
    }

    /** Keep GPS in photos sent through the share sheet. Default OFF — privacy first. */
    fun includeLocationWhenSharing(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCATION_IN_SHARES, false)

    fun setIncludeLocationWhenSharing(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCATION_IN_SHARES, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
