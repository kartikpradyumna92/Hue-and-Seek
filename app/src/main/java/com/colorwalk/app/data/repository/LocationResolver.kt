package com.colorwalk.app.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * GPS fixes and reverse geocoding (I-1: extracted from PhotoRepository).
 */
internal class LocationResolver(private val context: Context) {

    private companion object { const val TAG = "LocationResolver" }

    /**
     * Actively requests a fresh GPS fix (bounded at 5s), falling back to the
     * passive last-known cache — reading only the cache silently saved photos
     * without coordinates whenever no other app had recently obtained a fix.
     */
    // Lint can't see that the surrounding catch(Exception) already handles a
    // rejected/revoked permission via SecurityException — this isn't a real gap.
    @SuppressLint("MissingPermission")
    suspend fun getFreshLocation(): Pair<Double?, Double?> = try {
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
    } catch (e: Exception) {
        // DEBUG: fires on every capture while location permission is denied — that's
        // a normal state, not a fault, but the cause should still be findable.
        Log.d(TAG, "getFreshLocation unavailable: $e")
        Pair(null, null)
    }

    suspend fun reverseGeocode(lat: Double?, lon: Double?): String? {
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
        } catch (e: Exception) {
            Log.d(TAG, "reverseGeocode failed: $e") // network-dependent — common, not a fault
            null
        }
    }
}
