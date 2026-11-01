package com.dpm.pegdown.sensor

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class MagneticLeanReplayTest {
    private val base = ImuQuaternion.axisAngle(ImuVector(1.0, 0.0, 0.0), Math.PI / 2)
    private val up = base.inverse().rotate(ImuVector.UP)
    private val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(up)
    private val field = ImuVector(18.0, 5.0, -44.0)
    private val initialTime = 1_000_000_000L

    @Test
    fun `synthetic magnetic correction reduces observable roll bias and preserves true turn bank`() {
        val truth = base * ImuQuaternion.axisAngle(forward, Math.toRadians(20.0))
        val wrong = base * ImuQuaternion.axisAngle(forward, Math.toRadians(30.0))
        val biased = MagneticReplayEstimator(up, forward, wrong, initialTime,
            magneticReference = field, magneticTauSeconds = 2.0)
        val bodyField = truth.inverse().rotate(field)
        repeat(501) { i ->
            val time = initialTime + i * 20_000_000L
            biased.onGyroscope(time, ImuVector.ZERO)
            biased.onMagnetometer(time, bodyField, 3)
        }
        assertTrue(abs(biased.chassisBankDegrees - 20.0) < 5.0)
        assertTrue(biased.chassisBankDegrees > 15.0)

        val yawRate = 9.80665 * tan(Math.toRadians(20.0)) / 15.0
        for ((local, independent) in listOf(false to false, true to false, true to true)) {
        val turn = MagneticReplayEstimator(up, forward, truth, initialTime,
            magneticReference = field, magneticTauSeconds = 2.0, magneticLocalReference = local, independentMotionGate = independent)
        val bodyRate = truth.inverse().rotate(ImuVector.UP) * yawRate
        repeat(501) { i ->
            val time = initialTime + i * 20_000_000L
            val pose = ImuQuaternion.axisAngle(ImuVector.UP, yawRate * i * .02) * truth
            turn.onGyroscope(time, bodyRate)
            val bodyUp = pose.inverse().rotate(ImuVector.UP)
            turn.onAccelerometer(time, bodyUp * 9.80665 + forward.cross(bodyUp).unit() * (15.0 * yawRate))
            turn.onMagnetometer(time, pose.inverse().rotate(field), 3)
            if (i > 0 && i % 50 == 0) turn.onFix(MagneticReplayEstimator.Fix(
                time, time, 15.0, Math.toDegrees(yawRate * i * .02), .2, .1, 2.0,
            ))
        }
        assertEquals(20.0, turn.chassisBankDegrees, 1e-5)
        assertTrue((turn.magneticDecisions["ACCEPTED"] ?: 0) > 350)
        if (local) assertTrue((turn.magneticDecisions["ANCHOR_REFRESHED"] ?: 0) > 0)
        }
    }

    @Test
    fun `magnetic replay rejects direction disturbance even with constant field strength`() {
        val estimator = MagneticReplayEstimator(up, forward, base, initialTime, magneticReference = field)
        estimator.onMagnetometer(initialTime, base.inverse().rotate(field), 3)
        estimator.onGyroscope(initialTime + 20_000_000L, ImuVector.ZERO)
        val disturbed = ImuQuaternion.axisAngle(ImuVector(0.0, 1.0, 0.0), 1.0).rotate(field)
        estimator.onMagnetometer(initialTime + 20_000_000L, base.inverse().rotate(disturbed), 3)
        assertEquals(field.length(), disturbed.length(), 1e-9)
        assertEquals(1, estimator.magneticDecisions["DIRECTION"])
        assertEquals(0.0, estimator.chassisBankDegrees, 1e-9)
        estimator.onGyroscope(initialTime + 40_000_000L, ImuVector.ZERO)
        estimator.onMagnetometer(initialTime + 40_000_000L, base.inverse().rotate(field) * 1.5, 3)
        assertEquals(1, estimator.magneticDecisions["FIELD_STRENGTH"])
        estimator.onMagnetometer(initialTime + 60_000_000L, base.inverse().rotate(field), 3)
        assertEquals(1, estimator.magneticDecisions["BUFFERED"])
        val accepted = estimator.magneticDecisions["ACCEPTED"] ?: 0
        estimator.onGyroscope(initialTime + 80_000_000L, ImuVector.ZERO)
        assertEquals(accepted + 1, estimator.magneticDecisions["ACCEPTED"])
    }

    @Test
    fun `local magnetic reference needs qualified gravity anchor expires and resets on gyro gap`() {
        val estimator = MagneticReplayEstimator(up, forward, base, initialTime,
            magneticReference = field, magneticLocalReference = true,
            magneticReferenceMaxAgeSeconds = 1.0)
        repeat(51) { i ->
            val time = initialTime + i * 20_000_000L
            estimator.onGyroscope(time, ImuVector.ZERO)
            estimator.onAccelerometer(time, up * 9.80665)
            estimator.onMagnetometer(time, base.inverse().rotate(field), 3)
        }
        assertNull(estimator.magneticDecisions["ACCEPTED"])
        estimator.onFix(MagneticReplayEstimator.Fix(
            initialTime + 1_000_000_000L, initialTime + 1_000_000_000L,
            0.0, 0.0, .2, .1, 2.0))
        assertEquals(1, estimator.magneticDecisions["ANCHOR_REFRESHED"])
        repeat(61) { i ->
            val time = initialTime + 1_020_000_000L + i * 20_000_000L
            estimator.onGyroscope(time, ImuVector.ZERO)
            estimator.onMagnetometer(time, base.inverse().rotate(field), 3)
        }
        assertTrue((estimator.magneticDecisions["ACCEPTED"] ?: 0) > 40)
        assertTrue((estimator.magneticDecisions["ANCHOR_EXPIRED"] ?: 0) > 0)
        assertEquals(0.0, estimator.chassisBankDegrees, 1e-9)
        val accepted = estimator.magneticDecisions["ACCEPTED"]
        estimator.onGyroscope(initialTime + 3_000_000_000L, ImuVector.ZERO)
        estimator.onGyroscope(initialTime + 3_020_000_000L, ImuVector.ZERO)
        estimator.onMagnetometer(initialTime + 3_020_000_000L, base.inverse().rotate(field), 3)
        assertEquals(accepted, estimator.magneticDecisions["ACCEPTED"])
        assertEquals(1, estimator.gyroGaps)
    }

    @Test
    fun `local reference refuses a gravity anchor with large innovation`() {
        val wrong = base * ImuQuaternion.axisAngle(forward, Math.toRadians(10.0))
        val estimator = MagneticReplayEstimator(up, forward, wrong, initialTime,
            magneticReference = field, magneticLocalReference = true)
        repeat(51) { i ->
            val time = initialTime + i * 20_000_000L
            estimator.onGyroscope(time, ImuVector.ZERO)
            estimator.onAccelerometer(time, up * 9.80665)
            estimator.onMagnetometer(time, base.inverse().rotate(field), 3)
        }
        estimator.onFix(MagneticReplayEstimator.Fix(
            initialTime + 1_000_000_000L, initialTime + 1_000_000_000L,
            0.0, 0.0, .2, .1, 2.0))
        assertEquals(1, estimator.magneticDecisions["ANCHOR_INNOVATION"])
        assertNull(estimator.magneticDecisions["ANCHOR_REFRESHED"])
        assertNull(estimator.magneticDecisions["ACCEPTED"])
    }

    @Test
    fun `local magnetic rotation budget cannot grow with repeated samples and conflict requires new anchor`() {
        fun estimator(budget: Double, limit: Double) = MagneticReplayEstimator(up, forward, base, initialTime,
            magneticReference = field, magneticLocalReference = true, magneticTauSeconds = .05,
            magneticAnchorBudgetDegrees = budget, magneticLocalInnovationLimitDegrees = limit)
        fun seed(e: MagneticReplayEstimator) {
            repeat(51) { i ->
                val time = initialTime + i * 20_000_000L
                e.onGyroscope(time, ImuVector.ZERO)
                e.onAccelerometer(time, up * 9.80665)
                e.onMagnetometer(time, base.inverse().rotate(field), 3)
            }
            e.onFix(MagneticReplayEstimator.Fix(initialTime + 1_000_000_000L,
                initialTime + 1_000_000_000L, 0.0, 0.0, .2, .1, 2.0))
            assertEquals(1, e.magneticDecisions["ANCHOR_REFRESHED"])
        }
        val bounded = estimator(1.0, 15.0)
        seed(bounded)
        val shifted = ImuQuaternion.axisAngle(ImuVector(0.0, 1.0, 0.0), Math.toRadians(2.0)).rotate(field)
        repeat(200) { i ->
            val time = initialTime + 1_020_000_000L + i * 20_000_000L
            bounded.onGyroscope(time, ImuVector.ZERO)
            bounded.onAccelerometer(time, up * 9.80665)
            bounded.onMagnetometer(time, base.inverse().rotate(shifted), 3)
            assertTrue(bounded.magneticAnchorRotationDegrees <= 1.0 + 1e-9)
        }
        assertEquals(1.0, bounded.magneticAnchorRotationDegrees, 1e-9)
        assertTrue((bounded.magneticDecisions["ANCHOR_BUDGET"] ?: 0) > 100)
        bounded.onFix(MagneticReplayEstimator.Fix(initialTime + 5_000_000_000L,
            initialTime + 5_000_000_000L, 0.0, 0.0, .2, .1, 2.0))
        assertEquals(2, bounded.magneticDecisions["ANCHOR_REFRESHED"])
        assertEquals(0.0, bounded.magneticAnchorRotationDegrees, 1e-9)

        val conflict = estimator(Double.POSITIVE_INFINITY, 3.0)
        seed(conflict)
        val disturbed = ImuQuaternion.axisAngle(ImuVector(0.0, 1.0, 0.0), Math.toRadians(10.0)).rotate(field)
        conflict.onGyroscope(initialTime + 1_020_000_000L, ImuVector.ZERO)
        conflict.onMagnetometer(initialTime + 1_020_000_000L, base.inverse().rotate(disturbed), 3)
        assertEquals(1, conflict.magneticDecisions["LOCAL_REFERENCE_CONFLICT"])
        repeat(30) { i ->
            val time = initialTime + 1_040_000_000L + i * 20_000_000L
            conflict.onGyroscope(time, ImuVector.ZERO)
            conflict.onAccelerometer(time, up * 9.80665)
            conflict.onMagnetometer(time, base.inverse().rotate(field), 3)
        }
        assertNull(conflict.magneticDecisions["ACCEPTED"])
        assertTrue(conflict.magneticAnchorAgeSeconds.isNaN())
        conflict.onFix(MagneticReplayEstimator.Fix(initialTime + 1_620_000_000L,
            initialTime + 1_620_000_000L, 0.0, 0.0, .2, .1, 2.0))
        assertEquals(2, conflict.magneticDecisions["ANCHOR_REFRESHED"])
        conflict.onGyroscope(initialTime + 1_640_000_000L, ImuVector.ZERO)
        conflict.onMagnetometer(initialTime + 1_640_000_000L, base.inverse().rotate(field), 3)
        assertEquals(1, conflict.magneticDecisions["ACCEPTED"])
    }

    @Test
    fun `independent movement checks ignore actual magnetic tilt and reset after gyro outage`() {
        val control = MagneticReplayEstimator(up, forward, base, initialTime, compensateMotion = false)
        val magnetic = MagneticReplayEstimator(up, forward, base, initialTime,
            compensateMotion = false, magneticReference = field,
            magneticTauSeconds = .2, independentMotionGate = true)
        val disturbed = ImuQuaternion.axisAngle(ImuVector(0.0, 1.0, 0.0), Math.toRadians(8.0)).rotate(field)
        var truth = base
        var difference = 0.0
        var evaluations = 0
        repeat(301) { i ->
            val time = initialTime + i * 20_000_000L
            val rate = truth.inverse().rotate(ImuVector.UP) * .04 + forward * (.1 * sin(i * .04))
            if (i > 0) truth = (truth * ImuQuaternion.axisAngle(rate, rate.length() * .02)).unit()
            control.onGyroscope(time, rate)
            magnetic.onGyroscope(time, rate)
            val acceleration = truth.inverse().rotate(ImuVector.UP) * 9.80665
            control.onAccelerometer(time, acceleration)
            magnetic.onAccelerometer(time, acceleration)
            magnetic.onMagnetometer(time, truth.inverse().rotate(disturbed), 3)
            difference = maxOf(difference, abs(control.chassisBankDegrees - magnetic.chassisBankDegrees))
            if (i > 0 && i % 50 == 0) {
                val fix = MagneticReplayEstimator.Fix(time, time, 15.0,
                    Math.toDegrees(.04 * i * .02), .2, .1, 2.0)
                control.onFix(fix); magnetic.onFix(fix)
                assertEquals(control.lastMotionGate, magnetic.lastMotionGate)
                if (magnetic.lastMotionGate != null) evaluations++
            }
        }
        assertTrue("Magnetic perturbation was ineffective", difference > .5)
        assertTrue((magnetic.magneticDecisions["ACCEPTED"] ?: 0) > 100)
        assertTrue(evaluations >= 4)
        val afterGap = initialTime + 7_000_000_000L
        control.onGyroscope(afterGap, ImuVector.ZERO)
        magnetic.onGyroscope(afterGap, ImuVector.ZERO)
        val fix = MagneticReplayEstimator.Fix(afterGap, afterGap, 15.0, 17.0, .2, .1, 2.0)
        control.onFix(fix); magnetic.onFix(fix)
        assertEquals(1, magnetic.gyroGaps)
        assertEquals(control.lastDecision?.reason, magnetic.lastDecision?.reason)
        assertEquals(control.lastMotionGate, magnetic.lastMotionGate)
        assertTrue(magnetic.chassisBankDegrees.isFinite())
    }

    private fun readDiagnostics(file: File, consume: (Sequence<String>) -> Unit) {
        if (file.extension.equals("csv", ignoreCase = true)) {
            file.bufferedReader().useLines(consume)
        } else ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.endsWith(".csv") }
            zip.getInputStream(entry).bufferedReader().useLines(consume)
        }
    }

    private data class Pose(val time: Long, val q: ImuQuaternion)
    private data class Mag(val time: Long, val v: ImuVector)
    private data class Trial(val name: String, val slow: Boolean, val tau: Double?, val yawOnly: Boolean = false,
        val local: Boolean = false, val maxAge: Double = 10.0, val hold: Boolean = false,
        val budget: Double = Double.POSITIVE_INFINITY, val innovationLimit: Double = 15.0, val independentGate: Boolean = false,
        val gpsRate: Double = Double.POSITIVE_INFINITY, val shockLimit: Double = Double.POSITIVE_INFINITY)
    // Keep exact percentiles without millions of boxed Double objects.
    private class DoubleSamples {
        private var data = DoubleArray(1024)
        var size = 0
            private set
        private var sum = 0.0
        private var absoluteSum = 0.0
        private var minimum = Double.POSITIVE_INFINITY
        private var maximum = Double.NEGATIVE_INFINITY
        fun add(value: Double) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
            sum += value; absoluteSum += abs(value)
            minimum = minOf(minimum, value); maximum = maxOf(maximum, value)
        }
        fun average() = if (size == 0) Double.NaN else sum / size
        fun absoluteAverage() = if (size == 0) Double.NaN else absoluteSum / size
        fun minOrNull(): Double? = if (size == 0) null else minimum
        fun maxOrNull(): Double? = if (size == 0) null else maximum
        fun sorted() = data.copyOf(size).also { it.sort() }
    }

    private data class Window(
        val name: String, val range: ClosedFloatingPointRange<Double>,
        val values: DoubleSamples = DoubleSamples(), val differences: DoubleSamples = DoubleSamples(),
        var maximumDifference: Double = -1.0, var maximumTime: Double = Double.NaN,
        var maximumBaseline: Double = Double.NaN, var maximumCandidate: Double = Double.NaN,
        var maximumGpsReason: String = "",
    )
    private class Candidate(val trial: Trial, val engine: MagneticReplayEstimator) {
        var display = 0.0
        var signDifferences = 0
        var gpsLargeSteps = 0
        var gpsShockAccepted = 0
        var gpsNearBoundary = 0
        var gpsShockRejected = 0
        var gpsMaxStep = 0.0
        val topGps = mutableListOf<Pair<Double, String>>()
        val windows = listOf(
            Window("allAfter20", 20.0..Double.POSITIVE_INFINITY),
            Window("steadyCourseProxy", 20.0..Double.POSITIVE_INFINITY),
            Window("brakingProxy", 20.0..Double.POSITIVE_INFINITY),
            Window("acceleratingProxy", 20.0..Double.POSITIVE_INFINITY),
            Window("curveExitProxy", 20.0..Double.POSITIVE_INFINITY),
            Window("start85to88", 85.0..88.0),
            Window("reportedResidual93to99", 93.0..99.0),
            Window("mainRide150to2500", 150.0..2500.0),
            Window("magneticAnomaly645to647", 645.0..647.0),
            Window("largeRecordedBank", 20.0..Double.POSITIVE_INFINITY),
        )
    }

    private fun poseAt(poses: List<Pose>, time: Long): ImuQuaternion? {
        val index = poses.binarySearchBy(time) { it.time }
        if (index >= 0) return poses[index].q
        val next = -index - 1
        if (next == 0 || next >= poses.size) return null
        val a = poses[next - 1]; val b = poses[next]
        if (b.time - a.time > 150_000_000L) return null
        return (a.q * (a.q.inverse() * b.q).fractionalRotation((time - a.time).toDouble() / (b.time - a.time))).unit()
    }

    @Test
    fun `native magnetic correction replay compares predeclared variants without live changes`() {
        val path = System.getenv("PEGDOWN_MAGNET_DIAGNOSTICS_CSV")
            ?: System.getenv("PEGDOWN_MAGNET_DIAGNOSTICS_ZIP")
        assumeNotNull(path)
        val file = File(path!!)
        assertTrue(file.isFile)
        var referenceUp: ImuVector? = null
        var initial: ImuQuaternion? = null
        var start = 0L
        var firstWall = 0L
        val poses = mutableListOf<Pose>()
        val magnetic = mutableListOf<Mag>()
        readDiagnostics(file) { lines ->
                for (line in lines.drop(1)) {
                    val f = line.split(',')
                    if (f[0] == "session") {
                        val g = Regex("calibration_gravity_xyz=([^;]+);([^;]+);([^;]+)").find(f[36])!!
                        referenceUp = ImuVector(g.groupValues[1].toDouble(), g.groupValues[2].toDouble(), g.groupValues[3].toDouble())
                        val q = Regex("calibration_quaternion_wxyz=([^;]+);([^;]+);([^;]+);([^;]+)").find(f[36])!!
                        initial = ImuQuaternion(q.groupValues[1].toDouble(), q.groupValues[2].toDouble(),
                            q.groupValues[3].toDouble(), q.groupValues[4].toDouble())
                    } else if (f[0] == "sensor_sample") {
                        val time = f[2].toLong()
                        if (start == 0L) { start = time; firstWall = f[1].toLong() }
                        if ((time - start) / 1e9 > 20.2) break
                        val v = ImuVector(f[8].toDouble(), f[9].toDouble(), f[10].toDouble())
                        if (f[3] == "15") {
                            val w = f[11].toDoubleOrNull() ?: sqrt((1.0 - v.dot(v)).coerceAtLeast(0.0))
                            poses.add(Pose(time, ImuQuaternion(w, v.x, v.y, v.z).unit()))
                        } else if (f[3] == "2" && (time - start) / 1e9 <= 20.0 && f[7] == "3")
                            magnetic.add(Mag(time, v))
                    }
                }
        }
        val referenceVectors = magnetic.mapNotNull { poseAt(poses, it.time)?.rotate(it.v) }
        assertTrue("Too few initial magnetic samples: ${referenceVectors.size}", referenceVectors.size > 100)
        val reference = referenceVectors.fold(ImuVector.ZERO) { sum, v -> sum + v } * (1.0 / referenceVectors.size)
        println("MAG_REPLAY referenceTrainingSeconds=0to20 samples=${referenceVectors.size} referenceWorld=$reference norm=${reference.length()}")
        // Parameters and windows are declared before replay; no later-ride fitting.
        val trials = listOf(
            Trial("current_no_magnet", true, null),
            // Predeclared diagnostic policies; not enabled in production.
            Trial("gps_rate2", true, null, gpsRate = 2.0),
            Trial("gps_rate6", true, null, gpsRate = 6.0),
            Trial("gps_shock2p5g", true, null, shockLimit = 2.5 * 9.80665),
            Trial("gps_rate2_shock2p5g", true, null, gpsRate = 2.0, shockLimit = 2.5 * 9.80665),
            Trial("current_magnet_tau2", true, 2.0),
            Trial("current_magnet_tau10", true, 10.0),
            Trial("current_magnet_tau30", true, 30.0),
            Trial("current_magnet_yaw_only_tau10", true, 10.0, true),
            Trial("current_local_tau2_age10", true, 2.0, local = true),
            Trial("current_local_tau10_age10", true, 10.0, local = true),
            Trial("current_local_tau30_age10", true, 30.0, local = true),
            Trial("current_local_tau30_independent", true, 30.0, local = true, independentGate = true),
            Trial("current_local_tau30_independent_budget1", true, 30.0, local = true, independentGate = true, budget = 1.0),
            Trial("current_local_tau30_independent_conflict3", true, 30.0, local = true, independentGate = true, innovationLimit = 3.0),
            // Budget mirrors the 1-degree anchor-step allowance; conflict limit mirrors
            // the 3-degree anchor innovation gate. Neither is a measured accuracy bound.
            Trial("current_local_tau30_budget1", true, 30.0, local = true, budget = 1.0),
            Trial("current_local_tau30_conflict3", true, 30.0, local = true, innovationLimit = 3.0),
            Trial("current_local_tau30_budget1_conflict3", true, 30.0, local = true, budget = 1.0, innovationLimit = 3.0),
            Trial("current_local_tau30_hold1160to1195", true, 30.0, local = true, hold = true),
            Trial("current_local_tau10_age5", true, 10.0, local = true, maxAge = 5.0),
            Trial("legacy_no_magnet", false, null),
            Trial("legacy_magnet_tau10", false, 10.0),
        )
        val selectedTrials = trials.filter {
            if (System.getenv("PEGDOWN_GPS_AUDIT") == "1")
                it.name == "current_no_magnet" || it.name.startsWith("gps_") || it.name == "legacy_no_magnet" || it.name == "current_local_tau30_independent"
            else !it.name.startsWith("gps_")
        }
        val candidates = mutableListOf<Candidate>()
        var currentProduction: CompensatedImuLeanExperiment? = null
        var lastGyro = 0L
        var cloneDifference = 0.0
        var recordedStartupDifference = 0.0
        var recordedStartupSamples = 0
        var lastFixTime = 0L
        var lastFixSpeed = Double.NaN
        var lastFixBearing = Double.NaN
        var previousFixQuality = false
        var locationTime = Double.NEGATIVE_INFINITY
        var speed = Double.NaN
        var courseRate = Double.NaN
        var alongAcceleration = Double.NaN
        var recentTurnTime = Double.NEGATIVE_INFINITY
        var duration = 0.0
        var lastTraceSecond = -1
        var independentGateChecks = 0
        var independentMotionEvaluations = 0
        println("MAG_REPLAY source=${file.name}")
        readDiagnostics(file) { lines ->
                for (line in lines.drop(1)) {
                    val f = line.split(',')
                    if (f[0] == "sensor_sample") {
                        val time = f[2].toLong()
                        val t = (time - start) / 1e9
                        duration = maxOf(duration, t)
                        val v = ImuVector(f[8].toDouble(), f[9].toDouble(), f[10].toDouble())
                        if (f[3] == "4") {
                            if (candidates.isEmpty()) {
                                val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(referenceUp!!)
                                currentProduction = CompensatedImuLeanExperiment(referenceUp!!, forward, initial!!, time,
                                    matchGpsInterval = true, allowSlowCourseCorrections = true)
                                selectedTrials.forEach { trial ->
                                    candidates.add(Candidate(trial, MagneticReplayEstimator(referenceUp!!, forward, initial!!, time,
                                        matchGpsInterval = true, allowSlowCourseCorrections = trial.slow,
                                        maxGpsRotationRateDegreesPerSecond = trial.gpsRate, shockPeakLimitMps2 = trial.shockLimit,
                                        magneticReference = if (trial.tau == null) null else reference,
                                        magneticTauSeconds = trial.tau ?: 10.0, magneticYawOnly = trial.yawOnly,
                                        magneticLocalReference = trial.local, magneticReferenceMaxAgeSeconds = trial.maxAge,
                                        magneticAnchorBudgetDegrees = trial.budget, magneticLocalInnovationLimitDegrees = trial.innovationLimit,
                                        independentMotionGate = trial.independentGate,
                                        magneticHoldStartNanos = if (trial.hold) start + 1_160_000_000_000L else Long.MAX_VALUE,
                                        magneticHoldEndNanos = if (trial.hold) start + 1_195_000_000_000L else Long.MIN_VALUE)))
                                }
                                lastGyro = time
                            } else {
                                currentProduction!!.onGyroscope(time, v)
                                val alpha = 1.0 - exp(-(time - lastGyro) / 1e9 / (-1.0 / (59.0 * ln(.85))))
                                lastGyro = time
                                candidates.forEach { c ->
                                    c.engine.onGyroscope(time, v)
                                    val raw = c.engine.chassisBankDegrees
                                    val target = (if (abs(raw) < .2) 0.0 else raw).coerceIn(-65.0, 65.0)
                                    c.display += alpha * (target - c.display)
                                }
                                cloneDifference = maxOf(cloneDifference, abs(candidates.first().engine.chassisBankDegrees - currentProduction!!.chassisBankDegrees))
                                val baseline = candidates.first().display
                                for (c in candidates) {
                                    val freshFix = t - locationTime in 0.0..2.5
                                    c.windows.filter { w -> t in w.range && when(w.name) {
                                        "largeRecordedBank" -> abs(baseline) >= 20.0
                                        "steadyCourseProxy" -> freshFix && speed >= 8.0 && abs(courseRate) < .02 && abs(alongAcceleration) < .5
                                        "brakingProxy" -> freshFix && speed >= 3.0 && alongAcceleration < -1.0
                                        "acceleratingProxy" -> freshFix && speed >= 3.0 && alongAcceleration > 1.0
                                        "curveExitProxy" -> freshFix && speed >= 8.0 && abs(courseRate) < .02 && t - recentTurnTime in 0.0..10.0
                                        else -> true
                                    } }.forEach {
                                        it.values.add(c.display); it.differences.add(abs(c.display - baseline))
                                        if (abs(c.display - baseline) > it.maximumDifference) {
                                            it.maximumDifference = abs(c.display - baseline); it.maximumTime = t
                                            it.maximumBaseline = baseline; it.maximumCandidate = c.display
                                            it.maximumGpsReason = c.engine.lastDecision?.reason.toString()
                                        }
                                    }
                                    if (t >= 20.0 && abs(baseline) >= 5.0 && c.display * baseline < 0.0) c.signDifferences++
                                }
                                if (file.name.contains("20261004") && t in 1158.0..1196.0 && t.toInt() != lastTraceSecond) {
                                    lastTraceSecond = t.toInt()
                                    candidates.filter { it.trial.name in setOf("current_no_magnet", "current_local_tau30_age10", "current_local_tau30_independent", "current_local_tau30_independent_budget1") }.forEach { c ->
                                        println("MAG_TRACE t=$t trial=${c.trial.name} raw=${c.engine.chassisBankDegrees} display=${c.display} gpsDelta=${c.engine.gpsBankDelta} magDelta=${c.engine.magneticBankDelta} magAbs=${c.engine.magneticAbsoluteBankDelta} anchorAge=${c.engine.magneticAnchorAgeSeconds} anchorRotation=${c.engine.magneticAnchorRotationDegrees} innovation=${c.engine.lastMagneticInnovationDegrees} gpsReason=${c.engine.lastDecision?.reason}")
                                    }
                                }
                                if (t in 80.0..150.0) {
                                    val legacy = candidates.first { it.trial.name == "legacy_no_magnet" }.display
                                    f.getOrNull(40)?.toDoubleOrNull()?.let {
                                        recordedStartupSamples++
                                        recordedStartupDifference = maxOf(recordedStartupDifference, abs(it - legacy))
                                    }
                                }
                            }
                        } else if (f[3] == "1" && candidates.isNotEmpty()) {
                            currentProduction!!.onAccelerometer(time, v)
                            candidates.forEach { it.engine.onAccelerometer(time, v) }
                        } else if (f[3] == "2" && candidates.isNotEmpty() && t >= 20.0) {
                            candidates.forEach { it.engine.onMagnetometer(time, v, f[7].toInt()) }
                        }
                    } else if (f[0] == "location" && candidates.isNotEmpty()) {
                        fun number(i: Int) = f[i].toDoubleOrNull() ?: Double.NaN
                        val time = f[2].toLong()
                        if (f[22] == "gps") {
                        val fixSpeed = number(18)
                        val fixBearing = number(20)
                        val dt = (time - lastFixTime) / 1e9
                        locationTime = (time - start) / 1e9
                        speed = fixSpeed
                        val quality = f[28] == "true" && f[29] == "true" &&
                            number(21) in 0.0..5.0 && number(19) in 0.0..1.0 &&
                            number(17) in 0.0..15.0
                        val good = quality && previousFixQuality && dt in .3..2.5
                        courseRate = if (good) Math.toRadians(((fixBearing - lastFixBearing + 540.0) % 360.0) - 180.0) / dt else Double.NaN
                        alongAcceleration = if (good) (fixSpeed - lastFixSpeed) / dt else Double.NaN
                        if (speed >= 8.0 && abs(courseRate) >= .08) recentTurnTime = locationTime
                        lastFixTime = time; lastFixSpeed = fixSpeed; lastFixBearing = fixBearing
                        previousFixQuality = quality
                        }
                        val arrival = f.getOrNull(43)?.toLongOrNull() ?: (start + (f[1].toLong() - firstWall) * 1_000_000L)
                        currentProduction!!.onFix(CompensatedImuLeanExperiment.Fix(time, arrival, number(18),
                            number(20), number(21), number(19), number(17), f[28] == "true", f[29] == "true", f[22]))
                        candidates.forEach { c ->
                            val before = c.engine.chassisBankDegrees
                            c.engine.onFix(MagneticReplayEstimator.Fix(time, arrival, number(18),
                                number(20), number(21), number(19), number(17), f[28] == "true", f[29] == "true", f[22]))
                            val d = c.engine.lastDecision
                            val trace = d?.gravityTrace
                            if (d?.reason == MagneticReplayEstimator.DecisionReason.SHOCK_WINDOW) c.gpsShockRejected++
                            if (d?.reason == MagneticReplayEstimator.DecisionReason.ACCEPTED && trace != null) {
                                val step = abs(c.engine.chassisBankDegrees - before)
                                c.gpsMaxStep = maxOf(c.gpsMaxStep, step)
                                if (step > 2.0) c.gpsLargeSteps++
                                if (trace.rawAccelerationPeakMps2 > 2.5 * 9.80665) c.gpsShockAccepted++
                                if ((d.maxRollRate ?: 0.0) > .225 || (d.yawRateRange ?: 0.0) > .09) c.gpsNearBoundary++
                                if (c.trial.name == "current_no_magnet") {
                                    val row = "t=${(time-start)/1e9} step=$step before=$before after=${c.engine.chassisBankDegrees} rotation=${trace.appliedRotationDegrees} peak=${trace.rawAccelerationPeakMps2} rms=${trace.rawAccelerationRmsDeviationMps2} roll=${d.maxRollRate} yawRange=${d.yawRateRange} innovation=${d.innovationDegrees}"
                                    c.topGps.add(step to row)
                                    c.topGps.sortByDescending { it.first }
                                    if (c.topGps.size > 8) c.topGps.removeAt(8)
                                }
                            }
                            if (c.trial.independentGate) {
                                assertEquals("Magnetic update changed movement gate at $time",
                                    candidates.first().engine.lastMotionGate, c.engine.lastMotionGate)
                                independentGateChecks++
                                if (c.engine.lastMotionGate != null) independentMotionEvaluations++
                            }
                            if (file.name.contains("20261004") && (time - start) / 1e9 in 1170.0..1183.0 &&
                                f[22] == "gps" && c.trial.name in setOf("current_no_magnet", "current_local_tau30_age10", "current_local_tau30_hold1160to1195", "current_local_tau30_independent", "current_local_tau30_independent_budget1")) {
                                val d = c.engine.lastDecision
                                println("MAG_FIX t=${(time-start)/1e9} trial=${c.trial.name} reason=${d?.reason} before=$before after=${c.engine.chassisBankDegrees} innovation=${d?.innovationDegrees} rollRate=${d?.maxRollRate} yawRange=${d?.yawRateRange} inferredBank=${d?.gravityTrace?.inferredBank}")
                            }
                        }
                    }
                }
        }
        assertTrue("No nontrivial independent movement gates evaluated", independentMotionEvaluations > 100)
        assertTrue("Clone differs from production: $cloneDifference", cloneDifference < 1e-9)
        if (recordedStartupSamples > 0)
            assertTrue("Legacy startup is not reproduced: $recordedStartupDifference", recordedStartupDifference < .02)
        else assertEquals("Expected legacy output column in the October 6 ZIP", "csv", file.extension)
        println("MAG_REPLAY independentGateChecks=$independentGateChecks independentMotionEvaluations=$independentMotionEvaluations durationSeconds=$duration cloneMaxDifference=$cloneDifference legacyRecordedStartupSamples=$recordedStartupSamples legacyRecordedStartupMaxDifference=$recordedStartupDifference")
        for (c in candidates) {
            if (c.trial.name == "current_no_magnet" || c.trial.name.startsWith("gps_")) {
                println("GPS_AUDIT trial=${c.trial.name} accepted=${c.engine.acceptedCorrections} largeSteps=${c.gpsLargeSteps} maxStep=${c.gpsMaxStep} shockAccepted=${c.gpsShockAccepted} shockRejected=${c.gpsShockRejected} nearBoundaryAccepted=${c.gpsNearBoundary}")
                c.topGps.forEach { println("GPS_AUDIT_TOP $it") }
            }
            if (file.extension != "csv") assertEquals(0, c.engine.gyroGaps)
            println("MAG_REPLAY trial=${c.trial.name} magneticDecisions=${c.engine.magneticDecisions} gpsAccepted=${c.engine.acceptedCorrections} gyroGaps=${c.engine.gyroGaps} signDifferenceSamples=${c.signDifferences}")
            for (w in c.windows) {
                val sorted = w.differences.sorted()
                val p95 = if (sorted.isEmpty()) Double.NaN else sorted[((sorted.size - 1) * .95).toInt()]
                println("MAG_REPLAY trial=${c.trial.name} window=${w.name} n=${w.values.size} signedMean=${w.values.average()} meanAbsolute=${w.values.absoluteAverage()} min=${w.values.minOrNull()} max=${w.values.maxOrNull()} differenceMean=${w.differences.average()} differenceP95=$p95 differenceMax=${w.differences.maxOrNull()} maxAtSeconds=${w.maximumTime} baselineAtMax=${w.maximumBaseline} candidateAtMax=${w.maximumCandidate} lastGpsReason=${w.maximumGpsReason}")
            }
        }
        // Actual riding bank is unknown. Smaller outputs are not asserted as accuracy.
    }
}
