package com.dpm.pegdown.util

import com.dpm.pegdown.model.TourLogEntry
import kotlin.math.*

object PathSmoother {
    /**
     * Douglas-Peucker Algorithmus zur Pfad-Glättung
     */
    fun smoothPath(points: List<TourLogEntry>, epsilon: Double): List<TourLogEntry> {
        if (points.size < 3) return points

        var maxDist = 0.0
        var index = 0
        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistance(points[i], points.first(), points.last())
            if (dist > maxDist) {
                index = i
                maxDist = dist
            }
        }

        return if (maxDist > epsilon) {
            val left = smoothPath(points.subList(0, index + 1), epsilon)
            val right = smoothPath(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(points.first(), points.last())
        }
    }

    private fun perpendicularDistance(p: TourLogEntry, start: TourLogEntry, end: TourLogEntry): Double {
        val x = p.lat
        val y = p.lon
        val x1 = start.lat
        val y1 = start.lon
        val x2 = end.lat
        val y2 = end.lon

        val numerator = abs((y2 - y1) * x - (x2 - x1) * y + x2 * y1 - y2 * x1)
        val denominator = sqrt((y2 - y1).pow(2) + (x2 - x1).pow(2))
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }
}
