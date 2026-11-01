package com.dpm.pegdown.sensor

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Read-only diagnostic replay. No magnetic corrections are applied to production. */
class MagnetometerRideAnalysisTest {
    private data class Pose(val time: Long, val q: ImuQuaternion)
    private data class Magnetic(val time: Long, val value: ImuVector, val accuracy: String, val lean: Double)
    private data class Fix(val time: Long, val speed: Double, val bearing: Double, val bearingError: Double)
    private data class Residual(val time: Double, val degrees: Double, val microtesla: Double, val magnitudeChange: Double)

    private fun angle(a: ImuVector, b: ImuVector): Double =
        Math.toDegrees(acos(a.unit().dot(b.unit()).coerceIn(-1.0, 1.0)))

    private fun poseAt(poses: List<Pose>, time: Long): ImuQuaternion? {
        val found = poses.binarySearchBy(time) { it.time }
        if (found >= 0) return poses[found].q
        val next = -found - 1
        if (next == 0 || next >= poses.size) return null
        val a = poses[next - 1]
        val b = poses[next]
        val span = b.time - a.time
        if (span <= 0L || span > 150_000_000L) return null
        return (a.q * (a.q.inverse() * b.q).fractionalRotation((time - a.time).toDouble() / span)).unit()
    }

    private fun predictedBodyField(from: ImuQuaternion, to: ImuQuaternion, field: ImuVector) =
        to.inverse().rotate(from.rotate(field))

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * fraction).toInt()]
    }

    private fun describe(label: String, values: List<Double>) {
        val mean = values.average()
        val rms = sqrt(values.map { (it - mean) * (it - mean) }.average())
        println("MAG $label n=${values.size} mean=$mean sd=$rms p05=${percentile(values, .05)} p50=${percentile(values, .5)} p95=${percentile(values, .95)} max=${values.maxOrNull()}")
    }

    private fun meanVector(values: List<ImuVector>) =
        values.fold(ImuVector.ZERO) { sum, v -> sum + v } * (1.0 / values.size)

    @Test
    fun `gyro transport predicts magnetic vector during combined roll and yaw`() {
        val worldField = ImuVector(18.0, 5.0, -44.0)
        val from = ImuQuaternion.axisAngle(ImuVector(1.0, 0.0, 0.0), .3)
        val to = ImuQuaternion.axisAngle(ImuVector.UP, 1.1) *
            ImuQuaternion.axisAngle(ImuVector(1.0, 0.0, 0.0), -.5)
        val first = from.inverse().rotate(worldField)
        val second = to.inverse().rotate(worldField)
        assertEquals(0.0, angle(predictedBodyField(from, to, first), second), 1e-5)
        assertEquals(first.length(), second.length(), 1e-9)
        // A constant magnitude alone cannot validate direction.
        val disturbed = ImuQuaternion.axisAngle(ImuVector.UP, .4).rotate(second)
        assertEquals(second.length(), disturbed.length(), 1e-9)
        assertTrue(angle(second, disturbed) > 5.0)
    }

    @Test
    fun `analyze native ride magnetic stability and local gyro consistency`() {
        val path = System.getenv("PEGDOWN_MAGNET_DIAGNOSTICS_ZIP")
        assumeNotNull(path)
        val file = File(path!!)
        assertTrue(file.isFile)
        val magnetic = mutableListOf<Magnetic>()
        val gyroPoses = mutableListOf<Pose>()
        val gamePoses = mutableListOf<Pose>()
        val fixes = mutableListOf<Fix>()
        val counts = sortedMapOf<String, Int>()
        var start = 0L
        var gyroTime = 0L
        var gyroPose = ImuQuaternion.IDENTITY
        var gaps = 0
        var gyroMaxGap = 0L
        var badMagnetic = 0
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.endsWith(".csv") }
            println("MAG archive entry=${entry.name} size=${entry.size}")
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                for (line in lines.drop(1)) {
                    val f = line.split(',')
                    if (f[0] == "sensor_info" && f[3] == "2") {
                        println("MAG sensor name=${f[4]} vendor=${f[5]} accuracyResolution=${f[25]} range=${f[26]} minDelayUs=${f[27]}")
                    }
                    if (f[0] == "sensor_sample") {
                        val time = f[2].toLong()
                        if (start == 0L) start = time
                        val v = ImuVector(f[8].toDouble(), f[9].toDouble(), f[10].toDouble())
                        when (f[3]) {
                            "4" -> {
                                if (gyroTime > 0L) {
                                    val span = time - gyroTime
                                    gyroMaxGap = maxOf(gyroMaxGap, span)
                                    if (span > 150_000_000L || span <= 0L) gaps++
                                    else if (v.length() > 1e-12)
                                        gyroPose = (gyroPose * ImuQuaternion.axisAngle(v, v.length() * span / 1e9)).unit()
                                }
                                gyroTime = time
                                gyroPoses.add(Pose(time, gyroPose))
                            }
                            "15" -> {
                                val w = f[11].toDoubleOrNull() ?: sqrt((1.0 - v.dot(v)).coerceAtLeast(0.0))
                                gamePoses.add(Pose(time, ImuQuaternion(w, v.x, v.y, v.z).unit()))
                            }
                            "2" -> {
                                if (!v.finite() || v.length() < 1e-6 ||
                                    magnetic.lastOrNull()?.let { time <= it.time } == true) badMagnetic++
                                else {
                                    magnetic.add(Magnetic(time, v, f[7], f.getOrNull(40)?.toDoubleOrNull() ?: Double.NaN))
                                    counts[f[7]] = (counts[f[7]] ?: 0) + 1
                                }
                            }
                        }
                    } else if (f[0] == "location" && f[22] == "gps" && f[28] == "true" && f[29] == "true") {
                        fixes.add(Fix(f[2].toLong(), f[18].toDouble(),
                            f[20].toDouble(), f[21].toDoubleOrNull() ?: Double.NaN))
                    }
                }
            }
        }
        assertTrue(magnetic.size > 100)
        assertEquals(0, gaps)
        println("MAG samples=${magnetic.size} accuracyCounts=$counts invalidOrDuplicate=$badMagnetic gyroSamples=${gyroPoses.size} gyroMaxGapMs=${gyroMaxGap / 1e6}")
        describe("sampleIntervalMs", magnetic.zipWithNext().map { (a, b) -> (b.time - a.time) / 1e6 })
        val windows = listOf(
            "reported_engine_off" to (0.0..20.0),
            "reported_engine_on_stationary" to (45.0..70.0),
            "early_shock" to (78.0..80.0),
            "later_shock_turn" to (85.0..88.0),
            "reported_startup_residual" to (93.0..99.0),
            "ride_excluding_start_and_end" to (150.0..2500.0),
        )
        for ((name, window) in windows) {
            val selected = magnetic.filter { (it.time - start) / 1e9 in window }
            describe("$name norm_uT", selected.map { it.value.length() })
            val worldVectors = selected.mapNotNull { m -> poseAt(gamePoses, m.time)?.rotate(m.value) }
            if (worldVectors.isNotEmpty()) {
                val centre = meanVector(worldVectors)
                describe("$name gameWorldAngleToWindowMean_deg", worldVectors.map { angle(it, centre) })
                println("MAG $name meanGameWorld=$centre")
            }
        }
        val off = magnetic.filter { (it.time - start) / 1e9 in 0.0..20.0 }
            .mapNotNull { poseAt(gamePoses, it.time)?.rotate(it.value) }
        val on = magnetic.filter { (it.time - start) / 1e9 in 45.0..70.0 }
            .mapNotNull { poseAt(gamePoses, it.time)?.rotate(it.value) }
        println("MAG enginePhaseWorldDifference_uT=${(meanVector(on) - meanVector(off)).length()} directionDifference_deg=${angle(meanVector(off), meanVector(on))}")

        fun residuals(poses: List<Pose>, seconds: Double, poseOffset: Long = 0L): List<Residual> = magnetic.mapNotNull { current ->
            val target = current.time - (seconds * 1e9).toLong()
            val index = magnetic.binarySearchBy(target) { it.time }.let { if (it >= 0) it else -it - 1 }
            if (index >= magnetic.size) return@mapNotNull null
            val previous = magnetic[index]
            if (abs(previous.time - target) > 50_000_000L) return@mapNotNull null
            val a = poseAt(poses, previous.time + poseOffset) ?: return@mapNotNull null
            val b = poseAt(poses, current.time + poseOffset) ?: return@mapNotNull null
            val predicted = predictedBodyField(a, b, previous.value)
            Residual((current.time - start) / 1e9, angle(predicted, current.value),
                (predicted - current.value).length(), abs(previous.value.length() - current.value.length()))
        }
        for (span in listOf(1.0, 5.0, 10.0)) {
            for ((source, poses) in listOf("gyro" to gyroPoses, "game" to gamePoses)) {
                val residual = residuals(poses, span)
                describe("$source span=${span}s directionResidual_deg ride150to2500",
                    residual.filter { it.time in 150.0..2500.0 }.map { it.degrees })
                if (span == 5.0) {
                    for ((name, window) in windows) {
                        val selected = residual.filter { it.time in window }
                        describe("$name ${source}5sResidual_deg", selected.map { it.degrees })
                        describe("$name ${source}5sVectorResidual_uT", selected.map { it.microtesla })
                    }
                    residual.filter { it.time in 150.0..2500.0 }.sortedByDescending { it.degrees }.take(5)
                        .forEach { println("MAG ${source}Largest5sResidual $it") }
                }
            }
        }

        val rotationDifferences = magnetic.mapNotNull { current ->
            val previousTime = current.time - 5_000_000_000L
            val ga = poseAt(gyroPoses, previousTime) ?: return@mapNotNull null
            val gb = poseAt(gyroPoses, current.time) ?: return@mapNotNull null
            val ra = poseAt(gamePoses, previousTime) ?: return@mapNotNull null
            val rb = poseAt(gamePoses, current.time) ?: return@mapNotNull null
            val error = ((ga.inverse() * gb).inverse() * (ra.inverse() * rb)).unit()
            (current.time - start) / 1e9 to Math.toDegrees(2.0 * acos(abs(error.w).coerceIn(0.0, 1.0)))
        }
        for ((name, window) in windows)
            describe("$name gyroVsGame5sRotationDifference_deg", rotationDifferences.filter { it.first in window }.map { it.second })
        for (offsetMs in listOf(-100L, -50L, 0L, 50L, 100L)) {
            val residual = residuals(gyroPoses, 5.0, offsetMs * 1_000_000L)
            for ((name, window) in listOf("mainRide" to (150.0..2500.0), "startupTurn" to (85.0..88.0)))
                describe("timingSensitivity gyro5s poseOffsetMs=$offsetMs $name", residual.filter { it.time in window }.map { it.degrees })
        }
        for (window in listOf(49.0..51.0, 645.0..647.0, 990.0..992.0)) {
            describe("localized t=$window norm_uT", magnetic.filter { (it.time - start) / 1e9 in window }.map { it.value.length() })
            describe("localized t=$window gyroVsGame5sRotationDifference_deg", rotationDifferences.filter { it.first in window }.map { it.second })
        }

        // Examine norm dependence on GPS course without assuming it is geographic north.
        // Restrict to fast motion, precise reported course and the preselected main ride.
        for (headingBin in 0..7) {
            val values = magnetic.mapNotNull { m ->
                val t = (m.time - start) / 1e9
                if (t !in 150.0..2500.0) return@mapNotNull null
                val index = fixes.binarySearchBy(m.time) { it.time }.let { if (it >= 0) it else -it - 2 }
                if (index !in fixes.indices) return@mapNotNull null
                val fix = fixes[index]
                if (m.time - fix.time > 1_500_000_000L || fix.speed < 8.0 || fix.bearingError !in 0.0..2.0)
                    return@mapNotNull null
                if ((fix.bearing / 45.0).toInt().coerceIn(0, 7) != headingBin) return@mapNotNull null
                m.value.length()
            }
            describe("GPS_courseBin${headingBin * 45}to${(headingBin + 1) * 45} norm_uT", values)
        }
        // No assertion that magnetic magnitude or heading is correct in real riding:
        // this recording has no independent orientation or field reference.
    }
}
