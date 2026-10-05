package com.dpm.pegdown.sensor

import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTimingExperimentTest {
    private val lags = (-20..30).map { it / 10.0 }
    private fun yaw(t: Double) = 0.08 * sin(0.7 * t) + 0.035 * sin(1.1 * t)
    private fun course(t: Double) = -0.08 / 0.7 * cos(0.7 * t) - 0.035 / 1.1 * cos(1.1 * t)
    private fun synthetic(lag: Double): Pair<List<CourseTimingExperiment.Fix>, List<CourseTimingExperiment.Yaw>> {
        val fixes = (0..180).map { t ->
            CourseTimingExperiment.Fix(t.toDouble(), 18.0, (Math.toDegrees(course(t - lag)) % 360.0 + 360.0) % 360.0)
        }
        val rates = (0..1800).map { CourseTimingExperiment.Yaw(it / 10.0, yaw(it / 10.0)) }
        return fixes to rates
    }

    @Test
    fun `known positive negative and absent bearing delays are recovered`() {
        listOf(-0.7, 0.0, 1.2, 2.0).forEach { delay ->
            val (fixes, rates) = synthetic(delay)
            val scores = CourseTimingExperiment.scan(fixes, rates, lags)
            val best = scores.minBy { it.rmse }
            assertEquals(delay, best.lag, 0.1)
            assertTrue(best.correlation > 0.99)
            assertEquals(1, scores.map { it.count }.distinct().size)
            println("Course synthetic delay=$delay best=${best.lag} rmse=${best.rmse} correlation=${best.correlation}")
        }
    }

    @Test
    fun `constant rate is not presented as identifiable delay`() {
        val fixes = (0..60).map {
            CourseTimingExperiment.Fix(it.toDouble(), 18.0, (Math.toDegrees(it * 0.06) % 360.0 + 360.0) % 360.0)
        }
        val rates = (0..600).map { CourseTimingExperiment.Yaw(it / 10.0, 0.06) }
        assertTrue(CourseTimingExperiment.scan(fixes, rates, lags).isEmpty())
    }

    @Test
    fun `missing gyro coverage is excluded equally for every candidate`() {
        val (fixes, rates) = synthetic(1.2)
        val missing = rates.filter { it.time !in 70.0..75.0 }
        assertEquals(null, CourseTimingExperiment.mean(missing, 69.0, 76.0))
        assertEquals(null, CourseTimingExperiment.mean(missing, -1.0, 1.0))
        val complete = CourseTimingExperiment.scan(fixes, rates, lags)
        val reduced = CourseTimingExperiment.scan(fixes, missing, lags)
        assertTrue(reduced.first().count < complete.first().count)
        assertEquals(1, reduced.map { it.count }.distinct().size)
        assertEquals(1.2, reduced.minBy { it.rmse }.lag, 0.1)
    }

    private data class Source(
        val fixes: List<CourseTimingExperiment.Fix>,
        val rates: List<CourseTimingExperiment.Yaw>,
        val mountRates: List<CourseTimingExperiment.Yaw>,
    )

    private fun load(name: String, parts: Int): Source {
        var meta: String? = null
        val rows = (0 until parts).flatMap { part ->
            val stream = javaClass.getResourceAsStream(
                "/com/dpm/pegdown/sensor/course_timing_20261005_${name}_$part.csv.gz.b64"
            ) ?: error("Missing timing fixture")
            val encoded = stream.bufferedReader().use { it.readText().trim() }
            GZIPInputStream(Base64.getDecoder().decode(encoded).inputStream()).bufferedReader().useLines { lines ->
                val content = lines.toList()
                if (meta == null) meta = content.first() else assertEquals(meta, content.first())
                content.drop(1).filter { it.isNotBlank() }.map { it.split(';') }
            }
        }
        val gyro = rows.filter { it[0] == "y" }.sortedBy { it[1].toDouble() }
        assertEquals(parts * 600, gyro.size)
        assertTrue(gyro.all { it[4].toDouble() in 0.0..0.05 })
        val fixes = rows.filter { it[0] == "g" && it[7] == "gps" && it[8] == "true" && it[9] == "true" }
            .map { CourseTimingExperiment.Fix(
                it[1].toDouble(), it[2].toDouble(), it[3].toDouble(),
                it[4].toDouble(), it[5].toDouble(), it[6].toDouble(),
            ) }.sortedBy { it.time }
        return Source(
            fixes, gyro.map { CourseTimingExperiment.Yaw(it[1].toDouble(), it[2].toDouble()) },
            gyro.map { CourseTimingExperiment.Yaw(it[1].toDouble(), it[3].toDouble()) },
        )
    }

    @Test
    fun `long recorded sections compare lag across separate temporal halves`() {
        listOf(Triple("turns", 5, 300.0), Triple("faster", 4, 240.0)).forEach { (name, parts, duration) ->
            val source = load(name, parts)
            listOf("Android up" to source.rates, "fixed calibration up" to source.mountRates).forEach { (axis, rates) ->
                val fits = listOf(
                    "all" to source.fixes,
                    // Three-second margins keep the scanned gyro support for
                    // training and later evaluation disjoint across the split.
                    "first half" to source.fixes.filter { it.time < duration / 2.0 - 3.0 },
                    "second half" to source.fixes.filter { it.time >= duration / 2.0 + 3.0 },
                ).map { (label, fixes) ->
                    val scores = CourseTimingExperiment.scan(fixes, rates, lags)
                    if (scores.isEmpty()) {
                        println("Course recorded $name $axis $label: insufficient excited intervals")
                    } else {
                        val best = scores.minBy { it.rmse }
                        val zero = scores.single { it.lag == 0.0 }
                        println("Course recorded $name $axis $label: count=${best.count} bestLag=${best.lag} zeroRMSE=${zero.rmse} bestRMSE=${best.rmse} zeroCorrelation=${zero.correlation} bestCorrelation=${best.correlation}")
                        assertTrue(scores.all { it.rmse.isFinite() })
                        assertEquals(1, scores.map { it.count }.distinct().size)
                    }
                    scores
                }
                if (fits[1].isNotEmpty() && fits[2].isNotEmpty()) {
                    val train = fits[1].minBy { it.rmse }
                    val held = fits[2].single { it.lag == train.lag }
                    val zero = fits[2].single { it.lag == 0.0 }
                    println("Course held-out $name $axis: trainingLag=${train.lag} testRMSE=${held.rmse} zeroTestRMSE=${zero.rmse} testCorrelation=${held.correlation}")
                }
            }
        }
        // Android orientation is itself an imperfect projection reference.
        // No fitted delay is installed in the live app or the fusion prototype.
    }
}
