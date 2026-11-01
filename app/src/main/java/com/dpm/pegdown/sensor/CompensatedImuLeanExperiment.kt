package com.dpm.pegdown.sensor

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal data class ImuVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(v: ImuVector) = ImuVector(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: ImuVector) = ImuVector(x - v.x, y - v.y, z - v.z)
    operator fun times(scale: Double) = ImuVector(x * scale, y * scale, z * scale)
    fun dot(v: ImuVector) = x * v.x + y * v.y + z * v.z
    fun cross(v: ImuVector) = ImuVector(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
    fun length() = sqrt(dot(this))
    fun unit() = this * (1.0 / length().coerceAtLeast(1e-12))
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = ImuVector(0.0, 0.0, 0.0)
        val UP = ImuVector(0.0, 0.0, 1.0)
    }
}

internal data class ImuQuaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(q: ImuQuaternion) = ImuQuaternion(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
    )
    fun inverse() = ImuQuaternion(w, -x, -y, -z)
    fun unit(): ImuQuaternion {
        val norm = sqrt(w * w + x * x + y * y + z * z)
        require(norm.isFinite() && norm > 1e-12)
        return ImuQuaternion(w / norm, x / norm, y / norm, z / norm)
    }
    fun rotate(v: ImuVector): ImuVector {
        val axis = ImuVector(x, y, z)
        return v + axis.cross(v) * (2.0 * w) + axis.cross(axis.cross(v)) * 2.0
    }
    fun fractionalRotation(fraction: Double): ImuQuaternion {
        val q = if (w < 0.0) ImuQuaternion(-w, -x, -y, -z) else this
        val axis = ImuVector(q.x, q.y, q.z)
        if (axis.length() < 1e-12) return IDENTITY
        return axisAngle(axis.unit(), 2.0 * atan2(axis.length(), q.w) * fraction)
    }
    companion object {
        val IDENTITY = ImuQuaternion(1.0, 0.0, 0.0, 0.0)
        fun axisAngle(axis: ImuVector, radians: Double): ImuQuaternion {
            val vector = axis.unit() * sin(radians / 2.0)
            return ImuQuaternion(cos(radians / 2.0), vector.x, vector.y, vector.z)
        }
        fun between(from: ImuVector, to: ImuVector): ImuQuaternion {
            val a = from.unit()
            val b = to.unit()
            val dot = a.dot(b).coerceIn(-1.0, 1.0)
            if (dot < -0.999999) {
                val other = if (abs(a.x) < 0.8) ImuVector(1.0, 0.0, 0.0) else ImuVector(0.0, 1.0, 0.0)
                return axisAngle(a.cross(other), Math.PI)
            }
            val cross = a.cross(b)
            return ImuQuaternion(1.0 + dot, cross.x, cross.y, cross.z).unit()
        }
    }
}

/**
 * Experimental estimator: full body-axis gyro integration and GNSS-compensated
 * accelerometer correction. No chassis-bank reference is inferred from GPS.
 *
 * forwardInDevice must be the motorcycle's actual forward axis in device coordinates.
 * Projecting negative screen-Z onto the calibration plane is only a mounting assumption.
 * GPS height/vertical acceleration, moving rider and chassis reference truth are absent.
 */
