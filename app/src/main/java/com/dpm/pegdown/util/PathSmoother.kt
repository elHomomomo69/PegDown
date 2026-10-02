package com.dpm.pegdown.util

import com.dpm.pegdown.model.TourLogEntry
import kotlin.math.*

object PathSmoother {
    /**
     * Entfernt redundante GPS-Punkte im Metermaßstab. Endpunkte, relevante
     * Sensorereignisse und Punkte an längeren Zeitlücken bleiben erhalten.
     */
    fun simplifyTrack(
        points: List<TourLogEntry>,
        toleranceMeters: Double = 5.0,
        maxGapMillis: Long = 15_000L
    ): List<TourLogEntry> {
        if (points.size < 3) return points

        val anchors = mutableListOf(0)
        for (index in 1 until points.lastIndex) {
            val point = points[index]
            val previous = points[index - 1]
            if (hasRelevantMeasurement(point) ||
                timestampMillis(point) - timestampMillis(previous) > maxGapMillis
            ) {
                anchors.add(index)
            }
        }
        anchors.add(points.lastIndex)

        val keep = BooleanArray(points.size)
        anchors.forEach { keep[it] = true }

        for (anchorIndex in 0 until anchors.lastIndex) {
            simplifyRange(
                points = points,
                start = anchors[anchorIndex],
                end = anchors[anchorIndex + 1],
                toleranceMeters = toleranceMeters,
                keep = keep
            )
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun hasRelevantMeasurement(point: TourLogEntry): Boolean =
        point.leanAngleLeft >= 1.0 ||
            point.leanAngleRight >= 1.0 ||
            abs(point.acceleration) >= 0.1 ||
            abs(point.braking) >= 0.1

    private fun simplifyRange(
        points: List<TourLogEntry>,
        start: Int,
        end: Int,
        toleranceMeters: Double,
        keep: BooleanArray
    ) {
        if (end - start < 2) return
        val ranges = ArrayDeque<Pair<Int, Int>>()
        ranges.addLast(start to end)

        while (ranges.isNotEmpty()) {
            val (rangeStart, rangeEnd) = ranges.removeLast()
            var farthestIndex = -1
            var farthestDistance = toleranceMeters

            for (index in rangeStart + 1 until rangeEnd) {
                val distance = perpendicularDistanceMeters(
                    points[index],
                    points[rangeStart],
                    points[rangeEnd]
                )
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthestIndex = index
                }
            }

            if (farthestIndex >= 0) {
                keep[farthestIndex] = true
                ranges.addLast(rangeStart to farthestIndex)
                ranges.addLast(farthestIndex to rangeEnd)
            }
        }
    }

    private fun perpendicularDistanceMeters(
        point: TourLogEntry,
        start: TourLogEntry,
        end: TourLogEntry
    ): Double {
        val meanLatitude = Math.toRadians((start.lat + end.lat) / 2.0)
        val metersPerDegree = 111_320.0
        val startX = 0.0
        val startY = 0.0
        val endX = (end.lon - start.lon) * cos(meanLatitude) * metersPerDegree
        val endY = (end.lat - start.lat) * metersPerDegree
        val pointX = (point.lon - start.lon) * cos(meanLatitude) * metersPerDegree
        val pointY = (point.lat - start.lat) * metersPerDegree
        val dx = endX - startX
        val dy = endY - startY
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(pointX, pointY)

        val projection = ((pointX * dx + pointY * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(pointX - projection * dx, pointY - projection * dy)
    }

    private fun timestampMillis(point: TourLogEntry): Long =
        runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .parse(point.timestamp)?.time ?: 0L
        }.getOrDefault(0L)

    /**
     * Legacy Douglas-Peucker helper retained for existing callers and tests.
     */
    fun smoothPath(points: List<TourLogEntry>, epsilon: Double): List<TourLogEntry> {
        if (points.size < 3) return points

        var maxDist = 0.0
        var index = 0
        for (i in 1 until points.lastIndex) {
            val dist = perpendicularDistanceDegrees(points[i], points.first(), points.last())
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

    private fun perpendicularDistanceDegrees(
        point: TourLogEntry,
        start: TourLogEntry,
        end: TourLogEntry
    ): Double {
        val numerator = abs(
            (end.lon - start.lon) * (point.lat - start.lat) -
                (end.lat - start.lat) * (point.lon - start.lon)
        )
        val denominator = hypot(end.lon - start.lon, end.lat - start.lat)
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }
}
