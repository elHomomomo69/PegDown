package com.dpm.pegdown.sensor

import kotlin.math.*

/** Conservative near-upright hypothesis, never a measurement of true zero bank. */
internal class StraightRideReference {
    data class Observation(
        val startNanos: Long, val endNanos: Long,
        val minimumSpeedMps: Double, val maximumSpeedMps: Double,
        val startCourseDegrees: Double, val endCourseDegrees: Double,
        val startCourseAccuracyDegrees: Double, val endCourseAccuracyDegrees: Double,
        val maximumSpeedAccuracyMps: Double, val maximumPositionAccuracyMetres: Double,
        val fixAgeSeconds: Double, val alongAccelerationMps2: Double,
        val gyroYawRadps: Double, val maximumRollRateRadps: Double,
        val inferredBankDegrees: Double, val inferredPitchDegrees: Double,
        val gravityMagnitudeMps2: Double,
    )
    data class Trace(
        val reason: String,
        val qualified: Boolean = false,
        val durationSeconds: Double = 0.0,
        val observationCount: Int = 0,
        val courseRangeDegrees: Double? = null,
        val courseUncertaintyBankDegrees: Double? = null,
        val referenceBankDegrees: Double? = null,
        val appliedBankDeltaDegrees: Double = 0.0,
    )
    private val observations = ArrayDeque<Observation>()
    fun reset(reason: String): Trace {
        observations.clear()
        return Trace(reason)
    }
    fun observe(o: Observation): Trace {
        val dt = (o.endNanos - o.startNanos) / 1e9
        val values = listOf(o.minimumSpeedMps, o.maximumSpeedMps,
            o.startCourseDegrees, o.endCourseDegrees,
            o.startCourseAccuracyDegrees, o.endCourseAccuracyDegrees,
            o.maximumSpeedAccuracyMps, o.maximumPositionAccuracyMetres,
            o.fixAgeSeconds, o.alongAccelerationMps2, o.gyroYawRadps,
            o.maximumRollRateRadps, o.inferredBankDegrees, o.inferredPitchDegrees,
            o.gravityMagnitudeMps2)
        val reason = when {
            values.any { !it.isFinite() } -> "NONFINITE"
            dt !in .3..2.5 -> "INTERVAL"
            o.minimumSpeedMps < MINIMUM_SPEED_MPS || o.maximumSpeedMps < o.minimumSpeedMps -> "SPEED"
            o.startCourseDegrees !in 0.0..360.0 || o.endCourseDegrees !in 0.0..360.0 ||
                o.startCourseAccuracyDegrees !in 0.0..1.5 ||
                o.endCourseAccuracyDegrees !in 0.0..1.5 ||
                o.maximumSpeedAccuracyMps !in 0.0..0.5 ||
                o.maximumPositionAccuracyMetres !in 0.0..10.0 ||
                o.fixAgeSeconds !in 0.0..1.0 -> "GPS_QUALITY"
            abs(o.alongAccelerationMps2) > .5 -> "ACCELERATING_OR_BRAKING"
            abs(o.gyroYawRadps) > MAXIMUM_YAW_RATE_RADPS ||
                o.maximumRollRateRadps !in 0.0..MAXIMUM_ROLL_RATE_RADPS -> "TURNING"
            abs(o.inferredBankDegrees) > 1.0 -> "GRAVITY_NOT_UPRIGHT"
            abs(o.gravityMagnitudeMps2 - 9.80665) > .5 -> "GRAVITY_MAGNITUDE"
            else -> null
        }
        if (reason != null) return reset(reason)
        if (observations.lastOrNull()?.endNanos?.let { it != o.startNanos } == true)
            observations.clear()
        observations.addLast(o)
        while (observations.size > 1 &&
            observations.elementAt(1).startNanos <= o.endNanos - WINDOW_NANOS)
            observations.removeFirst()
        val seconds = (o.endNanos - observations.first().startNanos) / 1e9
        var heading = 0.0
        var minimumHeading = 0.0
        var maximumHeading = 0.0
        var gyroHeading = 0.0
        var weightedBank = 0.0
        for (v in observations) {
            val span = (v.endNanos - v.startNanos) / 1e9
            heading += ((v.endCourseDegrees - v.startCourseDegrees + 540.0) % 360.0) - 180.0
            minimumHeading = minOf(minimumHeading, heading)
            maximumHeading = maxOf(maximumHeading, heading)
            gyroHeading += v.gyroYawRadps * span
            weightedBank += v.inferredBankDegrees * span
        }
        val courseRange = maximumHeading - minimumHeading
        val sigma = hypot(observations.first().startCourseAccuracyDegrees, o.endCourseAccuracyDegrees)
        val maximumSpeed = observations.maxOf { it.maximumSpeedMps }
        val uncertaintyBank = Math.toDegrees(atan(maximumSpeed *
            Math.toRadians(abs(heading) + sigma) / seconds / 9.80665))
        val trace = Trace("WAITING", durationSeconds = seconds, observationCount = observations.size,
            courseRangeDegrees = courseRange, courseUncertaintyBankDegrees = uncertaintyBank,
            referenceBankDegrees = weightedBank / seconds)
        val windowReason = when {
            courseRange > 2.0 || abs(Math.toDegrees(gyroHeading)) > 2.0 -> "COURSE_CHANGING"
            observations.maxOf { it.inferredPitchDegrees } -
                observations.minOf { it.inferredPitchDegrees } > 2.0 -> "PITCH_CHANGING"
            seconds >= WINDOW_NANOS / 1e9 && uncertaintyBank > 2.0 -> "COURSE_UNCERTAIN"
            else -> null
        }
        if (windowReason != null) {
            observations.clear()
            return trace.copy(reason = windowReason)
        }
        return if (seconds >= WINDOW_NANOS / 1e9)
            trace.copy(reason = "QUALIFIED", qualified = true) else trace
    }
    companion object {
        const val WINDOW_NANOS = 5_000_000_000L
        const val MINIMUM_SPEED_MPS = 8.0
        const val MAXIMUM_ROLL_RATE_RADPS = .04
        const val MAXIMUM_YAW_RATE_RADPS = .01
        const val MAXIMUM_CORRECTION_DEGREES_PER_SECOND = .15
        const val BANK_DEADBAND_DEGREES = 1.0
    }
}
