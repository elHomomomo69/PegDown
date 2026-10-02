package com.dpm.pegdown.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dpm.pegdown.model.TourLogEntry
import com.dpm.pegdown.util.PathSmoother
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

class TourExporter(private val context: Context) {

    fun saveTourToGpx(fileName: String, recordedEntries: List<TourLogEntry>) {
        try {
            val gpxString = generateGpxString(fileName, recordedEntries)
            val finalFileName = if (fileName.endsWith(".gpx")) fileName else "$fileName.gpx"
            val fileContentBytes = gpxString.toByteArray(StandardCharsets.UTF_8)

            saveFile(finalFileName, "application/gpx+xml", fileContentBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving GPX file!", Toast.LENGTH_SHORT).show()
        }
    }

    fun generateGpxString(fileName: String, recordedEntries: List<TourLogEntry>): String {
        // Redundante GPS-Samples ausdünnen, aber Sensorereignisse, Endpunkte
        // und Punkte an längeren Zeitlücken erhalten.
        val trackEntries = PathSmoother.simplifyTrack(
            recordedEntries.filter { entry ->
                entry.lat in -90.0..90.0 && entry.lon in -180.0..180.0 &&
                    (entry.lat != 0.0 || entry.lon != 0.0)
            }
        )

        val gpxHeader = """<?xml version="1.0" encoding="UTF-8" ?>
<gpx version="1.1" creator="PegDownApp"
  xmlns="http://www.topografix.com/GPX/1/1"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <trk>
    <name>${escapeXml(fileName.removeSuffix(".gpx"))}</name>
    <trkseg>
"""
        val gpxFooter = """    </trkseg>
  </trk>
</gpx>"""

        val gpxContent = StringBuilder(gpxHeader)
        for (entry in trackEntries) {
            if ((entry.lat == 0.0) && (entry.lon == 0.0)) continue

            val isoTime = entry.timestamp.replace(" ", "T") + "Z"
            val desc = String.format(
                Locale.US,
                "Lean: L %.1f R %.1f | Accel: %.2fg | Brake: %.2fg | Speed: %.1f kmh",
                entry.leanAngleLeft,
                entry.leanAngleRight,
                entry.acceleration,
                entry.braking,
                entry.speed,
            )

            val entryXml = String.format(
                Locale.US,
                """      <trkpt lat="%.8f" lon="%.8f">
        <ele>%.1f</ele>
        <time>%s</time>
        <cmt>%s</cmt>
      </trkpt>
""",
                entry.lat,
                entry.lon,
                entry.altitude,
                isoTime,
                escapeXml(desc),
            )
            gpxContent.append(entryXml)
        }
        gpxContent.append(gpxFooter)
        return gpxContent.toString()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun saveTourToCsv(fileName: String, recordedEntries: List<TourLogEntry>) {
        try {
            val csvString = generateCsvString(recordedEntries)
            val finalFileName = if (fileName.endsWith(".csv")) fileName else "$fileName.csv"
            val fileContentBytes = csvString.toByteArray(StandardCharsets.UTF_8)

            saveFile(finalFileName, "text/csv", fileContentBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving CSV file!", Toast.LENGTH_SHORT).show()
        }
    }

    fun generateCsvString(recordedEntries: List<TourLogEntry>): String {
        val csvHeader = "Timestamp;LeanAngleLeft;LeanAngleRight;Acceleration;Braking;Latitude;Longitude;Altitude;Speed\n"
        val csvContent = StringBuilder(csvHeader)

        for (entry in recordedEntries) {
            csvContent.append("${entry.timestamp};${entry.leanAngleLeft};${entry.leanAngleRight};${entry.acceleration};${entry.braking};${entry.lat};${entry.lon};${entry.altitude};${entry.speed}\n")
        }
        return csvContent.toString()
    }

    private fun saveFile(fileName: String, mimeType: String, content: ByteArray) {
        val safeFileName = fileName
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .ifEmpty { "PegDown-tour" }

        val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("Could not create download entry")
            try {
                val output = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open download entry")
                output.use { it.write(content) }
                uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                throw IllegalStateException("Could not create Downloads directory")
            }
            val file = File(downloadsDir, safeFileName)
            file.writeBytes(content)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        Toast.makeText(context, "Saved to Downloads!", Toast.LENGTH_LONG).show()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, savedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share tour via"))
    }
}