package com.vmm5.notes.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Where the phone is, cheaply: the last position any app asked for, no
 * new fix (so no GPS battery cost and no waiting). The geocoder turns it
 * into a neighbourhood and town when it answers; the coordinates are
 * kept either way.
 */
object Where {
    data class Fix(val lat: Double, val lon: Double, val place: String?)

    fun allowed(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun allowedInBackground(c: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun lastKnown(c: Context): Fix? {
        if (!allowed(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (p in lm.allProviders) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        val b = best ?: return null
        return Fix(b.latitude, b.longitude, describe(c, b.latitude, b.longitude))
    }

    /** "Harvard Square, Cambridge" or "Boston, MA" or null; needs the network, so it may be null offline. */
    fun describe(c: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null else {
            @Suppress("DEPRECATION")
            val a = Geocoder(c, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.let {
                val parts = listOfNotNull(it.subLocality, it.locality ?: it.subAdminArea, it.adminArea?.takeIf { s -> it.locality == null })
                parts.distinct().joinToString(", ").ifBlank { null }
            }
        }
    } catch (e: Exception) { null }

    fun format(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)
}
