package com.dpm.pegdown.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.google.android.gms.location.*

class LocationTracker(
    private val context: Context,
    private val listener: LocationUpdateListener,
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2000L
    ).setMinUpdateIntervalMillis(1000L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                processLocation(location)
            }
        }
    }

    private val androidLocationListener = LocationListener { location ->
        processLocation(location)
    }

    private fun processLocation(location: Location) {
        val speedKmH = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        listener.onLocationUpdate(location, speedKmH)
    }

    fun start() {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    android.os.Looper.getMainLooper()
                )
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    2f,
                    androidLocationListener
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationManager.removeUpdates(androidLocationListener)
    }
}