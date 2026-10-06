package com.dpm.pegdown.sensor

import kotlin.math.abs
import kotlin.math.exp

/**
 * Longitudinal acceleration for a phone fixed to the motorcycle.
 * The calibrated, horizontal projection of screen -Z assumes no mounting yaw.
 * Android linear acceleration still contains gravity-estimation errors.
 */
internal class ForwardAccelerationProcessor {
    private var lastTimestamp = 0L
    private var filteredG = 0.0

    fun reset() {
        lastTimestamp = 0L
        filteredG = 0.0
    }

    fun update(timestampNanos: Long, linearAcceleration: ImuVector, calibrationUp: ImuVector): Double? {
        if (timestampNanos <= 0L || !linearAcceleration.finite() ||
            !calibrationUp.finite() || calibrationUp.length() < 1e-6
        ) return null
        val up = calibrationUp.unit()
        val screenForward = ImuVector(0.0, 0.0, -1.0)
        val horizontalForward = screenForward - up * screenForward.dot(up)
        if (horizontalForward.length() < 1e-3) return null
        // This axis stays fixed in the mounted phone, including when heading changes.
        val sampleG = (linearAcceleration.dot(horizontalForward.unit()) / 9.81).coerceIn(-2.0, 2.0)
        if (lastTimestamp == 0L) {
            lastTimestamp = timestampNanos
            return 0.0
        }
        if (timestampNanos <= lastTimestamp) return null
        val dt = (timestampNanos - lastTimestamp) / 1e9
        lastTimestamp = timestampNanos
        if (dt > 0.25) {
            filteredG = 0.0
            return 0.0
        }
        // Filter the signed signal before deadband, sign separation and peak detection.
        val alpha = 1.0 - exp(-dt / 0.35)
        filteredG += alpha * (sampleG - filteredG)
        return if (abs(filteredG) < 0.05) 0.0 else filteredG
    }
}
