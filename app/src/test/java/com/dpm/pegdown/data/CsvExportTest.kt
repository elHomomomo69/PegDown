package com.dpm.pegdown.data

import com.dpm.pegdown.model.TourLogEntry
import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExportTest {

    private val context = mockk<Context>(relaxed = true)
    private val tourExporter = TourExporter(context)

    @Test
    fun `generateCsvString contains correct header`() {
        val csv = tourExporter.generateCsvString(emptyList())
        assertTrue(csv.startsWith("Timestamp;LeanAngleLeft;LeanAngleRight;Acceleration;Braking;Latitude;Longitude;Altitude;Speed"))
    }

    @Test
    fun `generateCsvString formats entry with semicolons`() {
        val entry = TourLogEntry(
            timestamp = "2024-01-01 12:00:00",
            leanAngleLeft = 12.5,
            leanAngleRight = 0.0,
            acceleration = 0.2,
            braking = -0.1,
            lat = 52.5,
            lon = 13.4,
            altitude = 173.0,
            speed = 80.0
        )

        val csv = tourExporter.generateCsvString(listOf(entry))
        val lines = csv.trim().split("\n")
        
        assertEquals(2, lines.size) // Header + 1 Data line
        assertEquals("2024-01-01 12:00:00;12.5;0.0;0.2;-0.1;52.5;13.4;173.0;80.0", lines[1])
    }
}
