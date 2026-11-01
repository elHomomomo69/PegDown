package com.dpm.pegdown.sensor

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test

/** Local causal reconstruction. The recording starts mid-session; never seed from its calibration alone. */
class NovemberRideStartReplayTest {
    private fun metadata(value: String) = value.split(";").mapNotNull {
        val pair = it.trim().split("=", limit = 2)
        if (pair.size == 2) pair[0] to pair[1] else null
    }.toMap()

    @Test fun bundledNovemberReferenceReproducesTheRecordedUpdateAndRejectsIt() {
        val resource = requireNotNull(javaClass.getResourceAsStream(
            "november_20261101_stationary_reference.csv"))
        val lines = resource.bufferedReader().use { it.readLines() }
        val attributes = lines.filter { it.startsWith("# ") }.associate {
            val pair = it.removePrefix("# ").split("=", limit = 2)
            pair[0] to pair[1]
        }
        fun attribute(key: String) = attributes.getValue(key).toDouble()
        fun fix(value: String): CompensatedImuLeanExperiment.Fix {
            val f = value.split(";")
            return CompensatedImuLeanExperiment.Fix(f[0].toLong(), f[1].toLong(),
                f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(),
                f[6].toDouble(), f[7].toBoolean(), f[8].toBoolean(), f[9])
        }
        data class Sample(val type: Int, val time: Long, val value: ImuVector)
        val samples = lines.filter { it.startsWith("1,") || it.startsWith("4,") }.map {
            val f = it.split(",")
            Sample(f[0].toInt(), f[1].toLong(),
                ImuVector(f[2].toDouble(), f[3].toDouble(), f[4].toDouble()))
        }
        val g = attributes.getValue("reference_up").split(";").map { it.toDouble() }
        val up = ImuVector(g[0], g[1], g[2]).unit()
        val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(up)
        val right = forward.cross(up).unit()
        val target = fix(attributes.getValue("target_fix"))
        val bank = Math.toRadians(attribute("predicted_bank_degrees"))
        val pitch = Math.toRadians(attribute("predicted_pitch_degrees"))
        val checkpointUp = up * (cos(pitch) * cos(bank)) -
            right * (cos(pitch) * sin(bank)) + forward * sin(pitch)
        val checkpointPose = ImuQuaternion.between(checkpointUp, ImuVector.UP)
        val gyros = samples.filter { it.type == 4 }
        val firstTime = gyros.first().time
        var delta = ImuQuaternion.IDENTITY
        var previousTime = firstTime
        for (sample in gyros.drop(1)) {
            val end = minOf(sample.time, target.timestampNanos)
            val dt = (end - previousTime) / 1e9
            if (dt <= 0.0) break
            val rate = sample.value
            if (rate.length() > 1e-12)
                delta = (delta * ImuQuaternion.axisAngle(rate, rate.length() * dt)).unit()
            previousTime = sample.time
            if (sample.time >= target.timestampNanos) break
        }
        // Anchor the documented pre-update attitude, then replay this one update.
        // Earlier GPS updates are deliberately not reconstructed by this local test.
        val seed = (checkpointPose * delta.inverse()).unit()
        val control = CompensatedImuLeanExperiment(up, forward, seed, firstTime,
            matchGpsInterval = true, allowSlowCourseCorrections = true)
        val guarded = CompensatedImuLeanExperiment(up, forward, seed, firstTime,
            matchGpsInterval = true, allowSlowCourseCorrections = true,
            qualifyStationaryCorrections = true, correctStraightRideDrift = true)
        val engines = listOf(control, guarded)
        lines.filter { it.startsWith("# preceding_fix=") }.forEach { line ->
            val previous = fix(line.substringAfter("="))
            engines.forEach { it.onFix(previous) }
        }
        samples.forEach { sample -> engines.forEach { e ->
            if (sample.type == 4) e.onGyroscope(sample.time, sample.value)
            else e.onAccelerometer(sample.time, sample.value)
        } }
        engines.forEach {
            assertEquals(attribute("expected_bank_before"), it.chassisBankDegrees, .00001)
        }
        val guardedBefore = guarded.chassisBankDegrees
        engines.forEach { it.onFix(target) }
        val old = control.lastDecision!!
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.ACCEPTED, old.reason)
        assertEquals(attribute("expected_bank_after"), control.chassisBankDegrees, .00001)
        val originalGravity = requireNotNull(old.gravityTrace)
        assertEquals(attribute("expected_applied_rotation"), originalGravity.appliedRotationDegrees!!, .00001)
        val rejected = guarded.lastDecision!!
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_UNSTABLE, rejected.reason)
        assertTrue(rejected.stationarySpeedTrace!!.qualified)
        val stationary = requireNotNull(rejected.gravityTrace?.stationaryTrace)
        assertTrue(stationary.historyCovered)
        assertTrue(stationary.maxMeanAngularRateRadps!! > .12)
        assertEquals(guardedBefore, guarded.chassisBankDegrees, 0.0)
        engines.forEach { assertEquals(0, it.gyroGaps) }
        println("NOVEMBER_BUNDLED samples=${samples.size} originalRotation=${originalGravity.appliedRotationDegrees} guarded=${rejected.reason}")
    }

    @Test fun reconstructStationaryCheckpointAndWithholdOneStartupCorrection() {
        val path = System.getenv("PEGDOWN_NOVEMBER_DIAGNOSTICS_ZIP")
        assumeNotNull(path)
        val rows = mutableListOf<List<String>>()
        var origin = 0L
        ZipFile(File(path!!)).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.endsWith(".csv") }
            assumeTrue(entry.name.contains("20261101"))
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                for (line in lines.drop(1)) {
                    val f = line.split(',')
                    if (f[0] == "sensor_sample") {
                        if (origin == 0L) origin = f[2].toLong()
                        if ((f[2].toLong() - origin) / 1e9 > 100.0) break
                    }
                    rows.add(f)
                }
            }
        }
        val session = rows.first { it[0] == "session" }[36]
        val gravity = Regex("calibration_gravity_xyz=([^;]+);([^;]+);([^;]+)").find(session)!!
        val up = ImuVector(gravity.groupValues[1].toDouble(),
            gravity.groupValues[2].toDouble(), gravity.groupValues[3].toDouble()).unit()
        val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(up)
        val right = forward.cross(up).unit()
        fun relative(time: Long) = (time - origin) / 1e9
        val corrections = rows.filter { it[0] == "lean_correction" }
        val checkpoint = corrections.single { relative(it[2].toLong()) in 49.8..49.9 }
        val suspect = corrections.single { relative(it[2].toLong()) in 55.8..55.9 }
        val checkpointTime = checkpoint[2].toLong()
        val checkpointTrace = metadata(checkpoint[36])
        val bank = Math.toRadians(checkpointTrace.getValue("predicted_bank_degrees").toDouble())
        val pitch = Math.toRadians(checkpointTrace.getValue("predicted_pitch_degrees").toDouble())
        val checkpointUp = up * (cos(pitch) * cos(bank)) -
            right * (cos(pitch) * sin(bank)) + forward * sin(pitch)
        val checkpointPose = ImuQuaternion.between(checkpointUp, ImuVector.UP)
        val gyros = rows.filter { it[0] == "sensor_sample" && it[3] == "4" &&
            it[2].toLong() >= checkpointTime - 300_000_000L }
        val firstGyro = gyros.first()[2].toLong()
        fun vector(f: List<String>) = ImuVector(f[8].toDouble(), f[9].toDouble(), f[10].toDouble())
        var delta = ImuQuaternion.IDENTITY
        var previousGyro = firstGyro
        for (f in gyros.drop(1)) {
            val time = f[2].toLong()
            val span = minOf(time, checkpointTime) - previousGyro
            if (span <= 0L) break
            val rate = vector(f)
            if (rate.length() > 1e-12)
                delta = (delta * ImuQuaternion.axisAngle(rate, rate.length() * span / 1e9)).unit()
            previousGyro = time
            if (time >= checkpointTime) break
        }
        val seed = (checkpointPose * delta.inverse()).unit()
        val variants = linkedMapOf(
            "recorded_policy" to CompensatedImuLeanExperiment(up, forward, seed, firstGyro,
                matchGpsInterval = true, allowSlowCourseCorrections = true),
            "qualified_stationary" to CompensatedImuLeanExperiment(up, forward, seed, firstGyro,
                matchGpsInterval = true, allowSlowCourseCorrections = true, qualifyStationaryCorrections = true),
            "omit_55s_only" to CompensatedImuLeanExperiment(up, forward, seed, firstGyro,
                matchGpsInterval = true, allowSlowCourseCorrections = true,
                withheldReferenceTimes = setOf(suspect[2].toLong())),
        )
        fun fix(f: List<String>): CompensatedImuLeanExperiment.Fix {
            fun n(i: Int) = f[i].toDoubleOrNull() ?: Double.NaN
            return CompensatedImuLeanExperiment.Fix(f[2].toLong(), f[43].toLong(),
                n(18), n(20), n(21), n(19), n(17), f[28] == "true", f[29] == "true", f[22])
        }
        val precedingFix = rows.last { it[0] == "location" && it[22] == "gps" &&
            it[2].toLong() < firstGyro }
        variants.values.forEach { it.onFix(fix(precedingFix)) }
        var beforeError = 0.0
        var afterError = 0.0
        var comparisons = 0
        var guardedMatchesOmissionMax = 0.0
        var suspectRejected = false
        var second = -1
        var pending: List<String>? = null
        val windows = listOf(55.0..56.0, 58.0..60.0, 60.0..62.0, 62.0..65.0, 65.0..70.0)
        val differences = windows.map { mutableListOf<Double>() }
        for (f in rows) {
            when (f[0]) {
                "sensor_sample" -> {
                    val time = f[2].toLong()
                    if (time < firstGyro) continue
                    when (f[3]) {
                        "4" -> {
                            variants.values.forEach { it.onGyroscope(time, vector(f)) }
                            val t = relative(time)
                            val baseline = variants.getValue("recorded_policy").chassisBankDegrees
                            val omitted = variants.getValue("omit_55s_only").chassisBankDegrees
                            windows.forEachIndexed { i, w -> if (t in w) differences[i].add(omitted - baseline) }
                            if (t in 60.0..61.8)
                                guardedMatchesOmissionMax = maxOf(guardedMatchesOmissionMax,
                                    abs(variants.getValue("qualified_stationary").chassisBankDegrees - omitted))
                            if (t in 53.0..70.0 && floor(t).toInt() != second) {
                                second = floor(t).toInt()
                                println("NOV_START t=$t recordedDisplay=${f[40]} replayBank=$baseline omittedBank=$omitted qualifiedBank=${variants.getValue("qualified_stationary").chassisBankDegrees} delta=${omitted-baseline}")
                            }
                        }
                        "1" -> variants.values.forEach { it.onAccelerometer(time, vector(f)) }
                    }
                }
                "lean_correction" -> if (f[43].toLong() >= firstGyro && f[2].toLong() >= checkpointTime) pending = f
                "location" -> {
                    if (f[43].toLong() < firstGyro || f[2].toLong() < checkpointTime) continue
                    val expected = pending
                    val baseline = variants.getValue("recorded_policy")
                    if (expected != null) {
                        beforeError = maxOf(beforeError, abs(baseline.chassisBankDegrees -
                            metadata(expected[36]).getValue("bank_before_degrees").toDouble()))
                    }
                    variants.values.forEach { it.onFix(fix(f)) }
                    if (f[2] == suspect[2]) {
                        val guarded = variants.getValue("qualified_stationary").lastDecision!!
                        assertEquals(CompensatedImuLeanExperiment.DecisionReason.STATIONARY_UNSTABLE, guarded.reason)
                        assertTrue(guarded.gravityTrace!!.stationaryTrace!!.historyCovered)
                        suspectRejected = true
                    }
                    if (expected != null) {
                        afterError = maxOf(afterError, abs(baseline.chassisBankDegrees -
                            metadata(expected[36]).getValue("bank_after_degrees").toDouble()))
                        comparisons++
                        if (relative(f[2].toLong()) in 53.0..65.0) {
                            val d = baseline.lastDecision!!
                            val g = d.gravityTrace
                            println("NOV_FIX t=${relative(f[2].toLong())} reason=${d.reason} peak=${g?.rawAccelerationPeakMps2} rms=${g?.rawAccelerationRmsDeviationMps2} sampleCount=${g?.accelerationSampleCount} rotation=${g?.appliedRotationDegrees} inferredPitch=${g?.inferredPitch} predictedPitch=${g?.predictedPitch} qualifiedReason=${variants.getValue("qualified_stationary").lastDecision?.reason} stationary=${variants.getValue("qualified_stationary").lastDecision?.gravityTrace?.stationaryTrace}")
                        }
                    }
                    pending = null
                }
            }
        }
        println("NOV_RECONSTRUCTION comparisons=$comparisons bankBeforeMaxError=$beforeError bankAfterMaxError=$afterError")
        assertTrue(comparisons > 30)
        assertTrue("Cannot attribute a correction until the original is reconstructed: $beforeError", beforeError < .01)
        assertTrue(afterError < .01)
        windows.forEachIndexed { i, w ->
            println("NOV_ABLATION window=$w n=${differences[i].size} deltaMean=${differences[i].average()} deltaMin=${differences[i].minOrNull()} deltaMax=${differences[i].maxOrNull()}")
        }
        variants.values.forEach { assertEquals(0, it.gyroGaps) }
        val afterTurnInfluence = differences[2]
        assertTrue(afterTurnInfluence.isNotEmpty())
        assertTrue(afterTurnInfluence.max() in 5.0..5.5)
        assertTrue(suspectRejected)
        assertTrue("Guard no longer matches the causal omission: $guardedMatchesOmissionMax", guardedMatchesOmissionMax < .02)
        // This later recorded restart is not automatically the user's hill maneuver.
        // Initial hill attribution is unavailable: recording begins while already moving.
        // This tests causal influence on this record, not absolute bank accuracy.
    }
}
