package com.dpm.pegdown.sensor

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class StationaryCorrectionTest {
    private val forward = ImuVector(0.0, 1.0, 0.0)
    private fun engine(initialBank: Double = 0.0) = CompensatedImuLeanExperiment(
        ImuVector.UP, forward, ImuQuaternion.axisAngle(forward, Math.toRadians(initialBank)),
        1_000_000_000L, qualifyStationaryCorrections = true)
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
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_UNSTABLE, gap.lastDecision!!.reason)
        assertFalse(gap.lastDecision!!.gravityTrace!!.stationaryTrace!!.historyCovered)
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
