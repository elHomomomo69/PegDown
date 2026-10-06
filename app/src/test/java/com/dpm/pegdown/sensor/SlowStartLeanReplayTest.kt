package com.dpm.pegdown.sensor

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class SlowStartLeanReplayTest {
    @Test
    fun `native slow start replay compares guarded low speed corrections with control`() {
        val path = System.getenv("PEGDOWN_SLOW_START_ZIP")
        assumeNotNull(path)
        val file = File(path!!)
        assertTrue(file.isFile)
        var up: ImuVector? = null
        var initial: ImuQuaternion? = null
        var start = 0L
        var firstWallTime = 0L
        var lastGyro = 0L
        var control: CompensatedImuLeanExperiment? = null
        var candidate: CompensatedImuLeanExperiment? = null
        var controlDisplay = 0.0
        var candidateDisplay = 0.0
        val controlWindow = mutableListOf<Double>()
        val candidateWindow = mutableListOf<Double>()
        val laterDifference = mutableListOf<Double>()
        var slowAccepted = 0
        var maxControlReplayDifference = 0.0
        var maxControlReplayTime = 0.0
        var maxStartupControlDifference = 0.0
        val status = mutableMapOf<String, Int>()
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.endsWith(".csv") }
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                for (line in lines.drop(1)) {
                    val f = line.split(',')
                    if (f[0] == "session") {
                        val g = Regex("calibration_gravity_xyz=([^;]+);([^;]+);([^;]+)").find(f[36])!!
                        up = ImuVector(g.groupValues[1].toDouble(), g.groupValues[2].toDouble(), g.groupValues[3].toDouble())
                        val q = Regex("calibration_quaternion_wxyz=([^;]+);([^;]+);([^;]+);([^;]+)").find(f[36])!!
                        initial = ImuQuaternion(q.groupValues[1].toDouble(), q.groupValues[2].toDouble(),
                            q.groupValues[3].toDouble(), q.groupValues[4].toDouble())
                    } else if (f[0] == "sensor_sample") {
                        val time = f[2].toLong()
                        if (start == 0L) {
                            start = time
                            firstWallTime = f[1].toLong()
                        }
                        val t = (time - start) / 1e9
                        val v = ImuVector(f[8].toDouble(), f[9].toDouble(), f[10].toDouble())
                        if (f[3] == "4") {
                            if (control == null) {
                                val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(up!!)
                                control = CompensatedImuLeanExperiment(up!!, forward, initial!!, time, matchGpsInterval = true)
                                candidate = CompensatedImuLeanExperiment(up!!, forward, initial!!, time,
                                    matchGpsInterval = true, allowSlowCourseCorrections = true)
                                lastGyro = time
                            } else {
                                control!!.onGyroscope(time, v)
                                candidate!!.onGyroscope(time, v)
                                val dt = (time - lastGyro) / 1e9
                                lastGyro = time
                                val alpha = 1.0 - exp(-dt / (-1.0 / (59.0 * kotlin.math.ln(0.85))))
                                fun target(estimator: CompensatedImuLeanExperiment): Double =
                                    estimator.chassisBankDegrees.let { if (abs(it) < 0.2) 0.0 else it }.coerceIn(-65.0, 65.0)
                                controlDisplay += alpha * (target(control!!) - controlDisplay)
                                candidateDisplay += alpha * (target(candidate!!) - candidateDisplay)
                                if (t >= 10.0) {
                                    val recorded = f[40].toDoubleOrNull()
                                    if (recorded != null) {
                                        val difference = abs(controlDisplay - recorded)
                                        if (difference > maxControlReplayDifference) {
                                            maxControlReplayDifference = difference
                                            maxControlReplayTime = t
                                        }
                                        if (t in 80.0..150.0) {
                                            maxStartupControlDifference = maxOf(maxStartupControlDifference, difference)
                                        }
                                    }
                                }
                                if (t in 93.0..99.0) {
                                    controlWindow.add(controlDisplay)
                                    candidateWindow.add(candidateDisplay)
                                }
                                if (t in 180.0..2500.0) laterDifference.add(abs(candidateDisplay - controlDisplay))
                            }
                        } else if (f[3] == "1" && control != null) {
                            control!!.onAccelerometer(time, v)
                            candidate!!.onAccelerometer(time, v)
                        }
                    } else if (f[0] == "location" && control != null) {
                        fun number(i: Int) = f[i].toDoubleOrNull() ?: Double.NaN
                        val time = f[2].toLong()
                        val arrival = start + ((f[1].toLong() - firstWallTime) * 1_000_000L)
                        val fix = CompensatedImuLeanExperiment.Fix(time, arrival,
                            number(18), number(20), number(21), number(19), number(17),
                            f[28] == "true", f[29] == "true", f[22])
                        control!!.onFix(fix)
                        candidate!!.onFix(fix)
                        val reason = candidate!!.lastDecision!!.reason.name
                        val t = (time - start) / 1e9
                        if (t in 80.0..120.0) {
                            status[reason] = (status[reason] ?: 0) + 1
                            if (reason == "ACCEPTED" && fix.speedMps in 3.0..8.0) slowAccepted++
                        }
                        if (t in 90.0..150.0 && time % 3_000_000_000L < 1_000_000_000L) {
                            println("Slow replay t=$t speed=${fix.speedMps} control=$controlDisplay candidate=$candidateDisplay reason=$reason")
                        }
                    }
                }
            }
        }
        println("Control replay maximum difference=$maxControlReplayDifference at=$maxControlReplayTime startupMax=$maxStartupControlDifference")
        assertTrue("Control does not reproduce startup display: $maxStartupControlDifference",
            maxStartupControlDifference < 0.02)
        assertTrue(controlWindow.isNotEmpty())
        val oldMean = controlWindow.map { abs(it) }.average()
        val newMean = candidateWindow.map { abs(it) }.average()
        println("Slow start replay window93to99 controlAbs=$oldMean candidateAbs=$newMean slowAccepted=$slowAccepted status=$status laterMeanDifference=${laterDifference.average()}")
        assertTrue("No low-speed updates accepted", slowAccepted > 0)
        assertTrue("Startup residual not reduced: control=$oldMean candidate=$newMean", newMean < oldMean)
        assertTrue(control!!.gyroGaps == 0 && candidate!!.gyroGaps == 0)
    }
}
