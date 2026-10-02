package com.dpm.pegdown.util

import com.dpm.pegdown.model.TourLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSmootherTest {

    @Test
    fun `smoothPath reduces points on a straight line`() {
        val straightPath = listOf(
            createEntry(52.0, 13.0),
            createEntry(52.1, 13.0),
            createEntry(52.2, 13.0),
            createEntry(52.3, 13.0),
            createEntry(52.4, 13.0)
        )

        val simplified = PathSmoother.smoothPath(straightPath, 0.001)

        assertEquals(2, simplified.size)
        assertEquals(52.0, simplified.first().lat, 0.0001)
        assertEquals(52.4, simplified.last().lat, 0.0001)
    }

    @Test
    fun `simplifyTrack removes redundant points on a straight route`() {
        val straightTrack = (0..10).map { index ->
            createEntry(
                lat = 52.0 + index * 0.00001,
                lon = 13.0,
                timestamp = "2024-01-01 12:00:%02d".format(index * 2)
            )
        }

        val simplified = PathSmoother.simplifyTrack(straightTrack)

        assertEquals(2, simplified.size)
        assertEquals(straightTrack.first(), simplified.first())
        assertEquals(straightTrack.last(), simplified.last())
    }

    @Test
    fun `simplifyTrack preserves sensor event points`() {
        val track = (0..10).map { index ->
            createEntry(
                lat = 52.0 + index * 0.00001,
                lon = 13.0,
                timestamp = "2024-01-01 12:00:%02d".format(index * 2),
                leanAngleLeft = if (index == 5) 18.0 else 0.0
            )
        }
        val eventPoint = track[5]

        val simplified = PathSmoother.simplifyTrack(track)

        assertTrue(simplified.contains(eventPoint))
        assertEquals(track.first(), simplified.first())
        assertEquals(track.last(), simplified.last())
    }

    @Test
    fun `simplifyTrack preserves points after long time gaps`() {
        val track = (0..10).map { index ->
            val seconds = if (index >= 5) 30 + (index - 5) * 2 else index * 2
            createEntry(
                lat = 52.0 + index * 0.00001,
                lon = 13.0,
                timestamp = "2024-01-01 12:00:%02d".format(seconds)
            )
        }
        val gapPoint = track[5]

        val simplified = PathSmoother.simplifyTrack(track)

        assertTrue(simplified.contains(gapPoint))
    }

    private fun createEntry(
        lat: Double,
        lon: Double,
        timestamp: String = "2024-01-01 12:00:00",
        leanAngleLeft: Double = 0.0
    ): TourLogEntry {
        return TourLogEntry(
            timestamp = timestamp,
            leanAngleLeft = leanAngleLeft,
            leanAngleRight = 0.0,
            acceleration = 0.0,
            braking = 0.0,
            lat = lat,
            lon = lon,
            altitude = 150.0,
            speed = 50.0
        )
    }
}
