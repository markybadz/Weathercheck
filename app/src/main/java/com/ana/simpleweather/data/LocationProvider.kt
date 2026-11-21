package com.ana.simpleweather.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Battery-friendly location: a single "balanced power" (Wi-Fi/cell, city-level)
 * fix when the user opens or refreshes the app. No continuous or background updates.
 */
class LocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)

    fun hasPermission(): Boolean = hasLocationPermission(appContext)

    fun isLocationEnabled(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        val cts = CancellationTokenSource()
        val fresh = try {
            withTimeoutOrNull(15_000) {
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            }
        } catch (e: Exception) {
            null
        } finally {
            cts.cancel()
        }
        if (fresh != null) return fresh
        return try {
            fused.lastLocation.await()
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort city name for coordinates; returns null if the phone has no geocoder. */
    @Suppress("DEPRECATION")
    suspend fun placeName(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            val address = Geocoder(appContext, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull() ?: return@withContext null
            address.locality ?: address.subAdminArea ?: address.adminArea ?: address.countryName
        } catch (e: Exception) {
            null
        }
    }
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
