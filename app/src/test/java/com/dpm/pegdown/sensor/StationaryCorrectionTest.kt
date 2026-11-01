package com.dpm.pegdown.sensor

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class StationaryCorrectionTest {
    private val forward = ImuVector(0.0, 1.0, 0.0)
    private fun engine(initialBank: Double = 0.0, initialSpeed: Double = 0.0) =
        CompensatedImuLeanExperiment(
            ImuVector.UP, forward, ImuQuaternion.axisAngle(forward, Math.toRadians(initialBank)),
            1_000_000_000L, qualifyStationaryCorrections = true,
        ).also { it.onFix(fix(1_000_000_000L).copy(speedMps = initialSpeed)) }
    private fun fix(time: Long) = CompensatedImuLeanExperiment.Fix(
        time, time + 100_000_000L, 0.0, 0.0, 180.0, .1, 3.0)
    private fun fill(
        engine: CompensatedImuLeanExperiment, from: Int, until: Int,
        acceleration: (Int) -> ImuVector = { ImuVector.UP * 9.80665 },
        rate: (Int) -> ImuVector = { ImuVector.ZERO },
    ) {
        for (i in from..until) {
            val t = 1_000_000_000L + i * 10_000_000L
            engine.onGyroscope(t, rate(i))
            engine.onAccelerometer(t, acceleration(i))
        }
    }

    @Test fun uniformBrakingAtWalkingSpeedCannotBecomeAFalseTiltOrBank() {
        val guarded = engine(initialSpeed = 2.3)
        val control = CompensatedImuLeanExperiment(ImuVector.UP, forward,
            ImuQuaternion.IDENTITY, 1_000_000_000L)
        control.onFix(fix(1_000_000_000L).copy(speedMps = 2.3))
        for (e in listOf(control, guarded)) {
            fill(e, 0, 110, acceleration = { ImuVector(0.0, -2.0, 9.80665) })
            // Upright braking from 2.3 m/s: actual and reported speed at 1 s is 0.3 m/s.
            e.onFix(fix(2_000_000_000L).copy(speedMps = .3, speedAccuracyMps = .05))
        }
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, control.lastDecision!!.reason)
        assertEquals(3.2675459417, control.lastDecision!!.gravityTrace!!.appliedRotationDegrees!!, 1e-8)
        val rejected = guarded.lastDecision!!
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, rejected.reason)
        val speedTrace = requireNotNull(rejected.stationarySpeedTrace)
        assertTrue(speedTrace.historyCovered)
        assertTrue(speedTrace.maximumAccelerationUpperMps2!! > 2.0)
        assertNull(rejected.gravityTrace)
        // Geometric yaw coupling, not a complete motorcycle trajectory.
        for (i in 1..100) {
            val t = 2_100_000_000L + i * 10_000_000L
            listOf(control, guarded).forEach { it.onGyroscope(t, ImuVector.UP * (Math.PI / 2.0)) }
        }
        assertEquals(0.0, guarded.chassisBankDegrees, 1e-9)
        assertEquals(3.2675459417, control.chassisBankDegrees, 1e-8)
    }

    @Test fun changingLowSpeedsAreRejectedEvenWhenEveryFixIsBelowTheStopThreshold() {
        val e = engine(initialSpeed = .35)
        fill(e, 0, 60, acceleration = { ImuVector(0.0, -.35, 9.80665) })
        e.onFix(fix(1_500_000_000L).copy(speedMps = .175))
        fill(e, 61, 110, acceleration = { ImuVector(0.0, -.35, 9.80665) })
        e.onFix(fix(2_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, e.lastDecision!!.reason)
        val trace = e.lastDecision!!.stationarySpeedTrace!!
        assertTrue(trace.historyCovered)
        assertTrue(trace.maximumSpeedMps!! <= .4)
        assertTrue(trace.maximumAccelerationUpperMps2!! > .5)
        fill(e, 111, 220)
        e.onFix(fix(3_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
        assertTrue(e.lastDecision!!.stationarySpeedTrace!!.qualified)
    }

    @Test fun halfSecondFixesRequireACompleteSpeedWindowAndThenResumeAutomatically() {
        val e = engine()
        fill(e, 0, 60)
        e.onFix(fix(1_500_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, e.lastDecision!!.reason)
        assertFalse(e.lastDecision!!.stationarySpeedTrace!!.historyCovered)
        fill(e, 61, 110)
        e.onFix(fix(2_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
        assertTrue(e.lastDecision!!.stationarySpeedTrace!!.qualified)
    }

    @Test fun missingStaleOrImpreciseSpeedHistoryCannotSupplyAStationaryReference() {
        val missing = CompensatedImuLeanExperiment(ImuVector.UP, forward,
            ImuQuaternion.IDENTITY, 1_000_000_000L, qualifyStationaryCorrections = true)
        fill(missing, 0, 120)
        missing.onFix(fix(2_000_000_000L))
        assertFalse(missing.lastDecision!!.stationarySpeedTrace!!.historyCovered)

        val stale = engine()
        fill(stale, 0, 320)
        stale.onFix(fix(4_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, stale.lastDecision!!.reason)
        assertFalse(stale.lastDecision!!.stationarySpeedTrace!!.historyCovered)

        val imprecise = engine()
        fill(imprecise, 0, 120)
        imprecise.onFix(fix(2_000_000_000L).copy(speedAccuracyMps = .31))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, imprecise.lastDecision!!.reason)
        assertTrue(imprecise.lastDecision!!.stationarySpeedTrace!!.historyCovered)
        assertFalse(imprecise.lastDecision!!.stationarySpeedTrace!!.qualified)
    }

    @Test fun aBadFixClearsSpeedHistoryAndDoesNotRequireManualRecovery() {
        val e = engine()
        fill(e, 0, 120)
        e.onFix(fix(2_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
        e.onFix(fix(2_500_000_000L).copy(speedAccuracyMps = 2.0))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.BAD_FIX, e.lastDecision!!.reason)
        fill(e, 121, 220)
        e.onFix(fix(3_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, e.lastDecision!!.reason)
        fill(e, 221, 320)
        e.onFix(fix(4_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
        e.onFix(fix(4_100_000_000L).copy(provider = "network"))
        assertNull(e.lastDecision!!.stationarySpeedTrace)
        e.onFix(fix(4_200_000_000L).copy(speedMps = 12.0, bearingAccuracyDegrees = 1.0))
        assertNull(e.lastDecision!!.stationarySpeedTrace)
    }

    @Test fun coarseGpsIntervalMayContainBrakingFollowedByACompleteQuietStop() {
        val e = engine(initialSpeed = 4.0)
        // Brake during the first 0.5 s, then remain genuinely stationary for 1.5 s.
        fill(e, 0, 210, acceleration = { i ->
            ImuVector(0.0, if (i < 50) -8.0 else 0.0, 9.80665)
        })
        e.onFix(fix(3_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
        assertTrue(e.lastDecision!!.stationarySpeedTrace!!.coarseStopTransition)
        assertTrue(e.lastDecision!!.gravityTrace!!.stationaryTrace!!.qualified)
        assertEquals(0.0, e.lastDecision!!.gravityTrace!!.appliedRotationDegrees!!, 1e-9)

        val stillBraking = engine(initialSpeed = 4.3)
        fill(stillBraking, 0, 210, acceleration = { ImuVector(0.0, -2.0, 9.80665) })
        stillBraking.onFix(fix(3_000_000_000L).copy(speedMps = .3, speedAccuracyMps = .05))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, stillBraking.lastDecision!!.reason)
        assertFalse(stillBraking.lastDecision!!.stationarySpeedTrace!!.coarseStopTransition)
    }

    @Test fun coarseStopRequiresBothTheLongIntervalAndTheNearZeroEndpoint() {
        fun decision(seconds: Double, speed: Double): CompensatedImuLeanExperiment.CorrectionDecision {
            val e = engine(initialSpeed = 4.0)
            fill(e, 0, (seconds * 100 + 10).toInt(), acceleration = { i ->
                ImuVector(0.0, if (i < 50) -8.0 else 0.0, 9.80665)
            })
            e.onFix(fix(1_000_000_000L + (seconds * 1e9).toLong()).copy(speedMps = speed))
            return e.lastDecision!!
        }
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, decision(1.499, 0.0).reason)
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, decision(1.5, .1).reason)
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, decision(1.5, .100001).reason)
    }

    @Test fun speedQualificationLimitsAreInclusive() {
        fun trace(covered: Boolean, speed: Double, error: Double, acceleration: Double) =
            CompensatedImuLeanExperiment.StationarySpeedTrace(0L, 750_000_000L,
                covered, speed, error, acceleration)
        assertTrue(trace(true, .4, .3, .5).qualified)
        assertFalse(trace(false, .4, .3, .5).qualified)
        assertFalse(trace(true, .400001, .3, .5).qualified)
        assertFalse(trace(true, .4, .300001, .5).qualified)
        assertFalse(trace(true, .4, .3, .500001).qualified)
    }

    @Test fun qualificationLimitsAreInclusiveAndMissingHistoryCannotPass() {
        fun trace(covered: Boolean, delta: Double, rate: Double) =
            CompensatedImuLeanExperiment.StationaryTrace(0L, 750_000_000L, covered, delta, rate)
        assertTrue(trace(true, 1.0, .12).qualified)
        assertFalse(trace(true, 1.000001, .12).qualified)
        assertFalse(trace(true, 1.0, .120001).qualified)
        assertFalse(trace(false, 0.0, 0.0).qualified)
    }

    @Test fun stableSideStandAndLargeDriftRemainCorrectable() {
        val e = engine(5.0)
        val real = ImuQuaternion.axisAngle(forward, Math.toRadians(-15.0))
            .inverse().rotate(ImuVector.UP * 9.80665)
        for (second in 1..18) {
            fill(e, (second - 1) * 100, second * 100, acceleration = { real })
            e.onFix(fix(1_000_000_000L + second * 1_000_000_000L - 100_000_000L))
            assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
        }
        assertEquals(-15.0, e.chassisBankDegrees, .1)
    }

    @Test fun meanStabilityAllowsHighFrequencyVibrationWithoutANormPeakVeto() {
        val e = engine()
        fill(e, 0, 120, acceleration = { i ->
            ImuVector(3.0 * sin(2 * Math.PI * i / 5.0), 0.0,
                9.80665 + 4.0 * sin(2 * Math.PI * i / 5.0))
        })
        e.onFix(fix(2_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
        assertTrue(e.lastDecision!!.gravityTrace!!.rawAccelerationRmsDeviationMps2 > 3.0)
    }

    @Test fun gpsZeroDuringAccelerationIsRejectedAndSettlingAutomaticallyResumes() {
        val e = engine()
        fill(e, 0, 120, acceleration = { i ->
            ImuVector(0.0, if (i >= 90) 3.5 else 0.0, 9.80665)
        })
        e.onFix(fix(2_000_000_000L))
        val decision = e.lastDecision!!
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_UNSTABLE, decision.reason)
        assertTrue(decision.gravityTrace!!.stationaryTrace!!.maxMeanAccelerationDifferenceMps2!! > 1.0)
        assertEquals(0.0, e.chassisBankDegrees, 1e-9)
        fill(e, 121, 230)
        e.onFix(fix(3_200_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
    }

    @Test fun rotationCannotBeMistakenForStationaryAndAGyroGapRequiresFreshHistory() {
        val rotating = engine()
        fill(rotating, 0, 120, rate = { ImuVector.UP * .3 })
        rotating.onFix(fix(2_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_UNSTABLE, rotating.lastDecision!!.reason)
        assertTrue(rotating.lastDecision!!.gravityTrace!!.stationaryTrace!!.maxMeanAngularRateRadps!! > .12)

        val gap = engine()
        fill(gap, 0, 120)
        gap.onGyroscope(2_500_000_000L, ImuVector.ZERO)
        fill(gap, 151, 180)
        gap.onFix(fix(2_700_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_SPEED_UNCERTAIN, gap.lastDecision!!.reason)
        assertFalse(gap.lastDecision!!.stationarySpeedTrace!!.historyCovered)
        fill(gap, 181, 270)
        gap.onFix(fix(3_600_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, gap.lastDecision!!.reason)
    }

    @Test fun movingMotorcycleTurnIsIdenticalWithAndWithoutTheStationaryCheck() {
        val pose = ImuQuaternion.axisAngle(forward, Math.toRadians(20.0))
        val engines = listOf(false, true).map { guarded ->
            CompensatedImuLeanExperiment(ImuVector.UP, forward, pose, 1_000_000_000L,
                matchGpsInterval = true, qualifyStationaryCorrections = guarded)
        }
        val speed = 15.0
        val yaw = -9.80665 * tan(Math.toRadians(20.0)) / speed
        val bodyUp = pose.inverse().rotate(ImuVector.UP)
        val acceleration = bodyUp * 9.80665 + forward.cross(bodyUp).unit() * (speed * -yaw)
        for (i in 0..1000) {
            val time = 1_000_000_000L + i * 10_000_000L
            engines.forEach {
                it.onGyroscope(time, bodyUp * yaw)
                it.onAccelerometer(time, acceleration)
                if (i % 100 == 0) it.onFix(fix(time).copy(speedMps = speed,
                    bearingDegrees = ((-Math.toDegrees(yaw * i / 100.0)) % 360 + 360) % 360,
                    bearingAccuracyDegrees = 1.0))
            }
            assertEquals(engines[0].chassisBankDegrees, engines[1].chassisBankDegrees, 0.0)
            assertEquals(20.0, engines[1].chassisBankDegrees, .05)
        }
        assertTrue(engines[1].acceptedCorrections > 0)
        assertNull(engines[1].lastDecision!!.gravityTrace!!.stationaryTrace)
    }

    @Test fun stableSensorDirectionsSurviveASmallTimestampPhaseOffset() {
        val e = engine()
        for (i in 0..120) {
            val t = 1_000_000_000L + i * 10_000_000L
            e.onGyroscope(t, ImuVector.ZERO)
            e.onAccelerometer(t + 2_000_000L, ImuVector.UP * 9.80665)
        }
        e.onFix(fix(2_000_000_000L))
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, e.lastDecision!!.reason)
    }
}
