package com.dpm.pegdown.location

import android.location.Location

interface LocationUpdateListener {
    fun onLocationUpdate(location: Location, speedKmH: Double)
}