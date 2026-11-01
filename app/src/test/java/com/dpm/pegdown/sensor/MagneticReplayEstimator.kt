package com.dpm.pegdown.sensor

import kotlin.math.*

// Test-only snapshot of the production estimator at 4a8470a.
// Replay asserts that this copy with magnetism disabled matches production.
internal class MagneticReplayEstimator(
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
    private val courseLagSeconds: Double = 0.0,
    // Diagnostic ablation only: retain every input/history, omit specified updates.
    private val withheldReferenceTimes: Set<Long> = emptySet(),
    private val maxGpsRotationRateDegreesPerSecond: Double = Double.POSITIVE_INFINITY,
    private val shockPeakLimitMps2: Double = Double.POSITIVE_INFINITY,
    private val magneticReference: ImuVector? = null,
    private val magneticTauSeconds: Double = 10.0,
    private val magneticYawOnly: Boolean = false,
    private val magneticLocalReference: Boolean = false,
    private val magneticReferenceMaxAgeSeconds: Double = 10.0,
    private val magneticAnchorBudgetDegrees: Double = Double.POSITIVE_INFINITY,
    private val magneticLocalInnovationLimitDegrees: Double = 15.0,
    independentMotionGate: Boolean = false,
    private val magneticHoldStartNanos: Long = Long.MAX_VALUE,
    private val magneticHoldEndNanos: Long = Long.MIN_VALUE,
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
        SHOCK_WINDOW(true), BAD_FIX(true), INVALID_INTERVAL(true), IMPLAUSIBLE_MOTION(true),
        INSUFFICIENT_STATES, ROLLING(true), CHANGING_YAW(true),
        MISSING_HISTORY, MISSING_SPEED_HISTORY, INSUFFICIENT_ACCELERATION,
        IMPLAUSIBLE_GRAVITY(true), LARGE_INNOVATION(true), COURSE_GYRO_MISMATCH(true),
    }

    data class MotionTrace(
        val intervalStartNanos: Long, val intervalEndNanos: Long,
        val intervalSeconds: Double, val binCount: Int,
        val maxRollRate: Double, val yawRateRange: Double,
        val meanGyroYawRate: Double, val sigmaLateralMps2: Double,
    )

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
        if (decision.reason == DecisionReason.ACCEPTED) {
            acceptedCorrections++
            decision.gravityTrace?.let { trace ->
                trace.currentBankAfter?.let { gpsBankDelta += it - trace.currentBankBefore }
            }
            refreshMagneticReference(decision)
        }
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
    // An independent control follows the same raw IMU/GPS stream, without magnetic updates.
    // GPS is forwarded after this estimator's decision so its current fix cannot
    // retroactively alter the movement history used to decide that same fix.
    private val motionGuard = if (independentMotionGate) MagneticReplayEstimator(
        referenceUp, forwardInDevice, initialOrientation, initialTimestampNanos,
        gyroBias = gyroBias, compensateMotion = compensateMotion,
        historicalAlignment = historicalAlignment, compensateLongitudinal = compensateLongitudinal,
        compensateLateral = compensateLateral, checkYawConsistency = checkYawConsistency,
        allowSlowCourseCorrections = allowSlowCourseCorrections, matchGpsInterval = matchGpsInterval,
        courseLagSeconds = courseLagSeconds, withheldReferenceTimes = withheldReferenceTimes,
    ) else null
    var lastMotionGate: Pair<Double, Double>? = null
        private set
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
        require(maxGpsRotationRateDegreesPerSecond > 0.0 && !maxGpsRotationRateDegreesPerSecond.isNaN())
        require(shockPeakLimitMps2 > 0.0 && !shockPeakLimitMps2.isNaN())
        require(magneticAnchorBudgetDegrees > 0.0 && !magneticAnchorBudgetDegrees.isNaN())
        require(magneticLocalInnovationLimitDegrees.isFinite() && magneticLocalInnovationLimitDegrees in 0.0..15.0)
        require(magneticTauSeconds.isFinite() && magneticTauSeconds > 0.0)
        require(magneticReferenceMaxAgeSeconds.isFinite() && magneticReferenceMaxAgeSeconds > 0.0)
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
        motionGuard?.onGyroscope(timestampNanos, measuredRate)
        if (!measuredRate.finite() || timestampNanos <= lastGyroTime) return
        val dt = (timestampNanos - lastGyroTime) / 1e9
        lastGyroTime = timestampNanos
        val rate = measuredRate - gyroBias
        if (dt > 0.15 || rate.length() > 20.0) {
            gyroGaps++
            pendingMagnetic.clear()
            localMagneticReference = null
            magneticHistory.clear()
            states.clear()
            acceleration.clear()
            speedHistory.clear()
            previousFix = null
        } else if (rate.length() > 1e-12) {
            orientation = (orientation * ImuQuaternion.axisAngle(rate, rate.length() * dt)).unit()
        }
        states.addLast(State(timestampNanos, orientation, rate))
        prune(timestampNanos)
        while (pendingMagnetic.isNotEmpty() && pendingMagnetic.first().timestamp <= timestampNanos) {
            val sample = pendingMagnetic.removeFirst()
            if (timestampNanos - sample.timestamp > 150_000_000L) magneticDecision("LATE")
            else processMagnetic(sample.timestamp, sample.field, sample.accuracy)
        }
    }

    fun onAccelerometer(timestampNanos: Long, value: ImuVector) {
        motionGuard?.onAccelerometer(timestampNanos, value)
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
        lastMotionGate = null
        try {
            applyFix(fix)
        } finally {
            motionGuard?.onFix(fix)
        }
    }

    private fun applyFix(fix: Fix) {
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
            correctAt(fix.timestampNanos, ImuVector.ZERO, dt.coerceIn(0.0, 2.5), 1.0)
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
        val intervalStates = (motionGuard?.states ?: states).filter { it.timestampNanos in start..end }
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
        lastMotionGate = maxRollRate to yawRateRange
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

    private fun correctAt(
        timestamp: Long, vehicleAcceleration: ImuVector, dt: Double, quality: Double,
        courseRate: Double = 0.0, meanGyroYawRate: Double? = null,
        intervalStart: Long? = null, intervalEnd: Long? = null,
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
        )
        if (abs(inferredGravity.length() - 9.80665) > 1.5) {
            record(CorrectionDecision(
                timestamp, DecisionReason.IMPLAUSIBLE_GRAVITY,
                gravityMagnitude = inferredGravity.length(),
                gravityTrace = trace,
            ))
            return
        }
        if (rawPeak > shockPeakLimitMps2) {
            record(CorrectionDecision(timestamp, DecisionReason.SHOCK_WINDOW,
                gravityMagnitude = inferredGravity.length(), gravityTrace = trace))
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
        val appliedGain = if (innovation < 1e-12) gain else minOf(
            gain, Math.toRadians(maxGpsRotationRateDegreesPerSecond) * dt / innovation)
        val tilt = ImuQuaternion.between(observedUp, predictedUp).fractionalRotation(appliedGain)
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
                gain = appliedGain,
                requestedRotationDegrees = Math.toDegrees(innovation * gain),
                appliedRotationDegrees = Math.toDegrees(innovation * appliedGain)),
        ))
    }

    private data class PendingMagnetic(val timestamp: Long, val field: ImuVector, val accuracy: Int)
    private val pendingMagnetic = ArrayDeque<PendingMagnetic>()
    private val magneticHistory = ArrayDeque<PendingMagnetic>()
    private var localMagneticReference: ImuVector? = null
    private var localMagneticReferenceTime = 0L
    var gpsBankDelta = 0.0
        private set
    var magneticAbsoluteBankDelta = 0.0
        private set
    var magneticAnchorRotationDegrees = 0.0
        private set
    var lastMagneticInnovationDegrees = Double.NaN
        private set
    val magneticAnchorAgeSeconds: Double
        get() = if (localMagneticReference == null) Double.NaN else (lastGyroTime - localMagneticReferenceTime) / 1e9

    // A local anchor inherits the GPS/accelerometer estimate; it is not ground truth.
    // Only preceding samples and already accepted corrections are used.
    private fun refreshMagneticReference(decision: CorrectionDecision) {
        if (!magneticLocalReference || magneticReference == null) return
        val trace = decision.gravityTrace ?: return
        val after = trace.currentBankAfter ?: return
        if ((decision.innovationDegrees ?: Double.POSITIVE_INFINITY) > 3.0 ||
            abs(after - trace.currentBankBefore) > 1.0) {
            magneticDecision("ANCHOR_INNOVATION"); return
        }
        val samples = magneticHistory.filter {
            it.timestamp in (lastGyroTime - 300_000_000L)..lastGyroTime
        }
        if (samples.size < 8 || samples.last().timestamp - samples.first().timestamp < 200_000_000L ||
            lastGyroTime - samples.last().timestamp > 150_000_000L) {
            magneticDecision("ANCHOR_MISSING_SAMPLES"); return
        }
        val vectors = samples.mapNotNull { sample -> stateAt(sample.timestamp)?.rotate(sample.field) }
        if (vectors.size != samples.size) { magneticDecision("ANCHOR_MISSING_HISTORY"); return }
        val mean = vectors.fold(ImuVector.ZERO) { sum, v -> sum + v } * (1.0 / vectors.size)
        if (vectors.any { acos(it.unit().dot(mean.unit()).coerceIn(-1.0, 1.0)) > Math.toRadians(3.0) }) {
            magneticDecision("ANCHOR_UNSTABLE"); return
        }
        localMagneticReference = mean
        localMagneticReferenceTime = lastGyroTime
        magneticAnchorRotationDegrees = 0.0
        magneticDecision("ANCHOR_REFRESHED")
    }

    private var lastMagneticInput = 0L
    private var lastMagneticTime = 0L
    val magneticDecisions = mutableMapOf<String, Int>()
    var magneticBankDelta = 0.0
        private set

    private fun magneticDecision(reason: String) {
        magneticDecisions[reason] = (magneticDecisions[reason] ?: 0) + 1
    }

    fun onMagnetometer(timestamp: Long, field: ImuVector, accuracy: Int) {
        if (magneticReference == null) return
        if (timestamp <= lastMagneticInput) { magneticDecision("OUT_OF_ORDER"); return }
        lastMagneticInput = timestamp
        if (timestamp > lastGyroTime) {
            if (pendingMagnetic.size >= 32) { pendingMagnetic.removeFirst(); magneticDecision("QUEUE_LIMIT") }
            pendingMagnetic.addLast(PendingMagnetic(timestamp, field, accuracy))
            magneticDecision("BUFFERED")
            return
        }
        processMagnetic(timestamp, field, accuracy)
    }

    private fun processMagnetic(timestamp: Long, field: ImuVector, accuracy: Int) {
        val initialReference = magneticReference ?: return
        if (timestamp <= lastMagneticTime) { magneticDecision("OUT_OF_ORDER"); return }
        val dt = if (lastMagneticTime > 0L) (timestamp - lastMagneticTime) / 1e9 else 0.0
        lastMagneticTime = timestamp
        if (accuracy < 3 || !field.finite() || initialReference.length() < 1e-6) {
            magneticDecision("BAD_SAMPLE"); return
        }
        if (dt !in 0.001..0.15) { magneticDecision("INTERVAL"); return }
        if (abs(field.length() / initialReference.length() - 1.0) > 0.10) {
            magneticDecision("FIELD_STRENGTH"); return
        }
        val past = stateAt(timestamp)
        if (past == null) { magneticDecision("MISSING_HISTORY"); return }
        if (magneticLocalReference) {
            magneticHistory.addLast(PendingMagnetic(timestamp, field, accuracy))
            while (magneticHistory.isNotEmpty() && timestamp - magneticHistory.first().timestamp > 500_000_000L)
                magneticHistory.removeFirst()
        }
        val reference = if (magneticLocalReference) {
            val local = localMagneticReference
            if (local == null) { magneticDecision("NO_ANCHOR"); return }
            val age = (timestamp - localMagneticReferenceTime) / 1e9
            if (age < 0.0 || age > magneticReferenceMaxAgeSeconds) {
                magneticDecision("ANCHOR_EXPIRED"); return
            }
            local
        } else initialReference
        val observedWorld = past.rotate(field)
        val innovation = acos(observedWorld.unit().dot(reference.unit()).coerceIn(-1.0, 1.0))
        lastMagneticInnovationDegrees = Math.toDegrees(innovation)
        if (timestamp in magneticHoldStartNanos..magneticHoldEndNanos) {
            magneticDecision("DIAGNOSTIC_HOLDOUT"); return
        }
        if (magneticLocalReference && magneticLocalInnovationLimitDegrees < 15.0 &&
            lastMagneticInnovationDegrees > magneticLocalInnovationLimitDegrees) {
            localMagneticReference = null
            magneticDecision("LOCAL_REFERENCE_CONFLICT"); return
        }
        if (innovation > Math.toRadians(15.0)) { magneticDecision("DIRECTION"); return }
        val fullCorrection = if (magneticYawOnly) {
            if (hypot(observedWorld.x, observedWorld.y) < 1.0 || hypot(reference.x, reference.y) < 1.0) {
                magneticDecision("WEAK_HORIZONTAL"); return
            }
            var yaw = atan2(reference.y, reference.x) - atan2(observedWorld.y, observedWorld.x)
            yaw = atan2(sin(yaw), cos(yaw))
            ImuQuaternion.axisAngle(ImuVector.UP, yaw)
        } else ImuQuaternion.between(observedWorld, reference)
        val rotation = 2.0 * acos(abs(fullCorrection.w).coerceIn(0.0, 1.0))
        var gain = 1.0 - exp(-dt / magneticTauSeconds)
        if (rotation > 1e-12) gain = minOf(gain, Math.toRadians(0.25) / rotation)
        if (magneticLocalReference && magneticAnchorBudgetDegrees.isFinite()) {
            val remaining = (magneticAnchorBudgetDegrees - magneticAnchorRotationDegrees).coerceAtLeast(0.0)
            if (remaining < 1e-9) { magneticDecision("ANCHOR_BUDGET"); return }
            if (rotation > 1e-12) gain = minOf(gain, Math.toRadians(remaining) / rotation)
        }
        val correction = fullCorrection.fractionalRotation(gain)
        val before = chassisBankDegrees
        orientation = (correction * orientation).unit()
        states.filter { it.timestampNanos >= timestamp }.forEach {
            it.orientation = (correction * it.orientation).unit()
        }
        magneticBankDelta += chassisBankDegrees - before
        magneticAbsoluteBankDelta += abs(chassisBankDegrees - before)
        magneticAnchorRotationDegrees += Math.toDegrees(rotation * gain)
        magneticDecision("ACCEPTED")
    }

    companion object {
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
