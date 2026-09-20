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
    fun `smoothPath keeps corner points`() {
        val cornerPath = listOf(
            createEntry(52.0, 13.0),
            createEntry(52.1, 13.0),
            createEntry(52.2, 13.1),
            createEntry(52.1, 13.2),
            createEntry(52.0, 13.2)
        )

        val simplified = PathSmoother.smoothPath(cornerPath, 0.001)

        assertTrue("Simplified path should contain at least 3 points", simplified.size >= 3)
        val hasPeak = simplified.any { it.lat == 52.2 && it.lon == 13.1 }
        assertTrue("Simplified path should contain the peak point", hasPeak)
    }

    private fun createEntry(lat: Double, lon: Double): TourLogEntry {
        return TourLogEntry(
            timestamp = "2024-01-01 12:00:00",
            leanAngleLeft = 0.0,
            leanAngleRight = 0.0,
            acceleration = 0.0,
            braking = 0.0,
            lat = lat,
            lon = lon,
            speed = 50.0
        )
    }
}
