package com.dpm.pegdown.data

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.dpm.pegdown.R
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams raw IMU and location callbacks to a separate diagnostic CSV.
 * Samples carry both wall-clock and monotonic timestamps for sensor/GNSS alignment.
 */
class SensorDiagnosticsRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val queue = ArrayBlockingQueue<String>(8192)
    private val droppedRows = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var acceptingSamples = false

    @Volatile
    private var stopping = false

    @Volatile
    private var worker: Thread? = null

    fun start(
        sessionMetadata: String,
        sensors: List<Sensor>
    ): Boolean {
        synchronized(this) {
            if (acceptingSamples || worker?.isAlive == true) return false

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
            val fileName = "PegDown_sensor_diagnostics_$timestamp.csv"
            val uri = createOutputUri(fileName) ?: return false

            queue.clear()
            droppedRows.set(0L)
            stopping = false
            acceptingSamples = true

            worker = Thread(
                { writeSession(uri, fileName, sessionMetadata, sensors) },
                "PegDownSensorDiagnostics",
            ).apply {
                isDaemon = true
                start()
            }
            return true
        }
    }

    fun recordSensorSample(
        sensor: Sensor,
        timestampNanos: Long,
        accuracy: Int,
        values: FloatArray,
        estimatedLeanDegrees: Double,
        calibrated: Boolean,
    ) {
        if (!acceptingSamples) return
        val fields = blankRow("sensor_sample")
        fields[1] = System.currentTimeMillis().toString()
        fields[2] = timestampNanos.toString()
        fields[3] = sensor.type.toString()
        fields[7] = accuracy.toString()
        fields[8] = values.getOrNull(0)?.toString().orEmpty()
        fields[9] = values.getOrNull(1)?.toString().orEmpty()
        fields[10] = values.getOrNull(2)?.toString().orEmpty()
        fields[11] = values.getOrNull(3)?.toString().orEmpty()
        fields[12] = estimatedLeanDegrees.toString()
        fields[13] = calibrated.toString()
        enqueue(fields)
    }

    fun recordLocation(
        location: Location,
        estimatedLeanDegrees: Double,
        calibrated: Boolean,
        callbackLeanDegrees: Double = estimatedLeanDegrees,
        leanAlignedToFix: Boolean = true,
    ) {
        if (!acceptingSamples) return
        val fields = blankRow("location")
        fields[1] = System.currentTimeMillis().toString()
        fields[2] = location.elapsedRealtimeNanos.toString()
        fields[12] = estimatedLeanDegrees.toString()
        fields[13] = calibrated.toString()
        fields[14] = location.latitude.toString()
        fields[15] = location.longitude.toString()
        fields[16] = if (location.hasAltitude()) location.altitude.toString() else ""
        fields[17] = if (location.hasAccuracy()) location.accuracy.toString() else ""
        fields[18] = if (location.hasSpeed()) location.speed.toString() else ""
        fields[19] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond.toString()
        } else {
            ""
        }
        fields[20] = if (location.hasBearing()) location.bearing.toString() else ""
        fields[21] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
            location.bearingAccuracyDegrees.toString()
        } else {
            ""
        }
        fields[22] = location.provider.orEmpty()
        fields[23] = location.time.toString()
        fields[24] = location.elapsedRealtimeNanos.toString()
        fields[28] = location.hasSpeed().toString()
        fields[29] = location.hasBearing().toString()
        fields[30] = location.hasAltitude().toString()
        fields[31] = location.hasAccuracy().toString()
        fields[32] = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()).toString()
        fields[33] = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()).toString()
        fields[34] = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()).toString()
        fields[35] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            location.verticalAccuracyMeters.toString()
        } else {
            ""
        }
        fields[37] = callbackLeanDegrees.toString()
        fields[38] = leanAlignedToFix.toString()
        enqueue(fields)
    }

    fun stop() {
        acceptingSamples = false
        stopping = true
    }

    private fun writeSession(
        uri: Uri,
        fileName: String,
        sessionMetadata: String,
        sensors: List<Sensor>,
    ) {
        try {
            val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appContext.contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("Could not open diagnostics output")
            } else {
                FileOutputStream(File(uri.path ?: error("Missing diagnostics path")))
            }
            BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), 64 * 1024).use { writer ->
                writer.appendLine(CSV_HEADER)
                writer.appendLine(
                    encodeRow(
                        blankRow("session").apply {
                            this[1] = System.currentTimeMillis().toString()
                            this[2] = android.os.SystemClock.elapsedRealtimeNanos().toString()
                            this[36] = sessionMetadata
                        },
                    ),
                )
                sensors.forEach { sensor ->
                    writer.appendLine(
                        encodeRow(
                            blankRow("sensor_info").apply {
                                this[3] = sensor.type.toString()
                                this[4] = sensor.name
                                this[5] = sensor.vendor
                                this[6] = sensor.version.toString()
                                this[25] = sensor.resolution.toString()
                                this[26] = sensor.maximumRange.toString()
                                this[27] = sensor.minDelay.toString()
                            },
                        ),
                    )
                }

                while (!stopping || queue.isNotEmpty()) {
                    val row = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    writer.appendLine(row)
                }

                val dropped = droppedRows.get()
                if (dropped > 0L) {
                    writer.appendLine(
                        encodeRow(
                            blankRow("dropped_samples").apply {
                                this[1] = System.currentTimeMillis().toString()
                                this[2] = android.os.SystemClock.elapsedRealtimeNanos().toString()
                                this[36] = dropped.toString()
                            },
                        ),
                    )
                }
                writer.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appContext.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null,
                )
            }
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.sensor_diagnostics_saved, fileName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        } catch (error: Exception) {
            acceptingSamples = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appContext.contentResolver.delete(uri, null, null)
            } else {
                uri.path?.let { File(it).delete() }
            }
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    appContext.getString(
                        R.string.sensor_diagnostics_save_failed,
                        error.localizedMessage.orEmpty(),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun createOutputUri(fileName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                appContext.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                )
            } else {
                val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: appContext.filesDir
                if (!directory.exists() && !directory.mkdirs()) return null
                File(directory, fileName).let(Uri::fromFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun enqueue(fields: List<String>) {
        if (!queue.offer(encodeRow(fields))) droppedRows.incrementAndGet()
    }

    private fun blankRow(type: String): MutableList<String> =
        MutableList(COLUMN_COUNT) { "" }.apply { this[0] = type }

    private fun encodeRow(fields: List<String>): String =
        fields.joinToString(",") { value ->
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"${value.replace("\"", "\"\"")}\""
            } else {
                value
            }
        }

    private companion object {
        const val COLUMN_COUNT = 39
        const val CSV_HEADER =
            "record_type,wall_time_ms,elapsed_realtime_ns,sensor_type,sensor_name,sensor_vendor,sensor_version,sensor_accuracy,value_0,value_1,value_2,value_3,lean_estimate_deg,calibrated,latitude,longitude,altitude_m,location_accuracy_m,speed_mps,speed_accuracy_mps,bearing_deg,bearing_accuracy_deg,provider,location_time_ms,location_elapsed_realtime_ns,sensor_resolution,sensor_max_range,sensor_min_delay_us,has_speed,has_bearing,has_altitude,has_location_accuracy,has_speed_accuracy,has_bearing_accuracy,has_vertical_accuracy,vertical_accuracy_m,metadata,lean_callback_deg,lean_aligned_to_fix"
    }
}
