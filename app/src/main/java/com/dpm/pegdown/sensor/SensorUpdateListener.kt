package com.dpm.pegdown.sensor

import android.hardware.Sensor
import com.dpm.pegdown.model.TourLogEntry

interface SensorUpdateListener {
    fun onLeanAngleUpdate(current: Double, tempL: Double, tempR: Double, tourL: Double, tourR: Double)
    fun onAccelerationUpdate(accel: Double, brake: Double, tourMaxAccel: Double, tourMaxBrake: Double)
    fun onPeakRecorded(entry: TourLogEntry)

    fun onDiagnosticCalibration(metadata: String) = Unit

    fun onDiagnosticSensorSample(
        sensor: Sensor,
        timestampNanos: Long,
        accuracy: Int,
        values: FloatArray,
        estimatedLeanDegrees: Double,
        calibrated: Boolean,
    ) = Unit
}
