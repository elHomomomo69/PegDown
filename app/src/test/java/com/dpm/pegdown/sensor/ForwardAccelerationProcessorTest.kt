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
        data class Sample(val t: Double, val g: Double, val oldG: Double,
            val lean: Double, val rawMinusLinearG: Double)
        data class Fix(val t: Double, val speed: Double, val course: Double,
            val speedAccuracy: Double, val bearingAccuracy: Double)
        val samples = mutableListOf<Sample>()
        val fixes = mutableListOf<Fix>()
        val stand = mutableListOf<Double>()
        val processor = ForwardAccelerationProcessor()
        var referenceUp: ImuVector? = null
        var calibration: ImuQuaternion? = null
        var current: ImuQuaternion? = null
        var rawAcceleration: ImuVector? = null
        var rawAccelerationTime = 0L
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
                        if (fields[3] == "1") {
                            rawAcceleration = ImuVector(fields[8].toDouble(), fields[9].toDouble(), fields[10].toDouble())
                            rawAccelerationTime = time
                        }
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
                            val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(referenceUp!!)
                            val rawMinusLinearG = if (rawAccelerationTime == time && rawAcceleration != null)
                                (rawAcceleration!! - vector).dot(forward) / 9.81 else Double.NaN
                            samples.add(Sample(t, value, oldValue, fields[12].toDouble(), rawMinusLinearG))
                            if (t in 40.0..60.0) {
                                stand.add(value)
                                oldStand.add(oldValue)
                            }
                        }
                    } else if (fields[0] == "location" && fields[22] == "gps" && start != null &&
                        fields[18].isNotEmpty() && fields[20].isNotEmpty()) {
                        fixes.add(Fix((fields[2].toLong() - start!!) / 1e9,
                            fields[18].toDouble(), fields[20].toDouble(),
                            fields[19].toDoubleOrNull() ?: Double.NaN,
                            fields[21].toDoubleOrNull() ?: Double.NaN))
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
        var opposite = 0
        var zero = 0
        var strongCompared = 0
        var strongAgreeing = 0
        var strongOpposite = 0
        var strongZero = 0
        val accelerationCategories = mutableMapOf<String, Int>()
        var errorSum = 0.0
        data class Comparison(val start: Double, val end: Double, val gpsG: Double, val imuG: Double)
        val comparisons = mutableListOf<Comparison>()
        var cursor = 0
        for ((a, b) in fixes.zipWithNext()) {
            val dt = b.t - a.t
            val courseDelta = ((b.course - a.course + 540.0) % 360.0) - 180.0
            if (dt !in 0.3..2.5 || a.speed < 8.0 || b.speed < 8.0 || abs(courseDelta) > 2.0) continue
            val gpsG = (b.speed - a.speed) / dt / 9.81
            if (abs(gpsG) < 0.08) continue
            // Use the native GPS interval. An EMA time constant is not a measured
            // time offset between GPS speed and IMU; examine shifts separately below.
            while (cursor < samples.size && samples[cursor].t < a.t) cursor++
            var j = cursor
            var sum = 0.0
            var oldSum = 0.0
            var n = 0
            while (j < samples.size && samples[j].t <= b.t) {
                sum += samples[j].g
                oldSum += samples[j].oldG
                n++
                j++
            }
            if (n == 0) continue
            val imuG = sum / n
            comparisons.add(Comparison(a.t, b.t, gpsG, imuG))
            compared++
            val oldG = oldSum / n
            if (abs(oldG) >= 0.01 && oldG * gpsG > 0.0) oldAgreeing++
            oldErrorSum += abs(oldG - gpsG)
            val category = when {
                abs(imuG) < 0.01 -> { zero++; "zero" }
                imuG * gpsG > 0.0 -> { agreeing++; "agree" }
                else -> { opposite++; "opposite" }
            }
            val direction = if (gpsG > 0.0) "accel" else "brake"
            accelerationCategories["$direction-$category"] = (accelerationCategories["$direction-$category"] ?: 0) + 1
            val speedSigmaG = sqrt(a.speedAccuracy * a.speedAccuracy + b.speedAccuracy * b.speedAccuracy) / dt / 9.81
            if (speedSigmaG.isFinite() && speedSigmaG <= abs(gpsG) / 2.0 &&
                a.bearingAccuracy in 0.0..2.0 && b.bearingAccuracy in 0.0..2.0) {
                strongCompared++
                when (category) {
                    "agree" -> strongAgreeing++
                    "opposite" -> strongOpposite++
                    else -> strongZero++
                }
            }
            errorSum += abs(imuG - gpsG)
        }
        assertTrue("No dynamic GPS comparison intervals", compared > 20)
        assertEquals(compared, agreeing + opposite + zero)
        fun windowMean(startTime: Double, endTime: Double): Double {
            var index = samples.binarySearch { it.t.compareTo(startTime) }
            if (index < 0) index = -index - 1
            var sum = 0.0
            var n = 0
            while (index < samples.size && samples[index].t <= endTime) {
                sum += samples[index].g
                index++
                n++
            }
            return if (n > 0) sum / n else Double.NaN
        }
        for (lag in listOf(-0.7, -0.35, 0.0, 0.35, 0.7)) {
            val values = comparisons.map { it to windowMean(it.start + lag, it.end + lag) }
            val signAgree = values.count { (c, g) -> abs(g) >= 0.01 && c.gpsG * g > 0.0 }
            val oppositeVisible = values.count { (c, g) -> abs(g) >= 0.05 && c.gpsG * g < 0.0 }
            val nearZero = values.count { (_, g) -> abs(g) < 0.01 }
            println("Acceleration timing lag=$lag agree=$signAgree/${values.size} visibleOpposite=$oppositeVisible nearZero=$nearZero meanAbsoluteDifferenceG=${values.map { (c, g) -> abs(c.gpsG - g) }.average()}")
        }
        val sustained = comparisons.windowed(3).filter { window ->
            window.zipWithNext().all { (a, b) -> abs(a.end - b.start) < 0.05 && a.gpsG * b.gpsG > 0.0 }
        }.map { window ->
            val startTime = window.first().start
            val endTime = window.last().end
            val gpsG = window.sumOf { it.gpsG * (it.end - it.start) } / (endTime - startTime)
            gpsG to windowMean(startTime, endTime)
        }
        println("Acceleration sustained three intervals count=${sustained.size} agree=${sustained.count { (gps, imu) -> abs(imu) >= 0.01 && gps * imu > 0.0 }} visibleOpposite=${sustained.count { (gps, imu) -> abs(imu) >= 0.05 && gps * imu < 0.0 }} meanAbsoluteDifferenceG=${sustained.map { (gps, imu) -> abs(gps - imu) }.average()}")
        val visibleOpposite = comparisons.filter { abs(it.imuG) >= 0.05 && it.gpsG * it.imuG < 0.0 }
        visibleOpposite.take(8).forEach { c ->
            val sourceIndex = fixes.indexOfFirst { abs(it.t - c.start) < 0.01 }
            val nearby = fixes.subList((sourceIndex - 2).coerceAtLeast(0), (sourceIndex + 5).coerceAtMost(fixes.size))
            val gpsPhases = nearby.zipWithNext().map { (a, b) ->
                (b.speed - a.speed) / (b.t - a.t) / 9.81
            }
            val source = samples.filter { it.t in c.start..c.end }
            val removed = source.map { it.rawMinusLinearG }.filter { it.isFinite() }
            println("Acceleration remaining opposite nativeInterval t=${c.start}..${c.end} gpsG=${c.gpsG} imuG=${c.imuG} nearbyGpsG=$gpsPhases maxBank=${source.maxOf { abs(it.lean) }} rawMinusLinearMeanG=${removed.average()} rawMinusLinearRangeG=${(removed.maxOrNull() ?: Double.NaN) - (removed.minOrNull() ?: Double.NaN)}")
        }
        println("Acceleration categories: compared=$compared agree=$agreeing opposite=$opposite nearZero=$zero byDirection=$accelerationCategories strongCompared=$strongCompared strongAgree=$strongAgreeing strongOpposite=$strongOpposite strongZero=$strongZero")
        println("Acceleration replay: stationaryRms=$rms oldStationaryRms=$oldRms stationaryP95=$p95 GPSIntervals=$compared signAgreement=${agreeing.toDouble() / compared} oldSignAgreement=${oldAgreeing.toDouble() / compared} meanAbsoluteDifferenceG=${errorSum / compared} oldMeanAbsoluteDifferenceG=${oldErrorSum / compared}")
    }
}
