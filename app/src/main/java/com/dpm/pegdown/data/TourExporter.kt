package com.dpm.pegdown.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dpm.pegdown.model.TourLogEntry
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

class TourExporter(private val context: Context) {

    fun saveTourToGpx(fileName: String, recordedEntries: List<TourLogEntry>) {
        try {
            val gpxHeader = """<?xml version="1.0" encoding="UTF-8" ?>
<gpx version="1.1" creator="PegDownApp"
  xmlns="http://www.topografix.com/GPX/1/1"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <trk>
    <name>${fileName.replace(".gpx", "")}</name>
    <trkseg>
"""
            val gpxFooter = """    </trkseg>
  </trk>
</gpx>"""

            val gpxContent = StringBuilder(gpxHeader)
            for (entry in recordedEntries) {
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

                val entryXml = String.format(Locale.US,
                    """      <trkpt lat="%.8f" lon="%.8f">
        <time>%s</time>
        <cmt>%s</cmt>
        <desc>%s</desc>
      </trkpt>
""",
                    entry.lat,
                    entry.lon,
                    isoTime,
                    desc,
                    desc,
                )
                gpxContent.append(entryXml)
            }
            gpxContent.append(gpxFooter)

            val finalFileName = if (fileName.endsWith(".gpx")) fileName else "$fileName.gpx"
            val fileContentBytes = gpxContent.toString().toByteArray(StandardCharsets.UTF_8)

            saveFile(finalFileName, "application/gpx+xml", fileContentBytes)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving GPX file!", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveTourToCsv(fileName: String, recordedEntries: List<TourLogEntry>) {
        try {
            val csvHeader = "Timestamp;LeanAngleLeft;LeanAngleRight;Acceleration;Braking;Latitude;Longitude;Speed\n"
            val csvContent = StringBuilder(csvHeader)

            for (entry in recordedEntries) {
                csvContent.append("${entry.timestamp};${entry.leanAngleLeft};${entry.leanAngleRight};${entry.acceleration};${entry.braking};${entry.lat};${entry.lon};${entry.speed}\n")
            }

            val finalFileName = if (fileName.endsWith(".csv")) fileName else "$fileName.csv"
            val fileContentBytes = csvContent.toString().toByteArray(StandardCharsets.UTF_8)

            saveFile(finalFileName, "text/csv", fileContentBytes)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving CSV file!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFile(fileName: String, mimeType: String, content: ByteArray) {
        var fileUri: Uri? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content) }
                fileUri = uri
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, fileName)
            file.writeBytes(content)
            fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        Toast.makeText(context, "Saved to Downloads!", Toast.LENGTH_LONG).show()

        if (fileUri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share tour via"))
        }
    }
}