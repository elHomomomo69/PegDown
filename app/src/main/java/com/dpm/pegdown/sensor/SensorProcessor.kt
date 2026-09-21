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
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
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

    // Fusion State
    private var lastFusionTimestamp = 0L
    private var currentFusedAngle = 0.0 
    private val filterCoefficient = 0.98 // 98% Gyro, 2% Accel - The Industry Standard
    
    // Physical state
    var calibrationOffset = 0.0
        private set
    private var sensorStartupCounter = 0

    // Lean Angle Peaks
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
    private val accelSmoothingAlpha = 0.15 

    // External state needed for recording
    var isRecording = false
    var currentLatitude = 0.0
    var currentLongitude = 0.0
    var currentAltitude = 0.0
    var currentSpeedKmH = 0.0
    private var lastValidLat = 0.0
    private var lastValidLon = 0.0
    private var lastValidTime = 0L

    // Auto-Zero logic
    private var straightDriveStartTime = 0L
    private var lastHighGTime = 0L
    private val autoZeroThresholdAngle = 1.0 
    private val autoZeroMinSpeed = 40.0 
    private val autoZeroDurationMs = 15000L 
    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    fun start() {
        sensorStartupCounter = 0
        lastFusionTimestamp = 0L
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun calibrate() {
        calibrationOffset = currentFusedAngle
        sensorStartupCounter = 0
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
        if (lastHighGTime > 0 && (now - lastHighGTime < 20000)) {
            straightDriveStartTime = 0L
            return
        }

        if ((currentSpeedKmH > autoZeroMinSpeed) && (abs(currentAngle) < autoZeroThresholdAngle)) {
            if (straightDriveStartTime == 0L) {
                straightDriveStartTime = now
            } else {
                val elapsed = now - straightDriveStartTime
                if (elapsed > autoZeroDurationMs) {
                    calibrationOffset += (currentAngle * 0.005)
                }
            }
        } else {
            straightDriveStartTime = 0L
        }
    }

    private fun notifyUpdates() {
        val calculatedAngle = currentFusedAngle - calibrationOffset
        val finalAngle = round(calculatedAngle / 0.1) * 0.1
        listener.onLeanAngleUpdate(finalAngle, maxTempLeft, maxTempRight, maxTourLeft, maxTourRight)
        listener.onAccelerationUpdate(maxAcceleration, maxBraking, tourMaxAccel, tourMaxBrake)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAcceleration(event)
        }
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        // Calculate tilt angle from accelerometer (Static Reference)
        // Right tilt must be positive, Left tilt must be negative
        val accelAngle = if (isLandscape) {
            val rotation = try { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation } catch (_: Exception) { Surface.ROTATION_0 }
            val invertSign = if (rotation == Surface.ROTATION_270) 1.0 else -1.0
            // In landscape, tilt is reflected on Y axis
            Math.toDegrees(atan2((-y * invertSign).toDouble(), sqrt(((x * x) + (z * z)).toDouble())))
        } else {
            // In portrait, tilt is reflected on X axis. Gravity on +X means tilted LEFT.
            // So we use -x to make tilted RIGHT positive.
            Math.toDegrees(atan2(-x.toDouble(), sqrt(((y * y) + (z * z)).toDouble())))
        }

        if (sensorStartupCounter < 20) {
            currentFusedAngle = accelAngle
            sensorStartupCounter++
        } else {
            // Apply Complementary Filter: High trust in Gyro, slow drift correction with Accel
            // Weighting only happens when not in a heavy kurve (totalG close to 1.0)
            val totalG = sqrt((x * x + y * y + z * z).toDouble()) / 9.81
            val isStable = abs(totalG - 1.0) < 0.1
            
            if (isStable) {
                currentFusedAngle = filterCoefficient * currentFusedAngle + (1.0 - filterCoefficient) * accelAngle
            }
        }
        processLeanAngle()
    }

    private fun handleGyroscope(event: SensorEvent) {
        if (lastFusionTimestamp == 0L) {
            lastFusionTimestamp = event.timestamp
            return
        }

        val dt = (event.timestamp - lastFusionTimestamp) * 1.0e-9 
        lastFusionTimestamp = event.timestamp

        // Rotation around Z-axis (pointing through screen) is lean for handlebar mounts
        // CCW is positive in Android Gyro (tilting left).
        // We negate it so that tilting RIGHT (CW) results in a POSITIVE velocity.
        val rotationVelocity = -event.values[2].toDouble()

        if (abs(rotationVelocity) < 0.01) return

        currentFusedAngle += Math.toDegrees(rotationVelocity) * dt
        processLeanAngle()
    }

    private fun processLeanAngle() {
        val calculatedAngle = currentFusedAngle - calibrationOffset
        val finalAngle = round(calculatedAngle / 0.1) * 0.1

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
                if (isRecording && ((abs(lastPeakLeanAngle) > 1.0) || (maxAcceleration > 0.1) || (abs(maxBraking) > 0.1))) {
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
        val now = timeProvider()
        if (lastValidTime != 0L) {
            val dist = calculateDistance(lastValidLat, lastValidLon, currentLatitude, currentLongitude)
            val timeSec = (now - lastValidTime) / 1000.0
            if (timeSec > 0) {
                val speedCheck = (dist / timeSec) * 3.6 
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
        val r = 6371000.0 
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
