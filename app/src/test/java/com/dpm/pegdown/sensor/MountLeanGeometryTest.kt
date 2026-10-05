package com.dpm.pegdown.sensor

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MountLeanGeometryTest {
    private val north = ImuVector(0.0, 1.0, 0.0)
    private fun rx(deg: Double) = ImuQuaternion.axisAngle(ImuVector(1.0, 0.0, 0.0), Math.toRadians(deg))
    private fun ry(deg: Double) = ImuQuaternion.axisAngle(north, Math.toRadians(deg))
    private fun rz(deg: Double) = ImuQuaternion.axisAngle(ImuVector.UP, Math.toRadians(deg))
    private fun mounting(tilt: Double, screenRotation: Double, mountingYaw: Double = 0.0) =
        rz(mountingYaw) * rx(90.0 - tilt) * rz(screenRotation)

    @Test
    fun `vehicle axis recovers known bank with pitch heading and different mountings`() {
        for (tilt in listOf(0.0, 10.0, 21.5, 35.0)) {
            for (screen in listOf(0.0, 90.0, 180.0, 270.0)) {
                for (mountYaw in listOf(-20.0, 0.0, 20.0)) {
                    val mount = mounting(tilt, screen, mountYaw)
                    val up = mount.inverse().rotate(ImuVector.UP)
                    val geometry = MountLeanGeometry(up, mount.inverse().rotate(north))
                    for (bank in listOf(-45.0, -20.0, 0.0, 20.0, 45.0)) {
                        for (pitch in listOf(-12.0, 0.0, 12.0)) {
                            for (heading in listOf(-179.0, -89.0, 0.0, 89.0, 179.0)) {
                                val pose = rz(heading) * rx(pitch) * ry(bank) * mount
                                val currentUp = pose.inverse().rotate(ImuVector.UP)
                                assertEquals(bank, geometry.bankDegrees(currentUp), 1e-9)
                                assertEquals(pitch, geometry.pitchDegrees(currentUp), 1e-9)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `tilted centered screen changes projected gain but creates no zero bank offset`() {
        val mount = mounting(21.5, 90.0)
        val up = mount.inverse().rotate(ImuVector.UP)
        val geometry = MountLeanGeometry(up, CompensatedImuLeanExperiment.assumedForwardFromScreen(up))
        val banked = (ry(30.0) * mount).inverse().rotate(ImuVector.UP)
        val projected = geometry.projectedDegrees(banked)
        assertEquals(30.0, geometry.bankDegrees(banked), 1e-9)
        assertTrue(projected - 30.0 > 1.5)
        println("Mount synthetic 21.5deg: trueBank=30.0 projected=$projected axisBank=30.0")
        for (pitch in listOf(-12.0, -5.0, 0.0, 5.0, 12.0)) {
            val upright = (rx(pitch) * mount).inverse().rotate(ImuVector.UP)
            assertEquals(0.0, geometry.projectedDegrees(upright), 1e-9)
            assertEquals(0.0, geometry.bankDegrees(upright), 1e-9)
        }
    }

    @Test
    fun `upright calibration cannot reveal yaw mounting error and pitch then leaks into bank`() {
        val centred = mounting(21.5, 90.0)
        val skewed = mounting(21.5, 90.0, 20.0)
        val centredUp = centred.inverse().rotate(ImuVector.UP)
        val skewedUp = skewed.inverse().rotate(ImuVector.UP)
        assertEquals(centredUp.x, skewedUp.x, 1e-9)
        assertEquals(centredUp.y, skewedUp.y, 1e-9)
        assertEquals(centredUp.z, skewedUp.z, 1e-9)
        val currentUp = (rx(10.0) * skewed).inverse().rotate(ImuVector.UP)
        val knownAxis = MountLeanGeometry(skewedUp, skewed.inverse().rotate(north))
        val assumedAxis = MountLeanGeometry(skewedUp, CompensatedImuLeanExperiment.assumedForwardFromScreen(skewedUp))
        assertEquals(0.0, knownAxis.bankDegrees(currentUp), 1e-9)
        assertTrue(abs(assumedAxis.bankDegrees(currentUp)) > 3.0)
        println("Mount synthetic yaw20deg pitch10deg: knownBank=0 assumedBank=${assumedAxis.bankDegrees(currentUp)} projected=${assumedAxis.projectedDegrees(currentUp)}")
    }

    @Test
    fun `recorded calibration gives screen tilt but not mounting yaw`() {
        val references = listOf(
            ImuVector(-0.9305648836431455, -0.013920558292233265, 0.36586229019543914),
            ImuVector(-0.9275271611854533, 0.0011311997772454063, 0.3737540443263699),
        )
        references.forEachIndexed { index, up ->
            val geometry = MountLeanGeometry(up, CompensatedImuLeanExperiment.assumedForwardFromScreen(up))
            println("Mount recorded Oct0${index + 4}: screenTilt=${geometry.screenTiltDegrees}")
            assertTrue(geometry.screenTiltDegrees in 20.0..23.0)
            assertEquals(0.0, geometry.bankDegrees(up), 1e-9)
        }
    }
}
