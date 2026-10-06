package com.dpm.pegdown.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.dpm.pegdown.R
import com.dpm.pegdown.data.SensorDiagnosticsRecorder
import com.dpm.pegdown.location.LocationTracker
import com.dpm.pegdown.location.LocationUpdateListener
import com.dpm.pegdown.model.TourLogEntry
import com.dpm.pegdown.sensor.SensorProcessor
import com.dpm.pegdown.sensor.SensorUpdateListener
import com.dpm.pegdown.ui.MainActivity

class RecordingService : Service(), SensorUpdateListener, LocationUpdateListener {

    companion object {
        private const val CHANNEL_ID = "pegdown_recording"
        private const val NOTIFICATION_ID = 1
    }

    private val binder = LocalBinder()

    private lateinit var sensorProcessor: SensorProcessor
    private lateinit var locationTracker: LocationTracker
    private lateinit var diagnosticsRecorder: SensorDiagnosticsRecorder

    private var uiListener: RecordingUpdateListener? = null

    private var isTracking = false

    val recordedEntries = mutableListOf<TourLogEntry>()

    var isRecording = false
        private set

    interface RecordingUpdateListener {
        fun onSensorUpdate(
            current: Double,
            tempL: Double,
            tempR: Double,
            tourL: Double,
            tourR: Double
        )

        fun onAccelUpdate(
            accel: Double,
            brake: Double,
            tourMaxAccel: Double,
            tourMaxBrake: Double
        )

        fun onLocationUpdate(
            location: Location,
            speedKmH: Double
        )
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()

        sensorProcessor = SensorProcessor(
            context = this,
            listener = this
        )

        locationTracker = LocationTracker(
            context = this,
            listener = this
        )
        diagnosticsRecorder = SensorDiagnosticsRecorder(this)

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setUpdateListener(listener: RecordingUpdateListener?) {
        uiListener = listener
    }

    // -------------------------------------------------------------------------
    // Tracking
    // -------------------------------------------------------------------------

    /**
     * Startet Sensoren und GPS nur einmal.
     *
     * Wichtig:
     * Diese Methode darf von MainActivity mehrfach aufgerufen werden,
     * ohne den SensorProcessor jedes Mal neu zu initialisieren.
     */
    fun startTracking() {
        if (!isTracking) {
            sensorProcessor.start()
            isTracking = true
        }

        // Auch bei bereits laufenden Sensoren erneut prüfen, ob eine
        // inzwischen erteilte Standortberechtigung jetzt Updates erlaubt.
        locationTracker.start()
    }

