package com.dpm.pegdown.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

class LocationTracker(
    private val context: Context,
    private val listener: LocationUpdateListener,
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

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
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    2f,
                    androidLocationListener,
                )
                
                // Optional: Auch den Network Provider nutzen für schnellere erste Fixes
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        2f,
                        androidLocationListener
                    )
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stop() {
        locationManager.removeUpdates(androidLocationListener)
    }
}