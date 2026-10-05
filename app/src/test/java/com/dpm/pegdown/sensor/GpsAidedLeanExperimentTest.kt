package com.dpm.pegdown.sensor

import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsAidedLeanExperimentTest {
    private data class Reading(val seconds: Double, val result: GpsAidedLeanExperiment.Result)
    private data class ReplayRow(val fields: List<String>) {
        val eventSeconds = fields[1].toDouble()
        val arrivalSeconds = fields[2].toDouble()
    }

    private fun nanos(seconds: Double) = ((seconds + 10.0) * 1e9).roundToLong()

    private fun fix(
        seconds: Double,
        bearing: Double = 0.0,
        speed: Double = 15.0,
    ) = GpsAidedLeanExperiment.Fix(
        timestampNanos = nanos(seconds - 0.1),
        arrivalNanos = nanos(seconds),
        speedMps = speed,
        bearingDegrees = bearing,
        bearingAccuracyDegrees = 1.0,
        speedAccuracyMps = 0.2,
        positionAccuracyMetres = 4.0,
    )

    private fun synthetic(
        leanAt: (Double) -> Double = { 6.0 },
        courseAt: (Double) -> Double = { 0.0 },
        fixTransform: (GpsAidedLeanExperiment.Fix) -> GpsAidedLeanExperiment.Fix = { it },
        durationSeconds: Double = 20.0,
    ): List<Reading> {
        val experiment = GpsAidedLeanExperiment()
        return (0..(durationSeconds / 0.05).toInt()).map { index ->
            val seconds = index * 0.05
            if (index > 0 && index % 20 == 0) {
                experiment.onFix(fixTransform(fix(seconds, courseAt(seconds - 0.1))))
            }
            Reading(seconds, experiment.onLean(nanos(seconds), leanAt(seconds)))
        }
    }

    @Test
    fun `settled course reduces a synthetic orientation offset without snapping to zero`() {
        val readings = synthetic()
        assertEquals(6.0, readings.first().result.correctedLeanDegrees, 0.0)
        assertTrue(readings.filter { it.seconds >= 10.0 }.all {
            it.result.correctedLeanDegrees in 1.1..1.3
        })
        assertTrue(readings.zipWithNext().all { (a, b) ->
            abs(a.result.correctedLeanDegrees - b.result.correctedLeanDegrees) < 0.5
        })
    }

    @Test
    fun `stationary poor missing stale and network fixes never correct lean`() {
        val invalidFixes: List<(GpsAidedLeanExperiment.Fix) -> GpsAidedLeanExperiment.Fix> = listOf(
            { it.copy(speedMps = 0.0) },
            { it.copy(speedMps = 7.0) },
            { it.copy(hasBearing = false) },
            { it.copy(hasSpeed = false) },
            { it.copy(bearingAccuracyDegrees = 180.0) },
            { it.copy(speedAccuracyMps = Double.NaN) },
            { it.copy(positionAccuracyMetres = 100.0) },
            { it.copy(timestampNanos = it.timestampNanos - 3_000_000_000L) },
            { it.copy(provider = "network") },
        )
        invalidFixes.forEach { invalid ->
            assertTrue(synthetic(fixTransform = invalid).all {
                it.result.rawLeanDegrees == it.result.correctedLeanDegrees
            })
        }
    }

    @Test
    fun `course wraparound produces a small reference instead of a full revolution`() {
        assertEquals(0.4, GpsAidedLeanExperiment.courseDifference(359.8, 0.2), 1e-9)
        assertEquals(-0.4, GpsAidedLeanExperiment.courseDifference(0.2, 359.8), 1e-9)
        val readings = synthetic(courseAt = { (358.0 + it * 0.4) % 360.0 })
        val reference = readings.last().result.referenceDegrees!!
        assertEquals(Math.toDegrees(kotlin.math.atan(15.0 * Math.toRadians(0.4) / 9.80665)), reference, 1e-6)
    }

    @Test
    fun `settled genuine turn retains its angle`() {
        val readings = synthetic(leanAt = { 15.0 }, courseAt = { it * 10.0 % 360.0 })
        assertTrue(readings.all { it.result.correctedLeanDegrees == 15.0 })
    }

    @Test
    fun `fast lean transition disengages an already active correction`() {
        val readings = synthetic(leanAt = {
            if (it < 12.0) 6.0 else (6.0 + (it - 12.0) * 20.0).coerceAtMost(30.0)
        })
        assertTrue(readings.first { it.seconds >= 11.0 }.result.weight > 0.7)
        assertTrue(readings.filter { it.seconds >= 12.3 }.all {
            it.result.rawLeanDegrees == it.result.correctedLeanDegrees
        })
    }

    @Test
    fun `loss of fixes and invalid new fix disengage correction`() {
        val experiment = GpsAidedLeanExperiment()
        for (index in 0..200) {
            val seconds = index * 0.05
            if (index > 0 && index % 20 == 0) experiment.onFix(fix(seconds))
            experiment.onLean(nanos(seconds), 6.0)
        }
        assertTrue(experiment.onLean(nanos(10.05), 6.0).weight > 0.7)
        assertEquals(6.0, experiment.onLean(nanos(12.0), 6.0).correctedLeanDegrees, 0.0)

        val invalid = GpsAidedLeanExperiment()
        for (index in 0..200) {
            val seconds = index * 0.05
            if (index > 0 && index % 20 == 0) invalid.onFix(fix(seconds))
            invalid.onLean(nanos(seconds), 6.0)
        }
        invalid.onFix(fix(11.0).copy(bearingAccuracyDegrees = 180.0))
        assertEquals(6.0, invalid.onLean(nanos(11.01), 6.0).correctedLeanDegrees, 0.0)
    }

    @Test
    fun `real straight line bank under crosswind is falsely suppressed by GPS correction`() {
        // A steady crosswind can require chassis bank on a straight trajectory.
        // Here the synthetic attitude sensor is perfect: its 8-degree reading is true.
        val trueChassisBank = 8.0
        val readings = synthetic(leanAt = { trueChassisBank })
        val finalReading = readings.last().result
        assertEquals(trueChassisBank, finalReading.rawLeanDegrees, 0.0)
        assertTrue(abs(finalReading.correctedLeanDegrees - trueChassisBank) > 4.0)
        // This test records a known counterexample. Reduced GPS disagreement alone
        // must not be used as evidence of improved actual bank accuracy.
    }

    @Test
    fun `October ride replay measures GPS agreement and preserves recorded large banks`() {
        val cases = listOf(
            Triple("ride_20261005_left_exit", 28.0..34.0, "left exit"),
            Triple("ride_20261005_right_and_left_exit", 14.0..17.0, "right exit"),
            Triple("ride_20261005_right_and_left_exit", 26.0..34.0, "following left exit"),
            Triple("ride_20261005_fast_left_exit", 24.0..31.0, "held-out faster exit"),
        )
        val replayCache = mutableMapOf<String, List<Reading>>()
        cases.forEach { (fixture, range, label) ->
            val readings = replayCache.getOrPut(fixture) { replay(fixture) }
            val section = readings.filter { it.seconds in range }
            assertTrue(section.size >= 25)
            val baselineMagnitude = section.map { abs(it.result.rawLeanDegrees) }.average()
            val candidateMagnitude = section.map { abs(it.result.correctedLeanDegrees) }.average()
            val eligible = section.filter { it.result.referenceDegrees != null }
            val baselineGpsDisagreement = eligible.map {
                abs(it.result.rawLeanDegrees - it.result.referenceDegrees!!)
            }.average()
            val candidateGpsDisagreement = eligible.map {
                abs(it.result.correctedLeanDegrees - it.result.referenceDegrees!!)
            }.average()
            println(
                "$label: absolute angle baseline=$baselineMagnitude, candidate=$candidateMagnitude; " +
                    "GPS disagreement baseline=$baselineGpsDisagreement, candidate=$candidateGpsDisagreement; " +
                    "eligible=${eligible.size}/${section.size}"
            )
            if (label != "held-out faster exit") {
                assertTrue("Expected some qualified GPS support for $label", eligible.isNotEmpty())
                assertTrue(candidateGpsDisagreement < baselineGpsDisagreement)
            }
            assertTrue(
                readings.filter { abs(it.result.rawLeanDegrees) > 12.0 }.all {
                    it.result.rawLeanDegrees == it.result.correctedLeanDegrees
                }
            )
            assertTrue(readings.all {
                abs(it.result.rawLeanDegrees - it.result.correctedLeanDegrees) <= 4.8 + 1e-9
            })
        }
    }

    private fun replay(fixture: String): List<Reading> {
        val encoded = javaClass.getResourceAsStream(
            "/com/dpm/pegdown/sensor/$fixture.csv.gz.b64"
        )!!.bufferedReader().use { it.readText().trim() }
        val rows = GZIPInputStream(Base64.getDecoder().decode(encoded).inputStream())
            .bufferedReader().useLines { lines ->
                lines.drop(1).filter { it.isNotBlank() }.map {
                    ReplayRow(it.split(';'))
                }.toList()
            }.sortedBy { it.arrivalSeconds }
        val rotations = rows.filter { it.fields[0] == "r" }
        assertTrue(rotations.size >= 500)
        assertTrue(rotations.zipWithNext().all { (a, b) ->
            b.eventSeconds > a.eventSeconds && b.eventSeconds - a.eventSeconds < 0.2
        })
        assertTrue(rows.count { it.fields[0] == "g" } >= 50)
        val experiment = GpsAidedLeanExperiment()
        return buildList {
            rows.forEach { row ->
                val f = row.fields
                if (f[0] == "r") {
                    add(Reading(row.eventSeconds, experiment.onLean(nanos(row.eventSeconds), f[3].toDouble())))
                } else {
                    experiment.onFix(
                        GpsAidedLeanExperiment.Fix(
                            timestampNanos = nanos(row.eventSeconds),
                            arrivalNanos = nanos(row.arrivalSeconds),
                            speedMps = f[4].toDoubleOrNull() ?: Double.NaN,
                            bearingDegrees = f[5].toDoubleOrNull() ?: Double.NaN,
                            bearingAccuracyDegrees = f[6].toDoubleOrNull() ?: Double.NaN,
                            speedAccuracyMps = f[7].toDoubleOrNull() ?: Double.NaN,
                            positionAccuracyMetres = f[8].toDoubleOrNull() ?: Double.NaN,
                            hasSpeed = f[9].toBoolean(),
                            hasBearing = f[10].toBoolean(),
                            provider = f[11],
                        )
                    )
                }
            }
        }
    }
}
