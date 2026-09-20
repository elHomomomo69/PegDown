package com.dpm.pegdown.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import com.dpm.pegdown.model.TourLogEntry
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class SensorProcessor(
    private val context: Context,
    private val listener: SensorUpdateListener,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())
    private var tempResetRunnable: Runnable? = null
    private var accelResetRunnable: Runnable? = null

    private val gpxDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Settings
    var resetDurationMillis: Long = 7000L
    var smoothingAlpha: Double = 0.07

    // State
    var calibrationOffset = 0.0
    private var rawTilt = 0.0
    private var smoothedTilt = 0.0
    private var sensorStartupCounter = 0

    // Lean Angle
    var maxTourLeft = 0.0
    var maxTourRight = 0.0
    private var maxTempLeft = 0.0
    private var maxTempRight = 0.0
    private var lastPeakLeanAngle = 0.0

    // Accel / Brake
    var maxAcceleration = 0.0
    var maxBraking = 0.0
    var tourMaxAccel = 0.0
    var tourMaxBrake = 0.0
    private var smoothedAccel = 0.0
    private var smoothedBrake = 0.0
    private val accelSmoothingAlpha = 0.15 // Fast reaction but filters vibration

    // External state needed for recording
    var isRecording = false
    var currentLatitude = 0.0
    var currentLongitude = 0.0
    var currentAltitude = 0.0
    var currentSpeedKmH = 0.0
    private var lastValidLat = 0.0
    private var lastValidLon = 0.0
    private var lastValidTime = 0L

    fun start() {
        sensorStartupCounter = 0
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun calibrate() {
        smoothedTilt = rawTilt
        sensorStartupCounter = 0
        calibrationOffset = smoothedTilt
        notifyUpdates()
    }

    fun resetTour() {
        maxTourLeft = 0.0
        maxTourRight = 0.0
        maxTempLeft = 0.0
        maxTempRight = 0.0
        maxAcceleration = 0.0
        maxBraking = 0.0
        tourMaxAccel = 0.0
        tourMaxBrake = 0.0
        lastPeakLeanAngle = 0.0
        accelResetRunnable?.let { handler.removeCallbacks(it) }
        tempResetRunnable?.let { handler.removeCallbacks(it) }
        notifyUpdates()
    }

    internal fun checkAutoZero(currentAngle: Double) {
        val now = timeProvider()
        // Safety: Do not calibrate if we had high G-forces (turns/braking) in the last 20 seconds
        if (now - lastHighGTime < 20000) {
            straightDriveStartTime = 0L
            return
        }

        if ((currentSpeedKmH > autoZeroMinSpeed) && (abs(currentAngle) < autoZeroThresholdAngle)) {
            if (straightDriveStartTime == 0L) {
                straightDriveStartTime = now
            } else {
                val elapsed = now - straightDriveStartTime
                if (elapsed > autoZeroDurationMs) {
                    // Extremely gentle adjustment
                    calibrationOffset += (currentAngle * 0.005)
                }
            }
        } else {
            straightDriveStartTime = 0L
        }
    }

    // Auto-Zero logic
    private var straightDriveStartTime = 0L
    private var lastHighGTime = 0L
    private val autoZeroThresholdAngle = 1.0 // Strenger: 1 Grad
    private val autoZeroMinSpeed = 40.0 // km/h
    private val autoZeroDurationMs = 15000L // Länger warten: 15 Sekunden
    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    private fun notifyUpdates() {
        val calculatedAngle = smoothedTilt - calibrationOffset
        val finalAngle = kotlin.math.round(calculatedAngle / 0.1) * 0.1
        listener.onLeanAngleUpdate(finalAngle, maxTempLeft, maxTempRight, maxTourLeft, maxTourRight)
        listener.onAccelerationUpdate(maxAcceleration, maxBraking, tourMaxAccel, tourMaxBrake)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            handleGravity(event)
        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            handleLinearAcceleration(event)
        }
    }

    private fun handleGravity(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val rotation = try {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        } catch (_: Exception) {
            Surface.ROTATION_0
        }

        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val newRawTilt = if (isLandscape) {
            val invertSign = if (rotation == Surface.ROTATION_270) 1.0 else -1.0
            Math.toDegrees(atan2((-y * invertSign), sqrt(((x * x) + (z * z)).toDouble())))
        } else {
            Math.toDegrees(atan2(-x.toDouble(), sqrt(((y * y) + (z * z)).toDouble())))
        }

        if ((abs(newRawTilt - rawTilt) > 5.0) || (sensorStartupCounter < 15)) {
            smoothedTilt = newRawTilt
            sensorStartupCounter++
        } else {
            smoothedTilt += smoothingAlpha * (newRawTilt - smoothedTilt)
        }
        rawTilt = newRawTilt

        val calculatedAngle = smoothedTilt - calibrationOffset
        val finalAngle = kotlin.math.round(calculatedAngle / 0.1) * 0.1

        // Track when we have significant lean to pause Auto-Zero
        if (abs(finalAngle) > 5.0) {
            lastHighGTime = timeProvider()
        }

        checkAutoZero(finalAngle)

        if (finalAngle < maxTourLeft) maxTourLeft = finalAngle
        if (finalAngle > maxTourRight) maxTourRight = finalAngle

        var newTempPeak = false
        if (finalAngle < maxTempLeft) {
            maxTempLeft = finalAngle
            newTempPeak = true
        }
        if (finalAngle > maxTempRight) {
            maxTempRight = finalAngle
            newTempPeak = true
        }

        if (newTempPeak) {
            lastPeakLeanAngle = if (abs(maxTempLeft) > abs(maxTempRight)) maxTempLeft else maxTempRight
            tempResetRunnable?.let { handler.removeCallbacks(it) }
            tempResetRunnable = Runnable {
                if (isRecording && ((abs(lastPeakLeanAngle) > 0.5) || (maxAcceleration > 0.05) || (abs(maxBraking) > 0.05))) {
                    recordEntry()
                }
                maxTempLeft = 0.0
                maxTempRight = 0.0
                lastPeakLeanAngle = 0.0
                maxAcceleration = 0.0
                maxBraking = 0.0
                notifyUpdates()
            }
            handler.postDelayed(tempResetRunnable!!, resetDurationMillis)
        }

        notifyUpdates()
    }

    private fun handleLinearAcceleration(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]

        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val rawAccel = if (isLandscape) y else x

        var currentForwardG = (rawAccel / 9.81)
        if (abs(currentForwardG) < 0.05) currentForwardG = 0.0

        // Filter vibrations
        if (currentForwardG >= 0) {
            smoothedAccel += accelSmoothingAlpha * (currentForwardG - smoothedAccel)
            smoothedBrake = 0.0
        } else {
            smoothedBrake += accelSmoothingAlpha * (currentForwardG - smoothedBrake)
            smoothedAccel = 0.0
        }

        var newAccelPeak = false
        if (smoothedAccel > maxAcceleration && smoothedAccel < 2.0) {
            maxAcceleration = smoothedAccel
            newAccelPeak = true
        }
        if (smoothedBrake < maxBraking && smoothedBrake > -2.0) {
            maxBraking = smoothedBrake
            newAccelPeak = true
        }

        // Track when we have significant forces to pause Auto-Zero
        // Use rawTilt or a basic angle check here since finalAngle is only in handleGravity
        if (abs(currentForwardG) > 0.3) {
            lastHighGTime = timeProvider()
        }

        if (maxAcceleration > tourMaxAccel) tourMaxAccel = maxAcceleration
        if (maxBraking < tourMaxBrake) tourMaxBrake = maxBraking

        if (newAccelPeak) {
            val peakAccelToSave = maxAcceleration
            val peakBrakeToSave = maxBraking

            accelResetRunnable?.let { handler.removeCallbacks(it) }
            accelResetRunnable = Runnable {
                if (isRecording && (abs(lastPeakLeanAngle) > 1.0 || peakAccelToSave > 0.1 || abs(peakBrakeToSave) > 0.1)) {
                    recordEntry()
                }
                maxAcceleration = 0.0
                maxBraking = 0.0
                notifyUpdates()
            }
            handler.postDelayed(accelResetRunnable!!, resetDurationMillis)
        }
        notifyUpdates()
    }

    fun recordCurrentState() {
        if (isRecording) {
            recordEntry()
        }
    }

    private fun recordEntry() {
        // GPS Plausibility Check: Ignore spikes or impossible movement
        val now = timeProvider()
        if (lastValidTime != 0L) {
            val dist = calculateDistance(lastValidLat, lastValidLon, currentLatitude, currentLongitude)
            val timeSec = (now - lastValidTime) / 1000.0
            if (timeSec > 0) {
                val speedCheck = (dist / timeSec) * 3.6 // km/h
                // If calculated speed between points is > 300 km/h, it's likely a GPS jump
                if (speedCheck > 300.0) return 
            }
        }
        
        lastValidLat = currentLatitude
        lastValidLon = currentLongitude
        lastValidTime = now

        val leftVal = if (lastPeakLeanAngle < 0) abs(lastPeakLeanAngle) else 0.0
        val rightVal = if (lastPeakLeanAngle > 0) abs(lastPeakLeanAngle) else 0.0

        val entry = TourLogEntry(
            timestamp = gpxDateFormat.format(Date(now)),
            leanAngleLeft = leftVal,
            leanAngleRight = rightVal,
            acceleration = maxAcceleration,
            braking = maxBraking,
            lat = currentLatitude,
            lon = currentLongitude,
            altitude = currentAltitude,
            speed = currentSpeedKmH,
        )
        listener.onPeakRecorded(entry)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}