    /**
     * Stoppt Tracking nur dann, wenn gerade keine Tour aufgezeichnet wird.
     */
    fun stopTracking() {
        if (isRecording) return
        if (!isTracking) return

        sensorProcessor.stop()
        locationTracker.stop()

        isTracking = false

        stopForegroundCompat()
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    fun startTourRecording() {
        if (isRecording) return

        // Sicherheit: Tracking muss aktiv sein.
        startTracking()

        recordedEntries.clear()

        isRecording = true
        if (!diagnosticsRecorder.start(
                sessionMetadata = sensorProcessor.diagnosticSessionMetadata(),
                sensors = sensorProcessor.diagnosticSensors(),
            )
        ) {
            android.widget.Toast.makeText(
                this,
                R.string.sensor_diagnostics_unavailable,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } else {
            android.widget.Toast.makeText(
                this,
                R.string.sensor_diagnostics_started,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        sensorProcessor.startRecording()
        locationTracker.start(diagnosticRecording = true)

        startForeground(
            NOTIFICATION_ID,
            createNotification("PegDown: Recording active")
        )
    }

    fun stopTourRecording() {
        if (!isRecording) return

        /*
         * finishRecording() darf ausgeführt werden, solange
         * RecordingService.isRecording noch true ist.
         *
         * Dadurch kann ein letzter aktiver Peak über onPeakRecorded()
         * noch in recordedEntries übernommen werden.
         */
        sensorProcessor.finishRecording()

        isRecording = false

        sensorProcessor.stopRecording()
        diagnosticsRecorder.stop()
        if (isTracking) {
            locationTracker.start(diagnosticRecording = false)
        }

        stopForegroundCompat()
    }

    // -------------------------------------------------------------------------
    // Sensor / Calibration
    // -------------------------------------------------------------------------

    fun calibrate() {
        sensorProcessor.calibrate()
    }

    fun isCalibrated(): Boolean {
        return sensorProcessor.isCalibrated && (!sensorProcessor.experimentalLeanEnabled || sensorProcessor.isExperimentalReady)
    }

    fun isExperimentalLeanEnabled(): Boolean = sensorProcessor.experimentalLeanEnabled
    fun isExperimentalLeanReady(): Boolean = sensorProcessor.isExperimentalReady
    fun isExperimentalLeanRecovering(): Boolean = sensorProcessor.isExperimentalRecovering

    fun hasSensorData(): Boolean {
        return sensorProcessor.hasSensorData
    }

    fun isCalibrationInProgress(): Boolean {
        return sensorProcessor.isCalibrationInProgress
    }

    fun getCalibrationOffset(): Double {
        return sensorProcessor.calibrationOffset
    }

    // -------------------------------------------------------------------------
    // Tour
    // -------------------------------------------------------------------------

    fun resetTour() {
        sensorProcessor.resetTour()
        recordedEntries.clear()
        if (isRecording) {
            sensorProcessor.recordCurrentState()
        }
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    fun updateSettings(
        resetMillis: Long,
        smoothing: Double,
        experimentalLean: Boolean = false,
    ) {
        sensorProcessor.resetDurationMillis = resetMillis
        sensorProcessor.smoothingAlpha = smoothing
        if (!isRecording) sensorProcessor.setExperimentalLeanEnabled(experimentalLean)
    }

    // -------------------------------------------------------------------------
    // SensorUpdateListener
    // -------------------------------------------------------------------------

    override fun onDiagnosticCalibration(metadata: String) {
        diagnosticsRecorder.recordCalibration(metadata)
    }

    override fun onDiagnosticSensorSample(
        sensor: android.hardware.Sensor,
        timestampNanos: Long,
        accuracy: Int,
        values: FloatArray,
        estimatedLeanDegrees: Double,
        calibrated: Boolean,
    ) {
        diagnosticsRecorder.recordSensorSample(
            sensor = sensor,
            timestampNanos = timestampNanos,
            accuracy = accuracy,
            values = values,
            estimatedLeanDegrees = estimatedLeanDegrees,
            calibrated = calibrated,
            standardLeanDegrees = sensorProcessor.standardLeanAngleDegrees(),
            experimentalLeanDegrees = sensorProcessor.experimentalLeanAngleDegrees(),
            leanMode = if (sensorProcessor.experimentalLeanEnabled) "experimental_full_interval" else "android_standard",
            experimentalStatus = sensorProcessor.experimentalDiagnosticStatus(),
        )
    }

    override fun onDiagnosticLeanCorrection(
        referenceNanos: Long,
        arrivalNanos: Long,
        reason: String,
        metadata: String,
    ) {
        if (isRecording) diagnosticsRecorder.recordLeanCorrection(referenceNanos, arrivalNanos, reason, metadata)
    }

    override fun onLeanAngleUpdate(
        current: Double,
        tempL: Double,
        tempR: Double,
        tourL: Double,
        tourR: Double
    ) {
        uiListener?.onSensorUpdate(
            current,
            tempL,
            tempR,
            tourL,
            tourR
        )
    }

    override fun onAccelerationUpdate(
        accel: Double,
        brake: Double,
        tourMaxAccel: Double,
        tourMaxBrake: Double
    ) {
        uiListener?.onAccelUpdate(
            accel,
            brake,
            tourMaxAccel,
            tourMaxBrake
        )
    }

    override fun onPeakRecorded(entry: TourLogEntry) {
        if (!isRecording) return

        recordedEntries.add(entry)
    }

    // -------------------------------------------------------------------------
    // LocationUpdateListener
    // -------------------------------------------------------------------------

    override fun onLocationUpdate(
        location: Location,
        speedKmH: Double
    ) {
        val arrivalNanos = SystemClock.elapsedRealtimeNanos()
        sensorProcessor.currentLatitude = location.latitude
        sensorProcessor.currentLongitude = location.longitude
        sensorProcessor.currentAltitude = if (location.hasAltitude()) location.altitude else 0.0
        sensorProcessor.currentSpeedKmH = speedKmH
        sensorProcessor.handleExperimentalLocation(location, arrivalNanos)
        val callbackLeanDegrees = sensorProcessor.currentLeanAngleDegrees()
        val leanAtFixDegrees = sensorProcessor.leanAngleAt(location.elapsedRealtimeNanos)
        if (isRecording) {
            diagnosticsRecorder.recordLocation(
                location = location,
                estimatedLeanDegrees = leanAtFixDegrees ?: callbackLeanDegrees,
                calibrated = isCalibrated(),
                callbackLeanDegrees = callbackLeanDegrees,
                leanAlignedToFix = leanAtFixDegrees != null,
                callbackElapsedRealtimeNanos = arrivalNanos,
                standardLeanDegrees = sensorProcessor.standardLeanAngleDegrees(),
                experimentalLeanDegrees = sensorProcessor.experimentalLeanAngleDegrees(),
                leanMode = if (sensorProcessor.experimentalLeanEnabled) "experimental_full_interval" else "android_standard",
                experimentalStatus = sensorProcessor.experimentalDiagnosticStatus(),
            )
        }
        sensorProcessor.recordLocation(location, speedKmH, leanAtFixDegrees)

        uiListener?.onLocationUpdate(
            location,
            speedKmH
        )
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "PegDown Aufzeichnung",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status der PegDown-Aufzeichnung"
        }

        val manager = getSystemService(
            NotificationManager::class.java
        )

        manager.createNotificationChannel(channel)
    }

    private fun createNotification(
        text: String
    ): Notification {

        val intent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_motorcycle_rear)
            .setContentTitle("PegDown")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onDestroy() {

        /*
         * Falls Android den Service beendet, sauber aufräumen.
         */
        if (isRecording) {
            sensorProcessor.finishRecording()
            sensorProcessor.stopRecording()
            isRecording = false
            diagnosticsRecorder.stop()
        }

        if (isTracking) {
            sensorProcessor.stop()
            locationTracker.stop()
            isTracking = false
        }

        uiListener = null

        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}