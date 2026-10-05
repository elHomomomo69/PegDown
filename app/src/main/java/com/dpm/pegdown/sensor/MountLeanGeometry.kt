package com.dpm.pegdown.sensor

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.sqrt

/** Mount geometry for the experimental estimator. Upright calibration cannot determine vehicle-forward mounting yaw. */
internal class MountLeanGeometry(referenceUp: ImuVector, vehicleForward: ImuVector) {
    private val up = referenceUp.unit()
    private val forward = vehicleForward.unit()
    private val right = forward.cross(up).unit()

    init {
        require(referenceUp.finite() && referenceUp.length() > 1e-6)
        require(vehicleForward.finite() && vehicleForward.length() > 1e-6)
        require(abs(up.dot(forward)) < 1e-6)
    }

    /** Current app geometry: angle between gravity projections onto device XY. */
    fun projectedDegrees(currentUp: ImuVector): Double {
        if (sqrt(up.x * up.x + up.y * up.y) < 1e-6 ||
            sqrt(currentUp.x * currentUp.x + currentUp.y * currentUp.y) < 1e-6
        ) return 0.0
        return Math.toDegrees(atan2(
            up.x * currentUp.y - up.y * currentUp.x,
            up.x * currentUp.x + up.y * currentUp.y,
        ))
    }

    /** Vehicle roll for yaw-pitch-roll attitude, provided forward really is the vehicle axis. */
    fun bankDegrees(currentUp: ImuVector): Double =
        Math.toDegrees(atan2(-currentUp.dot(right), currentUp.dot(up)))

    fun pitchDegrees(currentUp: ImuVector): Double =
        Math.toDegrees(asin(currentUp.unit().dot(forward).coerceIn(-1.0, 1.0)))

    /** Signed screen-normal elevation when calibrated upright; independent of portrait/landscape. */
    val screenTiltDegrees: Double
        get() = Math.toDegrees(asin(up.z.coerceIn(-1.0, 1.0)))
}
