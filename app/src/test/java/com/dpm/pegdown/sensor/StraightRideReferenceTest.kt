package com.dpm.pegdown.sensor

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class StraightRideReferenceTest {
    private fun observation(i: Int, start: Double = 359.0, end: Double = 359.0) =
        StraightRideReference.Observation(i * 1_000_000_000L, (i+1) * 1_000_000_000L,
            18.0, 18.0, start, end, 1.0, 1.0, .2, 3.0, .12, 0.0, 0.0, 0.0, .2, 2.0, 9.80665)

    @Test fun fiveSecondsAreRequiredAndCourseWrapIsHandled() {
        val reference = StraightRideReference()
        repeat(4) { assertFalse(reference.observe(observation(it)).qualified) }
        assertTrue(reference.observe(observation(4, 359.0, 0.0)).qualified)
    }

    @Test fun longShallowCurveAndLargeCourseUncertaintyAreNotZeroReferences() {
        val curved = StraightRideReference()
        repeat(12) { i ->
            assertFalse(curved.observe(observation(i, i * .5, (i+1) * .5)
                .copy(gyroYawRadps = Math.toRadians(.5))).qualified)
        }
        val uncertain = StraightRideReference()
        repeat(12) { i ->
            assertFalse(uncertain.observe(observation(i).copy(
                minimumSpeedMps = 60.0, maximumSpeedMps = 60.0,
                startCourseAccuracyDegrees = 1.5, endCourseAccuracyDegrees = 1.5)).qualified)
        }
    }

    @Test fun badQualityBrakingRealLeanAndMissingIntervalsRestartTheWindow() {
        val transforms: List<(StraightRideReference.Observation) -> StraightRideReference.Observation> = listOf(
            { it.copy(maximumSpeedAccuracyMps = .6) },
            { it.copy(alongAccelerationMps2 = -1.0) },
            { it.copy(inferredBankDegrees = 2.0) },
            { it.copy(fixAgeSeconds = 1.1) },
            { it.copy(minimumSpeedMps = 7.0) },
            { it.copy(maximumRollRateRadps = .05) },
            { it.copy(gravityMagnitudeMps2 = 11.0) },
            { it.copy(gyroYawRadps = Double.NaN) },
        )
        for (transform in transforms) {
            val r = StraightRideReference()
            repeat(5) { r.observe(observation(it)) }
            assertFalse(r.observe(transform(observation(5))).qualified)
            repeat(4) { assertFalse(r.observe(observation(it+6)).qualified) }
        }
        val r = StraightRideReference()
        repeat(5) { r.observe(observation(it)) }
        assertFalse(r.observe(observation(7)).qualified)
    }

    @Test fun pitchChangeAndExplicitResetInvalidateTheReference() {
        val r = StraightRideReference()
        repeat(5) { r.observe(observation(it)) }
        assertFalse(r.observe(observation(5).copy(inferredPitchDegrees = 6.0)).qualified)
        assertFalse(r.reset("GYRO_GAP").qualified)
        assertFalse(r.observe(observation(6)).qualified)
    }

    @Test fun aTrueGentleMotorcycleTurnIsNotFlattenedEvenWithSmallCourseChange() {
        val forward = ImuVector(0.0, 1.0, 0.0)
        for (bank in listOf(1.5, 20.0)) {
            val pose = ImuQuaternion.axisAngle(forward, Math.toRadians(bank))
            val speed = 40.0
            val yaw = -9.80665 * tan(Math.toRadians(bank)) / speed
            val bodyUp = pose.inverse().rotate(ImuVector.UP)
            val acceleration = bodyUp * 9.80665 + forward.cross(bodyUp).unit() * (-speed * yaw)
            val e = CompensatedImuLeanExperiment(ImuVector.UP, forward, pose, 1_000_000_000L,
                matchGpsInterval = true, correctStraightRideDrift = true)
            for (i in 0..2000) {
                val time = 1_000_000_000L + i * 10_000_000L
                e.onGyroscope(time, bodyUp * yaw)
                e.onAccelerometer(time, acceleration)
                if (i % 100 == 0) {
                    e.onFix(CompensatedImuLeanExperiment.Fix(time, time, speed,
                        ((-Math.toDegrees(yaw * i / 100.0)) % 360 + 360) % 360, 1.0, .2, 3.0))
                    assertNotEquals("APPLIED", e.straightTrace?.reason)
                }
                assertEquals(bank, e.chassisBankDegrees, .05)
            }
        }
    }

    @Test fun recoveryAndGyroOutageDiscardAnyPreviouslyQualifiedWindow() {
        val e = simulate(0.0, 0.0, true).first
        assertTrue(e.straightTrace!!.qualified)
        val time = 46_000_000_000L
        e.onFix(CompensatedImuLeanExperiment.Fix(time, time, 18.0, 0.0, 1.0, .2, 3.0),
            allowStraightCorrection = false)
        assertEquals("RECOVERY_PENDING", e.straightTrace!!.reason)
        assertFalse(e.straightTrace!!.qualified)
        val fresh = simulate(0.0, 0.0, true).first
        fresh.onGyroscope(47_000_000_000L, ImuVector.ZERO)
        assertEquals("GYRO_GAP", fresh.straightTrace!!.reason)
        assertFalse(fresh.straightTrace!!.qualified)
    }

    private fun simulate(bankDegrees: Double, gyroBiasRadps: Double,
        corrected: Boolean): Pair<CompensatedImuLeanExperiment, Int> {
        val forward = ImuVector(0.0, 1.0, 0.0)
        val pose = ImuQuaternion.axisAngle(forward, Math.toRadians(bankDegrees))
        val e = CompensatedImuLeanExperiment(ImuVector.UP, forward, pose, 1_000_000_000L,
            matchGpsInterval = true, qualifyStationaryCorrections = true,
            correctStraightRideDrift = corrected)
        var applications = 0
        for (i in 0..4500) {
            val time = 1_000_000_000L + i * 10_000_000L
            e.onGyroscope(time, forward * gyroBiasRadps)
            e.onAccelerometer(time, pose.inverse().rotate(ImuVector.UP * 9.80665))
            if (i % 100 == 0) {
                e.onFix(CompensatedImuLeanExperiment.Fix(time, time, 18.0, 0.0, 1.0, .2, 3.0))
                val trace = e.straightTrace
                assertTrue(abs(trace?.appliedBankDeltaDegrees ?: 0.0) <= .15000001)
                if (trace?.reason == "APPLIED") applications++
            }
        }
        return e to applications
    }

    @Test fun gradualCorrectionReducesPersistentBiasButPreservesSmallRealLean() {
        val control = simulate(0.0, .02, false)
        val guarded = simulate(0.0, .02, true)
        println("STRAIGHT_SYNTHETIC controlBank=${control.first.chassisBankDegrees} candidateBank=${guarded.first.chassisBankDegrees} applications=${guarded.second}")
        assertTrue(guarded.second > 0)
        assertTrue(abs(guarded.first.chassisBankDegrees) < abs(control.first.chassisBankDegrees))
        val smallLean = simulate(.6, 0.0, true)
        assertEquals(.6, smallLean.first.chassisBankDegrees, .01)
        assertEquals(0, smallLean.second)
        val crosswind = simulate(2.0, 0.0, true)
        assertEquals(2.0, crosswind.first.chassisBankDegrees, .01)
        assertEquals(0, crosswind.second)
    }
}
