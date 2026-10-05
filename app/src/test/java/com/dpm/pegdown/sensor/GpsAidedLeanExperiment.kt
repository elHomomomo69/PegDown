package com.dpm.pegdown.sensor

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp

/**
 * Offline experiment, deliberately confined to test sources.
 *
 * v * courseRate / g estimates the equilibrium bank of the vehicle's centre of mass.
 * It is not a measurement of chassis bank: rider movement, crosswind and road
 * geometry can make them differ. GPS straightness cannot prove that actual bank is zero.
 */
internal class GpsAidedLeanExperiment {
    data class Fix(
        val timestampNanos: Long,
        val arrivalNanos: Long,
        val speedMps: Double,
        val bearingDegrees: Double,
        val bearingAccuracyDegrees: Double,
        val speedAccuracyMps: Double,
        val positionAccuracyMetres: Double,
        val hasSpeed: Boolean = true,
        val hasBearing: Boolean = true,
        val provider: String = "gps",
    )

    data class Result(
        val rawLeanDegrees: Double,
        val correctedLeanDegrees: Double,
        val referenceDegrees: Double?,
        val weight: Double,
    )

    private data class LeanSample(val timestampNanos: Long, val angle: Double)

    private val fixes = ArrayDeque<Fix>()
    private val leanHistory = ArrayDeque<LeanSample>()
    private var referenceDegrees: Double? = null
    private var referenceTimestampNanos = 0L
    private var lastLeanTimestampNanos = 0L
    private var weight = 0.0

    fun onFix(fix: Fix) {
        // Network fixes must not reset an otherwise valid GNSS sequence.
        if (fix.provider != "gps") return
        if (fixes.lastOrNull()?.let { fix.timestampNanos <= it.timestampNanos } == true) return
        val ageSeconds = (fix.arrivalNanos - fix.timestampNanos) / 1e9
        val valid = fix.timestampNanos > 0L &&
            ageSeconds in 0.0..2.0 &&
            fix.hasSpeed && fix.hasBearing &&
            fix.speedMps.isFinite() && fix.speedMps >= 8.0 &&
            fix.bearingDegrees.isFinite() && fix.bearingDegrees in 0.0..360.0 &&
            fix.bearingAccuracyDegrees.isFinite() && fix.bearingAccuracyDegrees in 0.0..5.0 &&
            fix.speedAccuracyMps.isFinite() && fix.speedAccuracyMps in 0.0..1.0 &&
            fix.positionAccuracyMetres.isFinite() && fix.positionAccuracyMetres in 0.0..15.0
        if (!valid) {
            fixes.clear()
            referenceDegrees = null
            return
        }

        val previous = fixes.lastOrNull()
        if (previous != null) {
            val dt = (fix.timestampNanos - previous.timestampNanos) / 1e9
            if (dt !in 0.3..2.0 ||
                abs(courseDifference(previous.bearingDegrees, fix.bearingDegrees)) / dt > 45.0
            ) {
                fixes.clear()
                referenceDegrees = null
            }
        }
        fixes.addLast(fix)
        while (fixes.size > 3) fixes.removeFirst()
        referenceDegrees = null
        if (fixes.size < 3) return

        val samples = fixes.toList()
        val rates = samples.zipWithNext { before, after ->
            courseDifference(before.bearingDegrees, after.bearingDegrees) /
                ((after.timestampNanos - before.timestampNanos) / 1e9)
        }
        val accelerations = samples.zipWithNext { before, after ->
            (after.speedMps - before.speedMps) /
                ((after.timestampNanos - before.timestampNanos) / 1e9)
        }
        // This first experiment corrects only settled course sections, not turn entry.
        if (rates.any { abs(it) > 1.5 } || accelerations.any { abs(it) > 2.0 }) return

        val courseRateRadians = Math.toRadians(rates.average())
        val equilibriumBank = Math.toDegrees(atan(samples.map { it.speedMps }.average() * courseRateRadians / 9.80665))
        if (abs(equilibriumBank) > 3.0) return
        referenceDegrees = equilibriumBank
        referenceTimestampNanos = fix.timestampNanos
    }

    fun onLean(timestampNanos: Long, rawLeanDegrees: Double): Result {
        require(rawLeanDegrees.isFinite())
        if (timestampNanos <= lastLeanTimestampNanos) {
            return Result(rawLeanDegrees, rawLeanDegrees, null, 0.0)
        }
        val dt = if (lastLeanTimestampNanos == 0L) 0.0 else
            (timestampNanos - lastLeanTimestampNanos) / 1e9
        lastLeanTimestampNanos = timestampNanos
        leanHistory.addLast(LeanSample(timestampNanos, rawLeanDegrees))
        while (leanHistory.isNotEmpty() &&
            timestampNanos - leanHistory.first().timestampNanos > 1_000_000_000L
        ) leanHistory.removeFirst()

        val ref = referenceDegrees
        val ageSeconds = (timestampNanos - referenceTimestampNanos) / 1e9
        val historyDuration = (timestampNanos - leanHistory.first().timestampNanos) / 1e9
        val settledLean = historyDuration >= 0.8 &&
            leanHistory.maxOf { it.angle } - leanHistory.minOf { it.angle } <= 5.0
        val eligible = ref != null && ageSeconds in 0.0..1.5 &&
            abs(rawLeanDegrees) <= 12.0 && settledLean && dt <= 0.25
        weight = if (eligible) weight + (0.8 - weight) * (1.0 - exp(-dt / 0.8)) else 0.0
        val correction = if (eligible) (ref!! - rawLeanDegrees).coerceIn(-6.0, 6.0) * weight else 0.0
        return Result(rawLeanDegrees, rawLeanDegrees + correction, if (eligible) ref else null, weight)
    }

    companion object {
        fun courseDifference(before: Double, after: Double): Double =
            ((after - before + 540.0) % 360.0) - 180.0
    }
}
