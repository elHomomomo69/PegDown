package com.dpm.pegdown.sensor

import com.dpm.pegdown.model.TourLogEntry

interface SensorUpdateListener {
    fun onLeanAngleUpdate(current: Double, tempL: Double, tempR: Double, tourL: Double, tourR: Double)
    fun onAccelerationUpdate(accel: Double, brake: Double, tourMaxAccel: Double, tourMaxBrake: Double)
    fun onPeakRecorded(entry: TourLogEntry)
}