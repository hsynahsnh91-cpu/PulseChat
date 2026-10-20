package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** One-shot current location (no background or live location sharing). */
object LocationHelper {
    @SuppressLint("MissingPermission")
    fun current(context: Context, onResult: (Location?) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> { onResult(null); return }
        }
        try {
            LocationManagerCompat.getCurrentLocation(lm, provider, null, ContextCompat.getMainExecutor(context)) { location -> onResult(location) }
        } catch (e: SecurityException) {
            onResult(null)
        }
    }
}
