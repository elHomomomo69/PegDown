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

    private val androidLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            processLocation(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            // Needed for API < 29
        }

        override fun onProviderEnabled(provider: String) {
            // Needed for API < 29
        }

        override fun onProviderDisabled(provider: String) {
            // Needed for API < 29
        }
    }

    private fun processLocation(location: Location) {
        val speedKmH = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        listener.onLocationUpdate(location, speedKmH)
    }

    fun start() {
        // Aktualisiert bestehende Provider-Anfragen, falls sich Berechtigungen
        // seit dem letzten Start geändert haben.
        locationManager.removeUpdates(androidLocationListener)

        val finePermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarsePermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!finePermission && !coarsePermission) return

        try {
            if (
                finePermission &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    2f,
                    androidLocationListener,
                )
            }

            // Mit genauer oder ungefährer Berechtigung kann der Netzwerk-Provider
            // einen Standort liefern; bei nur ungefährer Berechtigung bleibt es
            // bei diesem Provider.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10f,
                    androidLocationListener,
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stop() {
        locationManager.removeUpdates(androidLocationListener)
    }
}