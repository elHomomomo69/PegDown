package com.dpm.pegdown.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.dpm.pegdown.model.TourLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

class SensorProcessor(
    context: Context,
    private val listener: SensorUpdateListener,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Game Rotation Vector:
     *
     * - verwendet Gyro + Accelerometer-Sensorfusion
     * - benötigt keinen Magnetkompass
     * - wesentlich besser geeignet als eigener Gyro-Integrator
     */
    private val rotationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /**
     * Wird weiterhin ausschließlich für Beschleunigung/Bremsung benutzt.
     */
    private val linearAccelSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val handler =
        Handler(Looper.getMainLooper())

    private var tempResetRunnable: Runnable? = null
    private var accelResetRunnable: Runnable? = null

    private val gpxDateFormat =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    // ========================================================================
    // SETTINGS
    // ========================================================================

    /**
     * Glättung der angezeigten Schräglage.
     *
     * 0.05 = sehr ruhig
     * 0.10 = guter Startwert
     * 0.20 = schneller
     * 0.50 = sehr direkt
     */
    var smoothingAlpha: Double = 0.10

    var resetDurationMillis: Long = 7000L

    // ========================================================================
    // LEAN ANGLE
    // ========================================================================

    /**
     * Aktueller Rohwinkel relativ zur Kalibrierung.
     */
    private var rawLeanAngle = 0.0

    /**
     * Geglätteter Winkel für die Anzeige.
     */
    private var currentFusedAngle = 0.0

    /**
     * Beim Kalibrieren gespeicherte Quaternion.
     */
    private var calibrationQuaternion: Quaternion? = null

    /**
     * Letzte gültige Rotation.
     */
    private var currentQuaternion: Quaternion? = null

    /**
     * Anzahl Sensorwerte nach Start.
     */
    private var sensorStartupCounter = 0

    private val startupSamples = 15

    /**
     * Optionaler kleiner Offset für die Nulllage.
     */
    var calibrationOffset = 0.0
        private set

    private var straightDriveStartTime = 0L
    private val autoZeroMinSpeed = 40.0
    private val autoZeroThresholdAngle = 1.0
    private val autoZeroDurationMs = 15000L

    internal fun checkAutoZero(currentAngle: Double) {
        if (currentSpeedKmH >= autoZeroMinSpeed && abs(currentAngle) < autoZeroThresholdAngle) {
            val now = timeProvider()
            if (straightDriveStartTime == 0L) {
                straightDriveStartTime = now
            } else if (now - straightDriveStartTime > autoZeroDurationMs) {
                calibrationOffset += currentAngle * 0.005
            }
        } else {
            straightDriveStartTime = 0L
        }
    }

    // ========================================================================
    // LEAN PEAKS
    // ========================================================================

    var maxTourLeft = 0.0
    var maxTourRight = 0.0

    private var maxTempLeft = 0.0
    private var maxTempRight = 0.0

    private var lastPeakLeanAngle = 0.0

    // ========================================================================
    // ACCELERATION / BRAKING
    // ========================================================================

    var maxAcceleration = 0.0
    var maxBraking = 0.0

    var tourMaxAccel = 0.0
    var tourMaxBrake = 0.0

    private var smoothedAccel = 0.0
    private var smoothedBrake = 0.0

    private val accelSmoothingAlpha = 0.15

    // ========================================================================
    // EXTERNAL STATE
    // ========================================================================

    var isRecording = false

    var currentLatitude = 0.0
    var currentLongitude = 0.0
    var currentAltitude = 0.0
    var currentSpeedKmH = 0.0

    private var lastValidLat = 0.0
    private var lastValidLon = 0.0
    private var lastValidTime = 0L

    // ========================================================================
    // TIME
    // ========================================================================

    internal var timeProvider: () -> Long = {
        System.currentTimeMillis()
    }

    // ========================================================================
    // START / STOP
    // ========================================================================

    fun start() {

        sensorStartupCounter = 0

        currentQuaternion = null
        rawLeanAngle = 0.0
        currentFusedAngle = 0.0

        /*
         * Wir benötigen für die Schräglage ausschließlich den
         * Game Rotation Vector.
         */
        rotationSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        /*
         * Beschleunigung / Bremsung weiterhin separat.
         */
        linearAccelSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    // ========================================================================
    // CALIBRATION
    // ========================================================================

    /**
     * Setzt die aktuelle Position auf 0°.
     *
     * Wichtig:
     *
     * Es wird die komplette aktuelle Orientierung gespeichert.
     *
     * Dadurch ist es egal, ob das Handy z.B. 5° nach vorne oder
     * 3° seitlich schief in der Halterung sitzt.
     */
    fun calibrate() {

        val current = currentQuaternion

        if (current != null) {

            calibrationQuaternion = current.copy()

            calibrationOffset = 0.0
            straightDriveStartTime = 0L

            rawLeanAngle = 0.0
            currentFusedAngle = 0.0

            /*
             * Peaks sollen nicht durch die Kalibrierung verfälscht werden.
             */
            maxTempLeft = 0.0
            maxTempRight = 0.0
            lastPeakLeanAngle = 0.0
        }

        notifyUpdates()
    }

    // ========================================================================
    // TOUR RESET
    // ========================================================================

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

        accelResetRunnable?.let {
            handler.removeCallbacks(it)
        }

        tempResetRunnable?.let {
            handler.removeCallbacks(it)
        }

        notifyUpdates()
    }

    // ========================================================================
    // SENSOR CALLBACK
    // ========================================================================

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // Nicht benötigt.
    }

    override fun onSensorChanged(event: SensorEvent?) {

        if (event == null) return

        when (event.sensor.type) {

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                handleRotationVector(event)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                handleLinearAcceleration(event)
            }
        }
    }

    // ========================================================================
    // ROTATION VECTOR
    // ========================================================================

    private fun handleRotationVector(event: SensorEvent) {

        val quaternion =
            quaternionFromRotationVector(event.values)
                ?: return

        currentQuaternion = quaternion

        /*
         * Kurze Startphase.
         *
         * Dadurch vermeiden wir, dass direkt beim Start ein einzelner
         * Sensor-Ausreißer als Peak gespeichert wird.
         */
        if (sensorStartupCounter < startupSamples) {

            sensorStartupCounter++

            /*
             * Noch keine automatische Kalibrierung hier!
             *
             * Der Benutzer soll mit dem Kalibrierknopf den tatsächlichen
             * Nullpunkt bestimmen.
             */
            return
        }

        /*
         * Falls noch nie kalibriert wurde, setzen wir beim ersten gültigen
         * Zustand eine Referenz.
         *
         * Der Benutzer kann danach jederzeit erneut kalibrieren.
         */
        if (calibrationQuaternion == null) {
            calibrationQuaternion = quaternion.copy()

            rawLeanAngle = 0.0
            currentFusedAngle = 0.0

            return
        }

        val relativeQuaternion =
            calculateRelativeQuaternion(
                calibrationQuaternion!!,
                quaternion
            )

        /*
         * WICHTIG:
         *
         * Unsere Schräglage ist die Rotation um die Z-Achse des Displays.
         *
         * Wir extrahieren deshalb ausschließlich den Z-Twist.
         *
         * Yaw und Pitch werden dadurch nicht einfach als Schräglage
         * interpretiert.
         */
        val calculatedLean =
            extractZTwistAngle(relativeQuaternion)

        rawLeanAngle = calculatedLean

        /*
         * Kleine Sensorbewegungen unterhalb 0,15° ignorieren.
         */
        val targetAngle =
            if (abs(rawLeanAngle) < 0.15) {
                0.0
            } else {
                rawLeanAngle
            }

        /*
         * Anzeige glätten.
         */
        val alpha =
            smoothingAlpha.coerceIn(0.02, 0.50)

        currentFusedAngle +=
            alpha * (targetAngle - currentFusedAngle)

        processLeanAngle()
    }

    // ========================================================================
    // QUATERNION AUS ROTATION VECTOR
    // ========================================================================

    /**
     * Android liefert beim TYPE_GAME_ROTATION_VECTOR:
     *
     * x, y, z
     *
     * und bei manchen Geräten zusätzlich:
     *
     * w
     *
     * Falls w nicht enthalten ist, berechnen wir es.
     */
    private fun quaternionFromRotationVector(
        values: FloatArray
    ): Quaternion? {

        if (values.size < 3) {
            return null
        }

        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()

        val w =
            if (values.size >= 4) {
                values[3].toDouble()
            } else {

                val ww =
                    1.0 - x * x - y * y - z * z

                if (ww > 0.0) {
                    sqrt(ww)
                } else {
                    0.0
                }
            }

        val q =
            Quaternion(
                w = w,
                x = x,
                y = y,
                z = z
            )

        return q.normalized()
    }

    // ========================================================================
    // RELATIVE ROTATION
    // ========================================================================

    /**
     * Berechnet:
     *
     *     calibration^-1 * current
     *
     * Das Ergebnis beschreibt also die Rotation relativ zum beim
     * Kalibrieren gespeicherten Zustand.
     */
    private fun calculateRelativeQuaternion(
        calibration: Quaternion,
        current: Quaternion
    ): Quaternion {

        val inverse =
            calibration.inverse()

        return (
                inverse * current
                ).normalized()
    }

    // ========================================================================
    // Z TWIST
    // ========================================================================

    /**
     * Extrahiert ausschließlich die Rotation um Z.
     *
     * Das ist bei deiner Montage die gewünschte Schräglagenachse.
     *
     * Android:
     *
     *   X = rechts
     *   Y = oben
     *   Z = aus dem Display heraus
     *
     * Für die Anzeige:
     *
     *   links  = NEGATIV
     *   rechts = POSITIV
     *
     * Deshalb drehen wir das mathematische Vorzeichen entsprechend.
     */
    private fun extractZTwistAngle(
        q: Quaternion
    ): Double {

        /*
         * Twist um Z:
         *
         * angle = atan2(
         *     2 * (w*z),
         *     1 - 2*z²
         * )
         */
        val angle =
            atan2(
                2.0 * q.w * q.z,
                1.0 - 2.0 * q.z * q.z
            )

        /*
         * Für die von dir beschriebene Montage:
         *
         * links  = negativ
         * rechts = positiv
         *
         * Das Vorzeichen wird deshalb invertiert.
         */
        return -Math.toDegrees(angle)
    }

    // ========================================================================
    // LEAN PROCESSING
    // ========================================================================

    private fun processLeanAngle() {

        val calculatedAngle =
            currentFusedAngle - calibrationOffset

        val finalAngle =
            round(calculatedAngle / 0.1) * 0.1

        checkAutoZero(calculatedAngle)

        /*
         * Tour-Maxima
         */
        if (finalAngle < maxTourLeft) {
            maxTourLeft = finalAngle
        }

        if (finalAngle > maxTourRight) {
            maxTourRight = finalAngle
        }

        /*
         * Temporäre Peaks
         */
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

            lastPeakLeanAngle =
                if (abs(maxTempLeft) > abs(maxTempRight)) {
                    maxTempLeft
                } else {
                    maxTempRight
                }

            tempResetRunnable?.let {
                handler.removeCallbacks(it)
            }

            tempResetRunnable = Runnable {

                if (
                    isRecording &&
                    (
                            abs(lastPeakLeanAngle) > 1.0 ||
                                    maxAcceleration > 0.1 ||
                                    abs(maxBraking) > 0.1
                            )
                ) {
                    recordEntry()
                }

                maxTempLeft = 0.0
                maxTempRight = 0.0

                lastPeakLeanAngle = 0.0

                maxAcceleration = 0.0
                maxBraking = 0.0

                notifyUpdates()
            }

            handler.postDelayed(
                tempResetRunnable!!,
                resetDurationMillis
            )
        }

        notifyUpdates()
    }

    // ========================================================================
    // LINEAR ACCELERATION
    // ========================================================================

    private fun handleLinearAcceleration(
        event: SensorEvent
    ) {

        val y = event.values[1]

        /*
         * Die Vorwärtsachse hängt davon ab, wie dein Handy im Landscape
         * montiert ist.
         *
         * Bei deiner beschriebenen Montage:
         *
         * Rückseite zeigt nach vorne.
         *
         * Die bisherige App verwendet:
         *
         * Landscape -> Y
         *
         * Das behalten wir zunächst für Beschleunigung/Bremsung bei.
         */
        val rawAccel = y

        var currentForwardG =
            rawAccel / 9.81

        if (abs(currentForwardG) < 0.05) {
            currentForwardG = 0.0
        }

        if (currentForwardG >= 0.0) {

            smoothedAccel +=
                accelSmoothingAlpha *
                        (currentForwardG - smoothedAccel)

            smoothedBrake = 0.0

        } else {

            smoothedBrake +=
                accelSmoothingAlpha *
                        (currentForwardG - smoothedBrake)

            smoothedAccel = 0.0
        }

        var newAccelPeak = false

        /*
         * Beschleunigung
         */
        if (
            smoothedAccel > maxAcceleration &&
            smoothedAccel < 2.0
        ) {
            maxAcceleration = smoothedAccel
            newAccelPeak = true
        }

        /*
         * Bremsung
         */
        if (
            smoothedBrake < maxBraking &&
            smoothedBrake > -2.0
        ) {
            maxBraking = smoothedBrake
            newAccelPeak = true
        }

        /*
         * Tour-Maximum
         */
        if (maxAcceleration > tourMaxAccel) {
            tourMaxAccel = maxAcceleration
        }

        if (maxBraking < tourMaxBrake) {
            tourMaxBrake = maxBraking
        }

        if (newAccelPeak) {

            val peakAccelToSave =
                maxAcceleration

            val peakBrakeToSave =
                maxBraking

            accelResetRunnable?.let {
                handler.removeCallbacks(it)
            }

            accelResetRunnable = Runnable {

                if (
                    isRecording &&
                    (
                            abs(lastPeakLeanAngle) > 1.0 ||
                                    peakAccelToSave > 0.1 ||
                                    abs(peakBrakeToSave) > 0.1
                            )
                ) {
                    recordEntry()
                }

                maxAcceleration = 0.0
                maxBraking = 0.0

                notifyUpdates()
            }

            handler.postDelayed(
                accelResetRunnable!!,
                resetDurationMillis
            )
        }

        notifyUpdates()
    }

    // ========================================================================
    // UI
    // ========================================================================

    private fun notifyUpdates() {

        val calculatedAngle =
            currentFusedAngle - calibrationOffset

        val finalAngle =
            round(calculatedAngle / 0.1) * 0.1

        listener.onLeanAngleUpdate(
            finalAngle,
            maxTempLeft,
            maxTempRight,
            maxTourLeft,
            maxTourRight
        )

        listener.onAccelerationUpdate(
            maxAcceleration,
            maxBraking,
            tourMaxAccel,
            tourMaxBrake
        )
    }

    // ========================================================================
    // RECORDING
    // ========================================================================

    fun recordCurrentState() {

        if (isRecording) {
            recordEntry()
        }
    }

    private fun recordEntry() {

        val now =
            timeProvider()

        if (lastValidTime != 0L) {

            val dist =
                calculateDistance(
                    lastValidLat,
                    lastValidLon,
                    currentLatitude,
                    currentLongitude
                )

            val timeSec =
                (now - lastValidTime) / 1000.0

            if (timeSec > 0) {

                val speedCheck =
                    (dist / timeSec) * 3.6

                if (speedCheck > 300.0) {
                    return
                }
            }
        }

        lastValidLat = currentLatitude
        lastValidLon = currentLongitude
        lastValidTime = now

        val leftVal =
            if (lastPeakLeanAngle < 0) {
                abs(lastPeakLeanAngle)
            } else {
                0.0
            }

        val rightVal =
            if (lastPeakLeanAngle > 0) {
                abs(lastPeakLeanAngle)
            } else {
                0.0
            }

        val entry =
            TourLogEntry(
                timestamp =
                    gpxDateFormat.format(
                        Date(now)
                    ),
                leanAngleLeft = leftVal,
                leanAngleRight = rightVal,
                acceleration = maxAcceleration,
                braking = maxBraking,
                lat = currentLatitude,
                lon = currentLongitude,
                altitude = currentAltitude,
                speed = currentSpeedKmH
            )

        listener.onPeakRecorded(entry)
    }

    // ========================================================================
    // GPS DISTANCE
    // ========================================================================

    private fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {

        val r = 6371000.0

        val dLat =
            Math.toRadians(lat2 - lat1)

        val dLon =
            Math.toRadians(lon2 - lon1)

        val a =
            sin(dLat / 2).pow(2.0) +
                    cos(Math.toRadians(lat1)) *
                    cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2.0)

        val c =
            2.0 * atan2(
                sqrt(a),
                sqrt(1.0 - a)
            )

        return r * c
    }

    // ========================================================================
    // QUATERNION
    // ========================================================================

    private data class Quaternion(
        val w: Double,
        val x: Double,
        val y: Double,
        val z: Double
    ) {

        fun normalized(): Quaternion {

            val length =
                sqrt(
                    w * w +
                            x * x +
                            y * y +
                            z * z
                )

            if (length < 1e-12) {
                return Quaternion(
                    1.0,
                    0.0,
                    0.0,
                    0.0
                )
            }

            return Quaternion(
                w / length,
                x / length,
                y / length,
                z / length
            )
        }

        fun inverse(): Quaternion {

            val normSquared =
                w * w +
                        x * x +
                        y * y +
                        z * z

            if (normSquared < 1e-12) {
                return Quaternion(
                    1.0,
                    0.0,
                    0.0,
                    0.0
                )
            }

            return Quaternion(
                w = w / normSquared,
                x = -x / normSquared,
                y = -y / normSquared,
                z = -z / normSquared
            )
        }

        operator fun times(
            other: Quaternion
        ): Quaternion {

            return Quaternion(

                w =
                    w * other.w -
                            x * other.x -
                            y * other.y -
                            z * other.z,

                x =
                    w * other.x +
                            x * other.w +
                            y * other.z -
                            z * other.y,

                y =
                    w * other.y -
                            x * other.z +
                            y * other.w +
                            z * other.x,

                z =
                    w * other.z +
                            x * other.y -
                            y * other.x +
                            z * other.w
            )
        }
    }
}