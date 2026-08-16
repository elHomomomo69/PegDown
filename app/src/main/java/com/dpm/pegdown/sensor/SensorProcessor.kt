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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

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

    // External state needed for recording
    var isRecording = false
    var currentLatitude = 0.0
    var currentLongitude = 0.0
    var currentSpeedKmH = 0.0

    fun start() {
        sensorStartupCounter = 0
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        linearAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
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

        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Using windowManager for broader compatibility in separate class
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
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

        var forwardAcceleration = (rawAccel / 9.81)
        if (abs(forwardAcceleration) < 0.04) forwardAcceleration = 0.0

        var newAccelPeak = false
        if (forwardAcceleration > maxAcceleration) {
            maxAcceleration = forwardAcceleration
            newAccelPeak = true
        }
        if (forwardAcceleration < maxBraking) {
            maxBraking = forwardAcceleration
            newAccelPeak = true
        }

        if (maxAcceleration > tourMaxAccel) tourMaxAccel = maxAcceleration
        if (maxBraking < tourMaxBrake) tourMaxBrake = maxBraking

        if (newAccelPeak) {
            val peakAccelToSave = maxAcceleration
            val peakBrakeToSave = maxBraking

            accelResetRunnable?.let { handler.removeCallbacks(it) }
            accelResetRunnable = Runnable {
                if (isRecording && (abs(lastPeakLeanAngle) > 0.5 || peakAccelToSave > 0.05 || abs(peakBrakeToSave) > 0.05)) {
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

    private fun recordEntry() {
        val leftVal = if (lastPeakLeanAngle < 0) abs(lastPeakLeanAngle) else 0.0
        val rightVal = if (lastPeakLeanAngle > 0) abs(lastPeakLeanAngle) else 0.0

        val entry = TourLogEntry(
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            leanAngleLeft = leftVal,
            leanAngleRight = rightVal,
            acceleration = maxAcceleration,
            braking = maxBraking,
            lat = currentLatitude,
            lon = currentLongitude,
            speed = currentSpeedKmH,
        )
        listener.onPeakRecorded(entry)
    }
}