package com.dpm.pegdown.sensor

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Reports behavior changes; the original rides have no independent bank-angle reference. */
class StationaryRideReplayTest {
    @Test fun compareQualifiedStationaryUpdatesWithOriginalPolicy() {
        val source = System.getenv("PEGDOWN_STATIONARY_RIDE")
        assumeNotNull(source)
        val file = File(source!!)
        var up: ImuVector? = null
        var initial: ImuQuaternion? = null
        var origin = 0L
        var firstWall = 0L
        var baseline: CompensatedImuLeanExperiment? = null
        var candidate: CompensatedImuLeanExperiment? = null
        var oldStationaryAccepted = 0
        var newStationaryAccepted = 0
        var unstable = 0
        var missing = 0
        var gyroRejected = 0
        var accelerationRejected = 0
        var qualifiedRejected = 0
        var maximumDifference = 0.0
        var differenceAt = 0.0
        var maximumOriginalDisplay = 0.0
        var maximumGuardedDisplay = 0.0
        val stationaryContext = ArrayDeque<String>()
        var maximumContext = emptyList<String>()
        var startupCount = 0
        var startupOriginalAbsolute = 0.0
        var startupGuardedAbsolute = 0.0
        var startupDifference = 0.0
        var absoluteDifferenceSum = 0.0
        var compared = 0
        var latestSpeed = Double.NaN
        var latestFixTime = 0L
        var warmMissing = 0
        var maxIdleRun = 0
        var idleRun = 0
        var longestBlockedRun = 0
        var blockedRun = 0
        var longestBlockedEndedAt = 0.0
        var lastGyro = 0L
        var oldDisplay = 0.0
        var newDisplay = 0.0
        val decisions = mutableMapOf<String, Int>()
        fun consume(lines: Sequence<String>) {
            for (line in lines.drop(1)) {
                val f = line.split(',')
                when (f[0]) {
                    "session" -> {
                        val g = Regex("calibration_gravity_xyz=([^;]+);([^;]+);([^;]+)").find(f[36])!!
                        up = ImuVector(g.groupValues[1].toDouble(), g.groupValues[2].toDouble(), g.groupValues[3].toDouble())
                        val q = Regex("calibration_quaternion_wxyz=([^;]+);([^;]+);([^;]+);([^;]+)").find(f[36])!!
                        initial = ImuQuaternion(q.groupValues[1].toDouble(), q.groupValues[2].toDouble(),
                            q.groupValues[3].toDouble(), q.groupValues[4].toDouble())
                    }
                    "sensor_sample" -> {
                        val time = f[2].toLong()
                        if (origin == 0L) { origin = time; firstWall = f[1].toLong() }
                        val t = (time - origin) / 1e9
                        val v = ImuVector(f[8].toDouble(), f[9].toDouble(), f[10].toDouble())
                        if (f[3] == "4") {
                            if (baseline == null) {
                                val gravity = requireNotNull(up)
                                val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(gravity)
                                baseline = CompensatedImuLeanExperiment(gravity, forward, requireNotNull(initial), time,
                                    matchGpsInterval = true, allowSlowCourseCorrections = true)
                                candidate = CompensatedImuLeanExperiment(gravity, forward, requireNotNull(initial), time,
                                    matchGpsInterval = true, allowSlowCourseCorrections = true, qualifyStationaryCorrections = true)
                                lastGyro = time
                            } else {
                                baseline!!.onGyroscope(time, v)
                                candidate!!.onGyroscope(time, v)
                                val alpha = 1 - exp(-(time - lastGyro) / 1e9 / (-1.0 / (59 * ln(.85))))
                                lastGyro = time
                                fun target(e: CompensatedImuLeanExperiment) =
                                    (if (abs(e.chassisBankDegrees) < .2) 0.0 else e.chassisBankDegrees).coerceIn(-65.0, 65.0)
                                oldDisplay += alpha * (target(baseline!!) - oldDisplay)
                                newDisplay += alpha * (target(candidate!!) - newDisplay)
                                assertTrue(newDisplay.isFinite())
                                if (t >= 20) {
                                    val difference = abs(newDisplay - oldDisplay)
                                    if (difference > maximumDifference) {
                                        maximumDifference = difference; differenceAt = t
                                        maximumOriginalDisplay = oldDisplay; maximumGuardedDisplay = newDisplay
                                        maximumContext = stationaryContext.toList()
                                    }
                                    if (t in 93.0..99.0) {
                                        startupCount++
                                        startupOriginalAbsolute += abs(oldDisplay)
                                        startupGuardedAbsolute += abs(newDisplay)
                                        startupDifference += difference
                                    }
                                    absoluteDifferenceSum += difference
                                    compared++
                                }
                            }
                        } else if (f[3] == "1") {
                            baseline?.onAccelerometer(time, v)
                            candidate?.onAccelerometer(time, v)
                        }
                    }
                    "location" -> if (baseline != null) {
                        fun n(i: Int) = f[i].toDoubleOrNull() ?: Double.NaN
                        val time = f[2].toLong()
                        val arrival = f.getOrNull(43)?.toLongOrNull() ?: origin + (f[1].toLong() - firstWall) * 1_000_000L
                        val fix = CompensatedImuLeanExperiment.Fix(time, arrival,
                            n(18), n(20), n(21), n(19), n(17), f[28] == "true", f[29] == "true", f[22])
                        baseline!!.onFix(fix)
                        candidate!!.onFix(fix)
                        if (f[22] == "gps") {
                            latestSpeed = n(18); latestFixTime = time
                            val original = baseline!!.lastDecision!!
                            val changed = candidate!!.lastDecision!!
                            decisions[changed.reason.name] = (decisions[changed.reason.name] ?: 0) + 1
                            if (latestSpeed <= .4) {
                                idleRun++; maxIdleRun = maxOf(maxIdleRun, idleRun)
                                if (original.reason == CompensatedImuLeanExperiment.DecisionReason.ACCEPTED) oldStationaryAccepted++
                                if (changed.reason == CompensatedImuLeanExperiment.DecisionReason.ACCEPTED) {
                                    newStationaryAccepted++; blockedRun = 0
                                }
                                val trace = changed.gravityTrace?.stationaryTrace
                                stationaryContext.addLast("t=${(time-origin)/1e9} old=${original.reason} new=${changed.reason} oldBank=${baseline!!.chassisBankDegrees} newBank=${candidate!!.chassisBankDegrees} vectorDelta=${trace?.maxMeanAccelerationDifferenceMps2} gyroMean=${trace?.maxMeanAngularRateRadps}")
                                while (stationaryContext.size > 12) stationaryContext.removeFirst()
                                if (changed.reason == CompensatedImuLeanExperiment.DecisionReason.STATIONARY_UNSTABLE) {
                                    unstable++; blockedRun++
                                    if (blockedRun > longestBlockedRun) {
                                        longestBlockedRun = blockedRun
                                        longestBlockedEndedAt = (time-origin)/1e9
                                    }
                                    if (trace?.historyCovered == false) {
                                        missing++; if (time-origin > 2_000_000_000L) warmMissing++
                                    }
                                    if ((trace?.maxMeanAngularRateRadps ?: 0.0) > .12) gyroRejected++
                                    if ((trace?.maxMeanAccelerationDifferenceMps2 ?: 0.0) > 1.0) accelerationRejected++
                                } else if (trace?.qualified == true && changed.reason != CompensatedImuLeanExperiment.DecisionReason.ACCEPTED) qualifiedRejected++
                            } else { idleRun = 0; blockedRun = 0 }
                        }
                    }
                }
            }
        }
        if (file.extension == "zip") ZipFile(file).use { zip ->
            val selectedEntry = System.getenv("PEGDOWN_STATIONARY_ENTRY")
            val entry = zip.entries().asSequence().single {
                it.name.endsWith(".csv") && (selectedEntry == null || it.name == selectedEntry)
            }
            println("STATIONARY_SOURCE ${entry.name}")
            zip.getInputStream(entry).bufferedReader().useLines { consume(it) }
        } else file.bufferedReader().useLines { consume(it) }
        println("STATIONARY_STARTUP window=93to99 n=$startupCount oldMeanAbsolute=${startupOriginalAbsolute/startupCount} newMeanAbsolute=${startupGuardedAbsolute/startupCount} meanDifference=${startupDifference/startupCount}")
        println("STATIONARY_MAX oldDisplay=$maximumOriginalDisplay newDisplay=$maximumGuardedDisplay at=$differenceAt")
        maximumContext.forEach { println("STATIONARY_MAX_CONTEXT $it") }
        assertTrue(compared > 100)
        assertNotNull(candidate)
        assertEquals(baseline!!.gyroGaps, candidate!!.gyroGaps)
        println("STATIONARY_AUDIT source=${file.name} compared=$compared oldAccepted=$oldStationaryAccepted newAccepted=$newStationaryAccepted unstable=$unstable missingHistory=$missing warmMissingHistory=$warmMissing gyroRejected=$gyroRejected accelerationRejected=$accelerationRejected qualifiedOtherRejection=$qualifiedRejected maxIdleFixes=$maxIdleRun longestBlockedFixes=$longestBlockedRun blockedRunEndedAt=$longestBlockedEndedAt meanDisplayDifference=${absoluteDifferenceSum/compared} maximumDisplayDifference=$maximumDifference maximumAt=$differenceAt gyroGaps=${candidate!!.gyroGaps} finalSpeed=$latestSpeed lastFixTime=$latestFixTime decisions=$decisions")
    }
}
