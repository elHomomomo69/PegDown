package com.dpm.pegdown.sensor

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class ForwardAccelerationProcessorTest {
    private val up = ImuVector(-0.9257105869870015, -0.00988543351627083, 0.37810340826865907).unit()
    private val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(up)

    @Test
    fun `calibrated projection rejects vertical vibration with tilted landscape phone`() {
        val processor = ForwardAccelerationProcessor()
        repeat(1000) { i ->
            val result = processor.update((i + 1) * 20_000_000L, up * (8.0 * sin(i.toDouble())), up)
            assertEquals(0.0, result!!, 1e-9)
        }
    }

    @Test
    fun `signed filtering cancels alternating vibration before separating signs`() {
        val processor = ForwardAccelerationProcessor()
        val values = (0..1000).map { i ->
            processor.update((i + 1) * 20_000_000L, forward * (if (i % 2 == 0) 4.0 else -4.0), up)!!
        }
        assertTrue(values.drop(100).all { it == 0.0 })
    }

    @Test
    fun `steady acceleration braking and reversal keep magnitude and sign`() {
        val processor = ForwardAccelerationProcessor()
        var time = 0L
        var value = 0.0
        for (g in listOf(0.3, -0.4, 0.0)) {
            repeat(150) {
                time += 20_000_000L
                value = processor.update(time, forward * (g * 9.81), up)!!
            }
            assertEquals(g, value, 0.001)
        }
    }

    @Test
    fun `sampling rate changes preserve the same filter time constant`() {
        fun response(step: Long): Double {
            val processor = ForwardAccelerationProcessor()
            processor.update(1L, ImuVector.ZERO, up)
            var result = 0.0
            var time = step
            while (time <= 1_000_000_000L) {
                result = processor.update(time + 1L, forward * 2.943, up)!!
                time += step
            }
            return result
        }
        assertEquals(response(10_000_000L), response(20_000_000L), 1e-9)
    }

    @Test
    fun `invalid samples and stale timestamps do not corrupt filter and gaps reset it`() {
        val processor = ForwardAccelerationProcessor()
        assertNull(processor.update(1L, ImuVector(Double.NaN, 0.0, 0.0), up))
        assertNull(processor.update(1L, ImuVector.ZERO, ImuVector.ZERO))
        assertNull(processor.update(1L, ImuVector.ZERO, ImuVector.UP))
        assertEquals(0.0, processor.update(1L, forward * 3.0, up)!!, 0.0)
        assertNull(processor.update(1L, forward * 3.0, up))
        assertEquals(0.0, processor.update(500_000_001L, forward * 3.0, up)!!, 0.0)
        processor.reset()
        assertEquals(0.0, processor.update(1L, forward * 3.0, up)!!, 0.0)
    }

    @Test
    fun `new ride replay reduces stationary engine vibration and compares GPS acceleration`() {
        val path = System.getenv("PEGDOWN_ACCEL_DIAGNOSTICS_ZIP")
        assumeNotNull(path)
        val file = File(path!!)
        assertTrue("Configured archive does not exist", file.isFile)
        data class Sample(val t: Double, val g: Double, val oldG: Double)
        data class Fix(val t: Double, val speed: Double, val course: Double)
        val samples = mutableListOf<Sample>()
        val fixes = mutableListOf<Fix>()
        val stand = mutableListOf<Double>()
        val processor = ForwardAccelerationProcessor()
        var referenceUp: ImuVector? = null
        var calibration: ImuQuaternion? = null
        var current: ImuQuaternion? = null
        var oldPositive = 0.0
        var oldNegative = 0.0
        val oldStand = mutableListOf<Double>()
        var start: Long? = null
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.endsWith(".csv") }
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val fields = line.split(',')
                    if (fields[0] == "session") {
                        val gravity = Regex("calibration_gravity_xyz=([^;]+);([^;]+);([^;]+)")
                            .find(fields[36]) ?: error("Missing calibration gravity")
                        referenceUp = ImuVector(gravity.groupValues[1].toDouble(),
                            gravity.groupValues[2].toDouble(), gravity.groupValues[3].toDouble())
                        val quaternion = Regex("calibration_quaternion_wxyz=([^;]+);([^;]+);([^;]+);([^;]+)")
                            .find(fields[36]) ?: error("Missing calibration quaternion")
                        calibration = ImuQuaternion(quaternion.groupValues[1].toDouble(),
                            quaternion.groupValues[2].toDouble(), quaternion.groupValues[3].toDouble(),
                            quaternion.groupValues[4].toDouble()).unit()
                        current = calibration
                        assertTrue(fields[36].contains("experimental_full_interval"))
                    } else if (fields[0] == "sensor_sample") {
                        val time = fields[2].toLong()
                        if (start == null) start = time
                        if (fields[3] == "15") {
                            current = ImuQuaternion(fields[11].toDouble(), fields[8].toDouble(),
                                fields[9].toDouble(), fields[10].toDouble()).unit()
                        }
                        if (fields[3] == "10") {
                            val t = (time - start!!) / 1e9
                            val value = processor.update(time, ImuVector(fields[8].toDouble(),
                                fields[9].toDouble(), fields[10].toDouble()), referenceUp!!) ?: return@forEach
                            val vector = ImuVector(fields[8].toDouble(), fields[9].toDouble(), fields[10].toDouble())
                            val relative = (calibration!!.inverse() * current!!).unit()
                            var oldInput = (-relative.rotate(vector).z / 9.81).coerceIn(-2.0, 2.0)
                            if (abs(oldInput) < 0.05) oldInput = 0.0
                            if (oldInput >= 0.0) {
                                oldPositive += 0.15 * (oldInput - oldPositive)
                                oldNegative = 0.0
                            } else {
                                oldNegative += 0.15 * (oldInput - oldNegative)
                                oldPositive = 0.0
                            }
                            val oldValue = oldPositive + oldNegative
                            samples.add(Sample(t, value, oldValue))
                            if (t in 40.0..60.0) {
                                stand.add(value)
                                oldStand.add(oldValue)
                            }
                        }
                    } else if (fields[0] == "location" && fields[22] == "gps" && start != null &&
                        fields[18].isNotEmpty() && fields[20].isNotEmpty()) {
                        fixes.add(Fix((fields[2].toLong() - start!!) / 1e9,
                            fields[18].toDouble(), fields[20].toDouble()))
                    }
                }
            }
        }
        assertTrue(stand.size > 500)
        val rms = sqrt(stand.sumOf { it * it } / stand.size)
        val p95 = stand.map { abs(it) }.sorted()[(stand.size * 0.95).toInt()]
        assertTrue("Stationary filtered RMS=$rms", rms < 0.05)
        assertTrue("Stationary filtered p95=$p95", p95 < 0.10)
        val oldRms = sqrt(oldStand.sumOf { it * it } / oldStand.size)
        assertTrue("Stationary vibration should decrease: new=$rms old=$oldRms", rms < oldRms * 0.5)
        var oldAgreeing = 0
        var oldErrorSum = 0.0
        var compared = 0
        var agreeing = 0
        var errorSum = 0.0
        var cursor = 0
        for ((a, b) in fixes.zipWithNext()) {
            val dt = b.t - a.t
            val courseDelta = ((b.course - a.course + 540.0) % 360.0) - 180.0
            if (dt !in 0.3..2.5 || a.speed < 8.0 || b.speed < 8.0 || abs(courseDelta) > 2.0) continue
            val gpsG = (b.speed - a.speed) / dt / 9.81
            if (abs(gpsG) < 0.08) continue
            // A causal low pass delays the signal: account for its nominal 0.35s lag.
            while (cursor < samples.size && samples[cursor].t < a.t + 0.35) cursor++
            var j = cursor
            var sum = 0.0
            var oldSum = 0.0
            var n = 0
            while (j < samples.size && samples[j].t <= b.t + 0.35) {
                sum += samples[j].g
                oldSum += samples[j].oldG
                n++
                j++
            }
            if (n == 0) continue
            val imuG = sum / n
            compared++
            val oldG = oldSum / n
            if (oldG * gpsG > 0.0) oldAgreeing++
            oldErrorSum += abs(oldG - gpsG)
            if (imuG * gpsG > 0.0) agreeing++
            errorSum += abs(imuG - gpsG)
        }
        assertTrue("No dynamic GPS comparison intervals", compared > 20)
        println("Acceleration replay: stationaryRms=$rms oldStationaryRms=$oldRms stationaryP95=$p95 GPSIntervals=$compared signAgreement=${agreeing.toDouble() / compared} oldSignAgreement=${oldAgreeing.toDouble() / compared} meanAbsoluteDifferenceG=${errorSum / compared} oldMeanAbsoluteDifferenceG=${oldErrorSum / compared}")
    }
}