internal class CompensatedImuLeanExperiment(
    referenceUp: ImuVector,
    forwardInDevice: ImuVector,
    initialOrientation: ImuQuaternion,
    initialTimestampNanos: Long,
    private val gyroBias: ImuVector = ImuVector.ZERO,
    private val compensateMotion: Boolean = true,
    private val historicalAlignment: Boolean = true,
    private val compensateLongitudinal: Boolean = true,
    private val compensateLateral: Boolean = true,
    private val checkYawConsistency: Boolean = false,
    private val allowSlowCourseCorrections: Boolean = false,
    private val matchGpsInterval: Boolean = false,
    // SensorProcessor enables this for live estimation; false preserves historical replay controls.
    private val qualifyStationaryCorrections: Boolean = false,
    private val courseLagSeconds: Double = 0.0,
    // Diagnostic ablation only: retain every input/history, omit specified updates.
    private val withheldReferenceTimes: Set<Long> = emptySet(),
) {
    data class Fix(
        val timestampNanos: Long,
        val arrivalNanos: Long,
        val speedMps: Double,
        val bearingDegrees: Double,
        val bearingAccuracyDegrees: Double,
        val speedAccuracyMps: Double,
        val positionAccuracyMetres: Double,
        val hasSpeed: Boolean = true,
        val hasBearing: Boolean = true,
        val provider: String = "gps",
    )

    enum class DecisionReason(val rejected: Boolean = false) {
        ACCEPTED, DIAGNOSTIC_HOLDOUT, NON_GPS, OUT_OF_ORDER, WAITING_FOR_COURSE,
        BAD_FIX(true), INVALID_INTERVAL(true), IMPLAUSIBLE_MOTION(true),
        INSUFFICIENT_STATES, ROLLING(true), CHANGING_YAW(true),
        MISSING_HISTORY, MISSING_SPEED_HISTORY, INSUFFICIENT_ACCELERATION,
        IMPLAUSIBLE_GRAVITY(true), LARGE_INNOVATION(true), COURSE_GYRO_MISMATCH(true),
        STATIONARY_UNSTABLE(true),
    }

    data class MotionTrace(
        val intervalStartNanos: Long, val intervalEndNanos: Long,
        val intervalSeconds: Double, val binCount: Int,
        val maxRollRate: Double, val yawRateRange: Double,
        val meanGyroYawRate: Double, val sigmaLateralMps2: Double,
    )

    data class StationaryTrace(
        val intervalStartNanos: Long,
        val intervalEndNanos: Long,
        val historyCovered: Boolean,
        val maxMeanAccelerationDifferenceMps2: Double? = null,
        val maxMeanAngularRateRadps: Double? = null,
    ) {
        val qualified: Boolean get() = historyCovered &&
            maxMeanAccelerationDifferenceMps2?.let { it <= STATIONARY_ACCELERATION_DELTA_MPS2 } == true &&
            maxMeanAngularRateRadps?.let { it <= STATIONARY_ANGULAR_RATE_RADPS } == true
    }

    data class GravityTrace(
        val measured: ImuVector,
        val predictedMovement: ImuVector,
        val inferredGravity: ImuVector,
        val vehicleAcceleration: ImuVector,
        val courseRate: Double,
        val meanGyroYawRate: Double?,
        val predictedBank: Double,
        val measuredBank: Double,
        val inferredBank: Double,
        val predictedPitch: Double,
        val inferredPitch: Double,
        val gain: Double,
        val currentBankBefore: Double,
        val currentBankAfter: Double? = null,
        val rawAccelerationPeakMps2: Double = Double.NaN,
        val rawAccelerationRmsDeviationMps2: Double = Double.NaN,
        val accelerationSampleCount: Int = 0,
        val requestedRotationDegrees: Double? = null,
        val appliedRotationDegrees: Double? = null,
        val stationaryTrace: StationaryTrace? = null,
    )

    data class CorrectionDecision(
        val timestampNanos: Long,
        val reason: DecisionReason,
        val maxRollRate: Double? = null,
        val yawRateRange: Double? = null,
        val gravityMagnitude: Double? = null,
        val innovationDegrees: Double? = null,
        val gravityTrace: GravityTrace? = null,
        val courseRate: Double? = null,
        val meanGyroYawRate: Double? = null,
        val yawDiscrepancyLimit: Double? = null,
        val motionTrace: MotionTrace? = null,
    )

    private var activeMotionTrace: MotionTrace? = null
    private val decisionLog = mutableListOf<CorrectionDecision>()
    val decisions: List<CorrectionDecision>
        get() = decisionLog.toList()

    val lastDecision: CorrectionDecision?
        get() = decisionLog.lastOrNull()

    private fun record(input: CorrectionDecision) {
        val motion = input.motionTrace ?: activeMotionTrace
        val decision = input.copy(
            maxRollRate = input.maxRollRate ?: motion?.maxRollRate,
            yawRateRange = input.yawRateRange ?: motion?.yawRateRange,
            motionTrace = motion,
        )
        if (decisionLog.size >= 1024) decisionLog.removeAt(0)
        decisionLog.add(decision)
        if (decision.reason.rejected) rejectedCorrections++
        if (decision.reason == DecisionReason.ACCEPTED) acceptedCorrections++
    }

    private data class State(
        val timestampNanos: Long,
        var orientation: ImuQuaternion,
        val angularRate: ImuVector,
    )
    private data class Acceleration(val timestampNanos: Long, val value: ImuVector)

    private val calibrationUp = referenceUp.unit()
    private val forward = forwardInDevice.unit()
    private val geometry = MountLeanGeometry(calibrationUp, forward)
    private var orientation = initialOrientation.unit()
    private var lastGyroTime = initialTimestampNanos
    private var previousFix: Fix? = null
    private val states = ArrayDeque<State>()
    private val acceleration = ArrayDeque<Acceleration>()
    private val speedHistory = ArrayDeque<Fix>()

    var acceptedCorrections = 0
        private set
    var rejectedCorrections = 0
        private set
    var gyroGaps = 0
        private set

    init {
        require(abs(forward.dot(calibrationUp)) < 0.01)
        require(courseLagSeconds.isFinite() && courseLagSeconds in 0.0..2.0)
        require(courseLagSeconds == 0.0 || historicalAlignment)
        states.addLast(State(initialTimestampNanos, orientation, ImuVector.ZERO))
    }

    private fun up(q: ImuQuaternion) = q.inverse().rotate(ImuVector.UP).unit()

    /** Same XY projection as the current app, for a like-for-like recorded comparison. */
    val leanDegrees: Double
        get() = geometry.projectedDegrees(up(orientation))

    /** Bank about the supplied vehicle-forward axis, used for synthetic ground truth. */
    val chassisBankDegrees: Double
        get() = geometry.bankDegrees(up(orientation))

    fun onGyroscope(timestampNanos: Long, measuredRate: ImuVector) {
        if (!measuredRate.finite() || timestampNanos <= lastGyroTime) return
        val dt = (timestampNanos - lastGyroTime) / 1e9
        lastGyroTime = timestampNanos
        val rate = measuredRate - gyroBias
        if (dt > 0.15 || rate.length() > 20.0) {
            gyroGaps++
            states.clear()
            acceleration.clear()
            speedHistory.clear()
            previousFix = null
        } else if (rate.length() > 1e-12) {
            orientation = (orientation * ImuQuaternion.axisAngle(rate, rate.length() * dt)).unit()
        }
        states.addLast(State(timestampNanos, orientation, rate))
        prune(timestampNanos)
    }

    fun onAccelerometer(timestampNanos: Long, value: ImuVector) {
        if (!value.finite() || value.length() > 80.0) return
        if (acceleration.lastOrNull()?.let { timestampNanos <= it.timestampNanos } == true) return
        acceleration.addLast(Acceleration(timestampNanos, value))
        prune(timestampNanos)
    }

    private fun prune(now: Long) {
        while (states.size > 1 && now - states.first().timestampNanos > 6_000_000_000L) states.removeFirst()
        while (acceleration.isNotEmpty() &&
            now - acceleration.first().timestampNanos > 6_000_000_000L
        ) acceleration.removeFirst()
        while (speedHistory.size > 2 && now - speedHistory.first().timestampNanos > 6_000_000_000L)
            speedHistory.removeFirst()
    }

    fun onFix(fix: Fix) {
        activeMotionTrace = null
        if (fix.provider != "gps") {
            record(CorrectionDecision(fix.timestampNanos, DecisionReason.NON_GPS))
            return
        }
        val previous = previousFix
        if (previous != null && fix.timestampNanos <= previous.timestampNanos) {
            record(CorrectionDecision(fix.timestampNanos, DecisionReason.OUT_OF_ORDER))
            return
        }
        val age = (fix.arrivalNanos - fix.timestampNanos) / 1e9
        val validSpeed = fix.hasSpeed && fix.speedMps.isFinite() && fix.speedMps in 0.0..80.0 &&
            fix.speedAccuracyMps.isFinite() && fix.speedAccuracyMps in 0.0..1.0
        val validPosition = fix.positionAccuracyMetres.isFinite() &&
            fix.positionAccuracyMetres in 0.0..15.0
        if (!validSpeed || !validPosition || age !in 0.0..2.5) {
            previousFix = null
            speedHistory.clear()
            record(CorrectionDecision(fix.timestampNanos, DecisionReason.BAD_FIX))
            return
        }
        previousFix = fix
        speedHistory.addLast(fix)
        if (fix.speedMps <= 0.4) {
            val dt = previous?.let { (fix.timestampNanos - it.timestampNanos) / 1e9 } ?: 1.0
            correctAt(fix.timestampNanos, ImuVector.ZERO, dt.coerceIn(0.0, 2.5), 1.0, stationary = true)
            return
        }
        fun goodCourse(value: Fix) = value.hasBearing && value.bearingDegrees.isFinite() &&
            value.bearingDegrees in 0.0..360.0 && value.bearingAccuracyDegrees.isFinite() &&
            value.bearingAccuracyDegrees in 0.0..5.0 &&
            value.speedMps >= if (allowSlowCourseCorrections) 3.0 else 8.0
        if (previous == null || !goodCourse(previous) || !goodCourse(fix)) {
            record(CorrectionDecision(fix.timestampNanos, DecisionReason.WAITING_FOR_COURSE))
            return
        }
        val dt = (fix.timestampNanos - previous.timestampNanos) / 1e9
        if (dt !in 0.3..2.5) {
            record(CorrectionDecision(fix.timestampNanos, DecisionReason.INVALID_INTERVAL))
            return
        }
        val courseDelta = ((fix.bearingDegrees - previous.bearingDegrees + 540.0) % 360.0) - 180.0
        val headingRate = Math.toRadians(courseDelta) / dt
        // Shift the course-value reference, not callback arrival or GPS fix validity.
        // Speed is assumed current: interpolate historical speeds at shifted endpoints.
        val lagNanos = (courseLagSeconds * 1e9).toLong()
        val start = previous.timestampNanos - lagNanos
        val end = fix.timestampNanos - lagNanos
        val startSpeed = if (lagNanos == 0L) previous.speedMps else speedAt(start)
        val endSpeed = if (lagNanos == 0L) fix.speedMps else speedAt(end)
        if (startSpeed == null || endSpeed == null) {
            record(CorrectionDecision(start + (end - start) / 2, DecisionReason.MISSING_SPEED_HISTORY))
            return
        }
        val minimumSpeed = if (allowSlowCourseCorrections) 3.0 else 8.0
        if (startSpeed < minimumSpeed || endSpeed < minimumSpeed) {
            record(CorrectionDecision(start + (end - start) / 2, DecisionReason.WAITING_FOR_COURSE))
            return
        }
        val speed = (startSpeed + endSpeed) / 2.0
        val alongAcceleration = (endSpeed - startSpeed) / dt
        val lateralAcceleration = speed * headingRate
        if (abs(headingRate) > 0.8 || abs(alongAcceleration) > 6.0 || abs(lateralAcceleration) > 15.0) {
            record(CorrectionDecision(fix.timestampNanos, DecisionReason.IMPLAUSIBLE_MOTION))
            return
        }

        // The derivative represents the interval midpoint, not the later callback.
        val midpoint = start + (end - start) / 2
        val intervalStates = states.filter { it.timestampNanos in start..end }
        if (intervalStates.size < 4) {
            record(CorrectionDecision(midpoint, DecisionReason.INSUFFICIENT_STATES))
            return
        }
        val bins = intervalStates.groupBy {
            (it.timestampNanos - start) / 250_000_000L
        }.values.filter { it.size >= 2 }
        val rates = bins.map { bin ->
            val omega = bin.fold(ImuVector.ZERO) { sum, state -> sum + state.angularRate } * (1.0 / bin.size)
            val gravity = bin.fold(ImuVector.ZERO) { sum, state -> sum + up(state.orientation) }.unit()
            val horizontalForward = (forward - gravity * forward.dot(gravity)).unit()
            abs(omega.dot(horizontalForward)) to -omega.dot(gravity)
        }
        val maxRollRate = rates.maxOfOrNull { it.first } ?: 0.0
        val yawRateRange = if (rates.isEmpty()) 0.0 else
            rates.maxOf { it.second } - rates.minOf { it.second }
        val diagnosticSigma = speed * Math.toRadians(sqrt(
            fix.bearingAccuracyDegrees * fix.bearingAccuracyDegrees +
                previous.bearingAccuracyDegrees * previous.bearingAccuracyDegrees
        )) / dt
        activeMotionTrace = MotionTrace(start, end, dt, rates.size,
            maxRollRate, yawRateRange, rates.map { it.second }.average(), diagnosticSigma)
        if (maxRollRate > 0.25 || rates.size < 2 || yawRateRange > 0.10) {
            val reason = if (maxRollRate > 0.25) DecisionReason.ROLLING else DecisionReason.CHANGING_YAW
            record(CorrectionDecision(midpoint, reason, maxRollRate, yawRateRange))
            return
        }

        val sigmaCourseRate = Math.toRadians(sqrt(
            fix.bearingAccuracyDegrees * fix.bearingAccuracyDegrees +
                previous.bearingAccuracyDegrees * previous.bearingAccuracyDegrees
        )) / dt
        val gyroYaw = rates.map { it.second }.average()
        val slowInterval = startSpeed < 8.0 || endSpeed < 8.0
        val sigmaLateral = speed * sigmaCourseRate
        // Low-speed course is optional and must meet stricter uncertainty limits.
        // Limits use declared errors, not an assumption that a straight road means zero bank.
        if (slowInterval && (previous.bearingAccuracyDegrees > 2.0 ||
                fix.bearingAccuracyDegrees > 2.0 || sigmaLateral > 0.5)
        ) {
            record(CorrectionDecision(midpoint, DecisionReason.WAITING_FOR_COURSE))
            return
        }
        // Experimental consistency gate: three declared course-error scales plus
        // 0.02 rad/s model allowance. This is not a calibrated confidence interval.
        val yawLimit = 3.0 * sigmaCourseRate + 0.02
        if ((checkYawConsistency || slowInterval) && abs(headingRate - gyroYaw) > yawLimit) {
            record(CorrectionDecision(
                midpoint, DecisionReason.COURSE_GYRO_MISMATCH,
                courseRate = headingRate,
                meanGyroYawRate = gyroYaw,
                yawDiscrepancyLimit = yawLimit,
            ))
            return
        }
        // Endpoint velocity difference, expressed along the midpoint course.
        // The original v * headingRate approximation remains the default control.
        val bodyAcceleration = if (matchGpsInterval) ImuVector(
            alongAcceleration * cos(Math.toRadians(courseDelta) / 2.0),
            (startSpeed + endSpeed) * sin(Math.toRadians(courseDelta) / 2.0) / dt,
            0.0,
        ) else ImuVector(alongAcceleration, lateralAcceleration, 0.0)
        val quality = 1.0 / (1.0 + sigmaLateral * sigmaLateral / 4.0)
        correctAt(midpoint, bodyAcceleration, dt, quality, headingRate, gyroYaw,
            intervalStart = if (matchGpsInterval) start else null,
            intervalEnd = if (matchGpsInterval) end else null,
        )
    }

    private fun speedAt(timestamp: Long): Double? {
        val points = speedHistory.toList()
        val afterIndex = points.indexOfFirst { it.timestampNanos >= timestamp }
        if (afterIndex < 0) return null
        val after = points[afterIndex]
        if (after.timestampNanos == timestamp) return after.speedMps
        if (afterIndex == 0) return null
        val before = points[afterIndex - 1]
        val span = after.timestampNanos - before.timestampNanos
        if (span <= 0L || span > 2_500_000_000L) return null
        val fraction = (timestamp - before.timestampNanos).toDouble() / span
        return before.speedMps + fraction * (after.speedMps - before.speedMps)
    }

    private fun stateAt(timestamp: Long): ImuQuaternion? {
        val afterIndex = states.indexOfFirst { it.timestampNanos >= timestamp }
        if (afterIndex < 0) return null
        val after = states.elementAt(afterIndex)
        if (after.timestampNanos == timestamp) return after.orientation
        if (afterIndex == 0) return null
        val before = states.elementAt(afterIndex - 1)
        val fraction = (timestamp - before.timestampNanos).toDouble() /
            (after.timestampNanos - before.timestampNanos)
        val relative = before.orientation.inverse() * after.orientation
        return (before.orientation * relative.fractionalRotation(fraction)).unit()
    }

    /**
     * GPS zero speed can lag the actual start. Require three stable 250 ms means,
     * transported into the same body frame, before trusting gravity at rest.
     * Compare measured vectors with each other, not with the drifting estimate:
     * a stable true lean (including the side stand) can still correct drift.
     */
    private fun stationaryTrace(centre: Long, q: ImuQuaternion, measured: ImuVector): StationaryTrace {
        val latestCoveredAcceleration = acceleration.lastOrNull { it.timestampNanos <= lastGyroTime }
        val end = minOf(centre + 150_000_000L,
            latestCoveredAcceleration?.timestampNanos ?: return StationaryTrace(centre, centre, false))
        val start = end - STATIONARY_WINDOW_NANOS
        val missing = StationaryTrace(start, end, false)
        if (end < centre) return missing
        val transported = acceleration.filter {
            it.timestampNanos in (start - 150_000_000L)..(end + 150_000_000L)
        }.mapNotNull { sample ->
            stateAt(sample.timestampNanos)?.let { pose ->
                sample.timestampNanos to q.inverse().rotate(pose.rotate(sample.value))
            }
        }
        val rates = states.map { it.timestampNanos to it.angularRate }
        val means = mutableListOf(measured)
        var maxRate = 0.0
        repeat(3) { bin ->
            val left = start + bin * 250_000_000L
            val right = left + 250_000_000L
            means.add(timeWeightedMean(transported, left, right) ?: return missing)
            val rate = timeWeightedMean(rates, left, right) ?: return missing
            maxRate = maxOf(maxRate, rate.length())
        }
        var maxDifference = 0.0
        for (i in means.indices) for (j in 0 until i)
            maxDifference = maxOf(maxDifference, (means[i] - means[j]).length())
        return StationaryTrace(start, end, true, maxDifference, maxRate)
    }

    private fun correctAt(
        timestamp: Long, vehicleAcceleration: ImuVector, dt: Double, quality: Double,
        courseRate: Double = 0.0, meanGyroYawRate: Double? = null,
        intervalStart: Long? = null, intervalEnd: Long? = null,
        stationary: Boolean = false,
    ) {
        val q = if (historicalAlignment) stateAt(timestamp) else orientation
        if (q == null) {
            record(CorrectionDecision(timestamp, DecisionReason.MISSING_HISTORY))
            return
        }
        val centre = if (historicalAlignment) timestamp else lastGyroTime
        val measured = if (intervalStart != null && intervalEnd != null) {
            // Transport each acceleration vector into the midpoint body frame
            // before integration. Averaging rotating device axes directly is wrong.
            val transported = acceleration.filter {
                it.timestampNanos in (intervalStart - 150_000_000L)..(intervalEnd + 150_000_000L)
            }.mapNotNull { sample ->
                stateAt(sample.timestampNanos)?.let { samplePose ->
                    sample.timestampNanos to q.inverse().rotate(samplePose.rotate(sample.value))
                }
            }
            timeWeightedMean(transported, intervalStart, intervalEnd)
        } else {
            val values = acceleration.filter { abs(it.timestampNanos - centre) <= 150_000_000L }
            if (values.size < 3 || values.last().timestampNanos - values.first().timestampNanos < 100_000_000L)
                null
            else values.fold(ImuVector.ZERO) { sum, sample -> sum + sample.value } * (1.0 / values.size)
        }
        if (measured == null) {
            record(CorrectionDecision(timestamp, DecisionReason.INSUFFICIENT_ACCELERATION))
            return
        }
        val rawWindow = acceleration.filter {
            if (intervalStart != null && intervalEnd != null)
                it.timestampNanos in intervalStart..intervalEnd
            else abs(it.timestampNanos - centre) <= 150_000_000L
        }
        val rawMean = if (rawWindow.isEmpty()) ImuVector.ZERO else
            rawWindow.fold(ImuVector.ZERO) { sum, sample -> sum + sample.value } * (1.0 / rawWindow.size)
        val rawPeak = rawWindow.maxOfOrNull { it.value.length() } ?: Double.NaN
        val rawRms = if (rawWindow.isEmpty()) Double.NaN else sqrt(
            rawWindow.sumOf { (it.value - rawMean).dot(it.value - rawMean) } / rawWindow.size)
        val predictedUp = up(q)
        val horizontalForward = (forward - predictedUp * forward.dot(predictedUp)).unit()
        val right = forward.cross(predictedUp).unit()
        val predictedMovement = if (compensateMotion)
            horizontalForward * (if (compensateLongitudinal) vehicleAcceleration.x else 0.0) +
                right * (if (compensateLateral) vehicleAcceleration.y else 0.0)
        else ImuVector.ZERO
        val inferredGravity = measured - predictedMovement
        val gain = ((1.0 - exp(-dt / 3.0)) * quality).coerceAtMost(0.45)
        val trace = GravityTrace(
            measured, predictedMovement, inferredGravity, vehicleAcceleration,
            courseRate, meanGyroYawRate,
            geometry.bankDegrees(predictedUp), geometry.bankDegrees(measured),
            geometry.bankDegrees(inferredGravity),
            geometry.pitchDegrees(predictedUp), geometry.pitchDegrees(inferredGravity),
            gain, chassisBankDegrees,
            rawAccelerationPeakMps2 = rawPeak, rawAccelerationRmsDeviationMps2 = rawRms,
            accelerationSampleCount = rawWindow.size,
            stationaryTrace = if (stationary && qualifyStationaryCorrections)
                stationaryTrace(centre, q, measured) else null,
        )
        if (trace.stationaryTrace?.qualified == false) {
            record(CorrectionDecision(timestamp, DecisionReason.STATIONARY_UNSTABLE,
                gravityMagnitude = inferredGravity.length(), gravityTrace = trace))
            return
        }
        if (abs(inferredGravity.length() - 9.80665) > 1.5) {
            record(CorrectionDecision(
                timestamp, DecisionReason.IMPLAUSIBLE_GRAVITY,
                gravityMagnitude = inferredGravity.length(),
                gravityTrace = trace,
            ))
            return
        }
        val observedUp = inferredGravity.unit()
        val innovation = acos(predictedUp.dot(observedUp).coerceIn(-1.0, 1.0))
        if (innovation > Math.toRadians(30.0)) {
            record(CorrectionDecision(
                timestamp, DecisionReason.LARGE_INNOVATION,
                gravityMagnitude = inferredGravity.length(),
                gravityTrace = trace,
                innovationDegrees = Math.toDegrees(innovation),
            ))
            return
        }
        if (timestamp in withheldReferenceTimes) {
            record(CorrectionDecision(
                timestamp, DecisionReason.DIAGNOSTIC_HOLDOUT,
                gravityMagnitude = inferredGravity.length(),
                innovationDegrees = Math.toDegrees(innovation),
                gravityTrace = trace.copy(currentBankAfter = chassisBankDegrees,
                requestedRotationDegrees = Math.toDegrees(innovation * gain),
                appliedRotationDegrees = 0.0),
            ))
            return
        }
        // Right multiplication adjusts body tilt; the corresponding world rotation
        // also corrects all later stored states and the current integrated orientation.
        val tilt = ImuQuaternion.between(observedUp, predictedUp).fractionalRotation(gain)
        val correctedPast = (q * tilt).unit()
        val worldCorrection = (correctedPast * q.inverse()).unit()
        orientation = (worldCorrection * orientation).unit()
        states.filter { it.timestampNanos >= centre }.forEach {
            it.orientation = (worldCorrection * it.orientation).unit()
        }
        record(CorrectionDecision(
            timestamp, DecisionReason.ACCEPTED,
            gravityMagnitude = inferredGravity.length(),
            innovationDegrees = Math.toDegrees(innovation),
            gravityTrace = trace.copy(currentBankAfter = chassisBankDegrees,
                gain = gain,
                requestedRotationDegrees = Math.toDegrees(innovation * gain),
                appliedRotationDegrees = Math.toDegrees(innovation * gain)),
        ))
    }

    companion object {
        // Engineering quality thresholds, not calibrated confidence bounds.
        const val STATIONARY_WINDOW_NANOS = 750_000_000L
        const val STATIONARY_ACCELERATION_DELTA_MPS2 = 1.0
        const val STATIONARY_ANGULAR_RATE_RADPS = 0.12

        /** Piecewise-linear time integral with bracketed endpoints; never extrapolates. */
        fun timeWeightedMean(
            samples: List<Pair<Long, ImuVector>>, start: Long, end: Long,
        ): ImuVector? {
            if (end <= start || samples.size < 2 ||
                samples.first().first > start || samples.last().first < end
            ) return null
            var integral = ImuVector.ZERO
            var covered = 0L
            for ((before, after) in samples.zipWithNext()) {
                val span = after.first - before.first
                if (span <= 0L) return null
                val lo = maxOf(start, before.first)
                val hi = minOf(end, after.first)
                if (hi <= lo) continue
                if (span > 150_000_000L || !before.second.finite() || !after.second.finite()) return null
                val difference = after.second - before.second
                val left = before.second + difference * ((lo - before.first).toDouble() / span)
                val right = before.second + difference * ((hi - before.first).toDouble() / span)
                integral += (left + right) * ((hi - lo) / 2.0)
                covered += hi - lo
            }
            if (covered != end - start) return null
            return integral * (1.0 / (end - start))
        }

        fun assumedForwardFromScreen(referenceUp: ImuVector): ImuVector {
            val gravity = referenceUp.unit()
            val rear = ImuVector.UP - gravity * ImuVector.UP.dot(gravity)
            require(rear.length() > 0.2)
            return rear.unit() * -1.0
        }
    }
}
