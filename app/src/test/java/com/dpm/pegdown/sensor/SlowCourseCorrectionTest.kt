package com.dpm.pegdown.sensor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowCourseCorrectionTest {
    private val mounted = ImuQuaternion.axisAngle(ImuVector(1.0, 0.0, 0.0), Math.toRadians(80.0))
    private val up = mounted.inverse().rotate(ImuVector.UP)
    private val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(up)

    private fun run(
        enabled: Boolean = true,
        speed: Double = 5.0,
        accuracy: Double = 0.3,
        actualBank: Double = 0.0,
        initialBias: Double = 10.0,
        falseCourseRate: Double = 0.0,
        gpsStep: Int = 100,
    ): CompensatedImuLeanExperiment {
        val base = ImuQuaternion.axisAngle(ImuVector(0.0, 1.0, 0.0), Math.toRadians(actualBank)) * mounted
        val biased = ImuQuaternion.axisAngle(ImuVector(0.0, 1.0, 0.0), Math.toRadians(actualBank + initialBias)) * mounted
        val estimator = CompensatedImuLeanExperiment(up, forward, biased, 1_000_000_000L,
            matchGpsInterval = true, allowSlowCourseCorrections = enabled)
        val yawRate = 9.80665 * tan(Math.toRadians(actualBank)) / speed
        for (i in 0..3000) {
            val t = i * 0.01
            val time = 1_000_000_000L + i * 10_000_000L
            val course = yawRate * t
            val pose = ImuQuaternion.axisAngle(ImuVector.UP, -course) * base
            if (i > 0) estimator.onGyroscope(time, pose.inverse().rotate(ImuVector.UP) * -yawRate)
            val right = ImuVector(cos(course), -sin(course), 0.0)
            estimator.onAccelerometer(time, pose.inverse().rotate(ImuVector.UP * 9.80665 + right * (speed * yawRate)))
            if (i % gpsStep == 0) {
                estimator.onFix(CompensatedImuLeanExperiment.Fix(
                    time, time, speed, ((Math.toDegrees(course) + falseCourseRate * t) % 360.0 + 360.0) % 360.0,
                    accuracy, 0.2, 3.0,
                ))
            }
        }
        return estimator
    }

    @Test
    fun `good slow course corrects attitude bias while disabled control stays biased`() {
        val candidate = run()
        val control = run(enabled = false)
        assertTrue(candidate.acceptedCorrections > 20)
        assertEquals(0.0, candidate.chassisBankDegrees, 0.2)
        assertEquals(10.0, control.chassisBankDegrees, 1e-8)
    }

    @Test
    fun `real slow turn retains bank instead of zeroing the display`() {
        val estimator = run(actualBank = 20.0, initialBias = 0.0)
        assertTrue(estimator.acceptedCorrections > 20)
        assertEquals(20.0, estimator.chassisBankDegrees, 0.3)
    }

    @Test
    fun `poor bearing or walking speed never enable moving gravity correction`() {
        for (estimator in listOf(run(accuracy = 3.0), run(speed = 2.0))) {
            assertEquals(0, estimator.acceptedCorrections)
            assertEquals(10.0, estimator.chassisBankDegrees, 1e-8)
        }
    }

    @Test
    fun `fast course sampling with excessive differentiated uncertainty is rejected`() {
        val estimator = run(speed = 7.0, accuracy = 2.0, gpsStep = 50)
        assertEquals(0, estimator.acceptedCorrections)
    }

    @Test
    fun `precise but inconsistent slow GPS course cannot create false bank`() {
        val estimator = run(initialBias = 0.0, falseCourseRate = 15.0)
        assertEquals(0, estimator.acceptedCorrections)
        assertTrue(estimator.decisions.any { it.reason == CompensatedImuLeanExperiment.DecisionReason.COURSE_GYRO_MISMATCH })
        assertTrue(abs(estimator.chassisBankDegrees) < 1e-8)
    }

    @Test
    fun `high speed results remain identical when optional slow correction is enabled`() {
        val candidate = run(speed = 18.0, actualBank = 20.0, initialBias = 0.0)
        val control = run(enabled = false, speed = 18.0, actualBank = 20.0, initialBias = 0.0)
        assertEquals(control.chassisBankDegrees, candidate.chassisBankDegrees, 1e-9)
        assertEquals(control.acceptedCorrections, candidate.acceptedCorrections)
    }
}
