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
import androidx.core.app.NotificationCompat
import com.dpm.pegdown.R
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
        if (isTracking) return

        sensorProcessor.start()
        locationTracker.start()

        isTracking = true
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

        sensorProcessor.startRecording()

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

        stopForegroundCompat()
    }

    // -------------------------------------------------------------------------
    // Sensor / Calibration
    // -------------------------------------------------------------------------

    fun calibrate() {
        sensorProcessor.calibrate()
    }

    fun isCalibrated(): Boolean {
        return sensorProcessor.isCalibrated
    }

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
        /*
         * Eine laufende Aufnahme nicht heimlich löschen.
         */
        if (isRecording) return

        sensorProcessor.resetTour()
        recordedEntries.clear()
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    fun updateSettings(
        resetMillis: Long,
        smoothing: Double
    ) {
        sensorProcessor.resetDurationMillis = resetMillis
        sensorProcessor.smoothingAlpha = smoothing
    }

    // -------------------------------------------------------------------------
    // SensorUpdateListener
    // -------------------------------------------------------------------------

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
        sensorProcessor.currentLatitude = location.latitude
        sensorProcessor.currentLongitude = location.longitude
        sensorProcessor.currentAltitude = location.altitude
        sensorProcessor.currentSpeedKmH = speedKmH

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