package com.dpm.pegdown.sensor

import kotlin.math.abs
import kotlin.math.sqrt

/** Offline timing comparison, not an estimator of true chassis bank. */
internal object CourseTimingExperiment {
    data class Yaw(val time: Double, val rate: Double)
    data class Fix(
        val time: Double, val speed: Double, val bearing: Double,
        val bearingAccuracy: Double = 0.3, val speedAccuracy: Double = 0.2,
        val positionAccuracy: Double = 3.0,
    ) {
        fun usable() = time.isFinite() && speed.isFinite() && speed in 8.0..80.0 &&
            bearing.isFinite() && bearing in 0.0..360.0 &&
            bearingAccuracy.isFinite() && bearingAccuracy in 0.0..5.0 &&
            speedAccuracy.isFinite() && speedAccuracy in 0.0..1.0 &&
            positionAccuracy.isFinite() && positionAccuracy in 0.0..15.0
    }
    data class Score(val lag: Double, val rmse: Double, val correlation: Double, val count: Int)
    private data class Interval(val start: Double, val end: Double, val rate: Double)

    /** Positive lag compares GPS with older gyro samples, indicating GPS following gyro. */
    fun scan(fixes: List<Fix>, yaw: List<Yaw>, lags: List<Double>): List<Score> {
        require(lags.isNotEmpty() && lags.all { it.isFinite() })
        require(fixes.zipWithNext().all { (a, b) -> b.time > a.time })
        require(yaw.zipWithNext().all { (a, b) -> b.time > a.time })
        val intervals = fixes.zipWithNext().mapNotNull { (a, b) ->
            val dt = b.time - a.time
            if (!a.usable() || !b.usable() || dt !in 0.3..2.5) return@mapNotNull null
            val rate = Math.toRadians(GpsAidedLeanExperiment.courseDifference(a.bearing, b.bearing)) / dt
            if (abs(rate) !in 0.025..0.8) return@mapNotNull null
            Interval(a.time, b.time, rate)
        }
        // Every candidate uses exactly the same GPS intervals, including gap checks.
        val common = intervals.mapNotNull { interval ->
            val means = lags.map { lag -> mean(yaw, interval.start - lag, interval.end - lag) }
            if (means.any { it == null }) null else interval to means.map { it!! }
        }
        if (common.size < 12) return emptyList()
        val gpsMean = common.map { it.first.rate }.average()
        val gpsVariance = common.sumOf { (it.first.rate - gpsMean) * (it.first.rate - gpsMean) }
        // Constant-rate turns cannot identify a delay. This is an analysis eligibility
        // rule, not an accuracy bound or a live correction threshold.
        if (sqrt(gpsVariance / common.size) < 0.015) return emptyList()
        return lags.mapIndexed { index, lag ->
            val values = common.map { it.second[index] }
            val gyroMean = values.average()
            val gyroVariance = values.sumOf { (it - gyroMean) * (it - gyroMean) }
            val covariance = common.indices.sumOf {
                (common[it].first.rate - gpsMean) * (values[it] - gyroMean)
            }
            val correlation = if (gyroVariance > 1e-12) covariance / sqrt(gpsVariance * gyroVariance) else Double.NaN
            val mse = common.indices.sumOf {
                val error = common[it].first.rate - values[it]
                error * error
            } / common.size
            Score(lag, sqrt(mse), correlation, common.size)
        }
    }

    /** Bracketed time-weighted integration. Gaps above 250 ms are ineligible. */
    fun mean(yaw: List<Yaw>, start: Double, end: Double): Double? {
        if (yaw.size < 2 || end <= start || start < yaw.first().time || end > yaw.last().time) return null
        val found = yaw.binarySearchBy(start) { it.time }
        var index = if (found >= 0) found else -found - 2
        if (index < 0) return null
        var integral = 0.0
        var covered = 0.0
        while (index + 1 < yaw.size && yaw[index].time < end) {
            val a = yaw[index]
            val b = yaw[index + 1]
            val span = b.time - a.time
            val lo = maxOf(start, a.time)
            val hi = minOf(end, b.time)
            if (hi > lo) {
                if (span !in 0.0..0.25 || !a.rate.isFinite() || !b.rate.isFinite()) return null
                val left = a.rate + (b.rate - a.rate) * (lo - a.time) / span
                val right = a.rate + (b.rate - a.rate) * (hi - a.time) / span
                integral += (left + right) * (hi - lo) / 2.0
                covered += hi - lo
            }
            index++
        }
        if (abs(covered - (end - start)) > 1e-6) return null
        return integral / (end - start)
    }
}
