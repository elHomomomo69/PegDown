package com.dpm.pegdown.data

import com.dpm.pegdown.model.TourLogEntry
import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExportTest {

    private val context = mockk<Context>(relaxed = true)
    private val tourExporter = TourExporter(context)

    @Test
    fun `generateGpxString contains valid XML header and footer`() {
        val entries = listOf(createEntry(52.0, 13.0))
        val gpx = tourExporter.generateGpxString("TestTour", entries)

        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"))
        assertTrue(gpx.contains("<trk>"))
        assertTrue(gpx.contains("<name>TestTour</name>"))
        assertTrue(gpx.endsWith("</gpx>"))
    }

    @Test
    fun `generateGpxString contains correctly formatted track points`() {
        val entry = TourLogEntry(
            timestamp = "2024-01-01 12:00:00",
            leanAngleLeft = 25.5,
            leanAngleRight = 0.0,
            acceleration = 0.15,
            braking = -0.10,
            lat = 52.5200,
            lon = 13.4050,
            speed = 65.0
        )
        
        val gpx = tourExporter.generateGpxString("TourData", listOf(entry))

        // Check if lat/lon are in the XML
        assertTrue(gpx.contains("lat=\"52.52000000\""))
        assertTrue(gpx.contains("lon=\"13.40500000\""))
        
        // Check if the description (lean/accel) is present in <cmt> and <desc>
        assertTrue(gpx.contains("<cmt>Lean: L 25.5 R 0.0 | Accel: 0.15g | Brake: -0.10g | Speed: 65.0 kmh</cmt>"))
        assertTrue(gpx.contains("<desc>Lean: L 25.5 R 0.0 | Accel: 0.15g | Brake: -0.10g | Speed: 65.0 kmh</desc>"))
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
