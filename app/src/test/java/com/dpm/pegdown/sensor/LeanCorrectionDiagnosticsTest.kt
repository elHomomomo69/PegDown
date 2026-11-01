package com.dpm.pegdown.sensor

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class LeanCorrectionDiagnosticsTest {
    private val forward = ImuVector(0.0, 1.0, 0.0)
    private fun fix(time: Long, provider: String = "gps") =
        CompensatedImuLeanExperiment.Fix(time, time + 200_000_000L, 18.0, 0.0, 1.0, .2, 3.0, provider = provider)

    @Test fun acceptedCorrectionRecordsMotionAndRawSpreadWithoutStaleValues() {
        val engine = CompensatedImuLeanExperiment(ImuVector.UP, forward,
            ImuQuaternion.IDENTITY, 0L, matchGpsInterval = true)
        engine.onFix(fix(0L))
        for (i in 0..120) {
            val time = i * 10_000_000L
            engine.onGyroscope(time, ImuVector.ZERO)
            engine.onAccelerometer(time, ImuVector(0.0, 0.0,
                9.80665 + if (i % 2 == 0) 1.0 else -1.0))
        }
        engine.onFix(fix(1_000_000_000L))
        val decision = engine.lastDecision!!
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, decision.reason)
        val motion = decision.motionTrace!!
        assertEquals(0L, motion.intervalStartNanos)
        assertEquals(1_000_000_000L, motion.intervalEndNanos)
        assertEquals(1.0, motion.intervalSeconds, 0.0)
        assertTrue(motion.binCount >= 2)
        assertEquals(0.0, decision.maxRollRate!!, 0.0)
        assertEquals(0.0, decision.yawRateRange!!, 0.0)
        assertTrue(motion.sigmaLateralMps2.isFinite())
        val trace = decision.gravityTrace!!
        assertEquals(101, trace.accelerationSampleCount)
        assertEquals(10.80665, trace.rawAccelerationPeakMps2, 1e-9)
        assertEquals(1.0, trace.rawAccelerationRmsDeviationMps2, .001)
        assertEquals(0.0, trace.appliedRotationDegrees!!, 1e-9)
        engine.onFix(fix(1_100_000_000L, "network"))
        assertNull(engine.lastDecision!!.motionTrace)
        assertNull(engine.lastDecision!!.gravityTrace)
        assertNull(engine.lastDecision!!.maxRollRate)
        engine.onFix(fix(1_200_000_000L).copy(speedMps = 0.0))
        assertNull(engine.lastDecision!!.motionTrace)
    }

    @Test fun movementThresholdsAcceptBelowAndRejectAboveTheirLimits() {
        fun decision(roll: Double, yaw: Double): CompensatedImuLeanExperiment.CorrectionDecision {
            val engine = CompensatedImuLeanExperiment(ImuVector.UP, forward,
                ImuQuaternion.IDENTITY, 0L, matchGpsInterval = true)
            engine.onFix(fix(0L))
            var orientation = ImuQuaternion.IDENTITY
            for (i in 0..120) {
                val time = i * 10_000_000L
                val rate = ImuVector(0.0, roll, if (i < 50) -yaw else yaw)
                if (i > 0) orientation = (orientation *
                    ImuQuaternion.axisAngle(rate, rate.length() * .01)).unit()
                engine.onGyroscope(time, rate)
                engine.onAccelerometer(time, orientation.inverse().rotate(ImuVector.UP * 9.80665))
            }
            engine.onFix(fix(1_000_000_000L))
            return engine.lastDecision!!
        }
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, decision(.249999, 0.0).reason)
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ROLLING, decision(.250001, 0.0).reason)
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, decision(0.0, .049999).reason)
        val rejected = decision(0.0, .050001)
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.CHANGING_YAW, rejected.reason)
        assertTrue(rejected.yawRateRange!! > .10)
        assertNotNull(rejected.motionTrace)
    }

    @Test fun replayRotationBudgetAndShockGuardHaveDefinedSyntheticBehavior() {
        fun replay(rate: Double, shock: Double, injectShock: Boolean): MagneticReplayEstimator {
            val engine = MagneticReplayEstimator(ImuVector.UP, forward,
                ImuQuaternion.axisAngle(forward, Math.toRadians(15.0)), 0L,
                matchGpsInterval = true, maxGpsRotationRateDegreesPerSecond = rate,
                shockPeakLimitMps2 = shock)
            engine.onFix(MagneticReplayEstimator.Fix(0L, 0L, 18.0, 0.0, 1.0, .2, 3.0))
            for (i in 0..120) {
                val time = i * 10_000_000L
                engine.onGyroscope(time, ImuVector.ZERO)
                engine.onAccelerometer(time, ImuVector(0.0, 0.0,
                    if (injectShock && i == 50) 50.0 else 9.80665))
            }
            engine.onFix(MagneticReplayEstimator.Fix(1_000_000_000L, 1_200_000_000L,
                18.0, 0.0, 1.0, .2, 3.0))
            return engine
        }
        val limited = replay(2.0, Double.POSITIVE_INFINITY, false)
        assertEquals(MagneticReplayEstimator.DecisionReason.ACCEPTED, limited.lastDecision!!.reason)
        val trace = limited.lastDecision!!.gravityTrace!!
        assertTrue(trace.requestedRotationDegrees!! > 2.0)
        assertEquals(2.0, trace.appliedRotationDegrees!!, 1e-9)
        assertTrue(abs(limited.chassisBankDegrees) > 10.0) // A cap also delays useful recovery.
        assertEquals(MagneticReplayEstimator.DecisionReason.ACCEPTED,
            replay(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, true).lastDecision!!.reason)
        val rejected = replay(Double.POSITIVE_INFINITY, 2.5 * 9.80665, true).lastDecision!!
        assertEquals(MagneticReplayEstimator.DecisionReason.SHOCK_WINDOW, rejected.reason)
        assertEquals(50.0, rejected.gravityTrace!!.rawAccelerationPeakMps2, 1e-9)
    }
}
