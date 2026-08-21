package com.dpm.pegdown.service

import android.app.*
import android.content.Intent
import android.location.Location
import android.os.Binder
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

    private val binder = LocalBinder()
    private lateinit var sensorProcessor: SensorProcessor
    private lateinit var locationTracker: LocationTracker
    
    private var uiListener: RecordingUpdateListener? = null
    val recordedEntries = mutableListOf<TourLogEntry>()
    
    var isRecording = false
        private set

    interface RecordingUpdateListener {
        fun onSensorUpdate(current: Double, tempL: Double, tempR: Double, tourL: Double, tourR: Double)
        fun onAccelUpdate(accel: Double, brake: Double, tourMaxAccel: Double, tourMaxBrake: Double)
        fun onLocationUpdate(location: Location, speedKmH: Double)
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        sensorProcessor = SensorProcessor(this, this)
        locationTracker = LocationTracker(this, this)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setUpdateListener(listener: RecordingUpdateListener?) {
        this.uiListener = listener
    }

    fun startTracking() {
        sensorProcessor.start()
        locationTracker.start()
    }

    fun stopTracking() {
        if (!isRecording) {
            sensorProcessor.stop()
            locationTracker.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun startTourRecording() {
        isRecording = true
        sensorProcessor.isRecording = true
        recordedEntries.clear()
        startForeground(1, createNotification("PegDown: Recording active"))
    }

    fun stopTourRecording() {
        isRecording = false
        sensorProcessor.isRecording = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun calibrate() {
        sensorProcessor.calibrate()
    }

    fun resetTour() {
        sensorProcessor.resetTour()
        recordedEntries.clear()
    }

    fun updateSettings(resetMillis: Long, smoothing: Double) {
        sensorProcessor.resetDurationMillis = resetMillis
        sensorProcessor.smoothingAlpha = smoothing
    }

    override fun onLeanAngleUpdate(current: Double, tempL: Double, tempR: Double, tourL: Double, tourR: Double) {
        uiListener?.onSensorUpdate(current, tempL, tempR, tourL, tourR)
    }

    override fun onAccelerationUpdate(accel: Double, brake: Double, tourMaxAccel: Double, tourMaxBrake: Double) {
        uiListener?.onAccelUpdate(accel, brake, tourMaxAccel, tourMaxBrake)
    }

    override fun onPeakRecorded(entry: TourLogEntry) {
        if (isRecording) {
            recordedEntries.add(entry)
        }
    }

    override fun onLocationUpdate(location: Location, speedKmH: Double) {
        sensorProcessor.currentLatitude = location.latitude
        sensorProcessor.currentLongitude = location.longitude
        sensorProcessor.currentSpeedKmH = speedKmH
        uiListener?.onLocationUpdate(location, speedKmH)
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "recording_channel")
            .setContentTitle("PegDown Tracking")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "recording_channel",
            "Recording Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
