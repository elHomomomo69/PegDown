package com.dpm.pegdown.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
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
    private val context: Context,
    private val listener: SensorUpdateListener,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationSensor =
        sensorManager.getDefaultSensor(
            Sensor.TYPE_GAME_ROTATION_VECTOR
        )

    private val accelerometerSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroscopeSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val magnetometerSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val linearAccelSensor =
        sensorManager.getDefaultSensor(
            Sensor.TYPE_LINEAR_ACCELERATION
        )

    private val handler =
        Handler(Looper.getMainLooper())

    // ========================================================================
    // PEAK WINDOW
    // ========================================================================

    /**
     * Gemeinsames Zeitfenster für Lean-, Beschleunigungs- und Bremspeaks.
     *
     * Wichtig:
     *
     * Es gibt bewusst nur EINEN Timer.
     *
     * Dadurch kann ein einzelnes Ereignis nicht durch getrennte Lean- und
     * Beschleunigungs-Timer mehrfach aufgezeichnet werden.
     */
    private var peakResetRunnable: Runnable? = null

    // ========================================================================
    // GPX / TIME
    // ========================================================================

    private val gpxDateFormat =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    // ========================================================================
    // SETTINGS
    // ========================================================================

    var smoothingAlpha: Double = 0.10

    var resetDurationMillis: Long = 7000L

    /**
     * Anzahl der Samples während der Kalibrierung.
     */
    private val calibrationSampleCount = 30

    /**
     * Anzahl der Samples, die nach dem Start ignoriert werden.
     */
    private val startupSamples = 15

    // ========================================================================
    // LEAN
    // ========================================================================

    private var rawLeanAngle = 0.0

    private var currentFusedAngle = 0.0

    private data class LeanTimestampSample(
        val timestampNanos: Long,
        val leanDegrees: Double,
        val calibrated: Boolean,
    )

    private val leanTimestampSamples = ArrayDeque<LeanTimestampSample>()
    private val leanTimestampSamplesLock = Any()
    private val leanHistoryDurationNanos = 15_000_000_000L
    private val maxLeanHistorySamples = 1_500
    private val maxLeanInterpolationGapNanos = 500_000_000L
    private val maxLeanNearestSampleAgeNanos = 250_000_000L

    private var currentQuaternion: Quaternion? = null

    /**
     * Gemittelte Orientierung während der Kalibrierung.
     */
    private var calibrationQuaternion: Quaternion? = null

    /**
     * Schwerkraftrichtung im Telefonrahmen zum Kalibrierzeitpunkt.
     * Vergleiche dieser Richtung sind unabhängig von der Yaw-Drehung.
     */
    private var calibrationGravity: Vector3? = null

    /**
     * Relative Rotation:
     *
     * calibration^-1 * current
     */
    private var relativeQuaternion: Quaternion? = null

    private var sensorStartupCounter = 0

    /**
     * Legacy-Wert.
     *
     * Die eigentliche Kalibrierung erfolgt über calibrationQuaternion.
     */
    var calibrationOffset = 0.0
        private set

    /**
     * true, sobald eine gültige Kalibrierungsreferenz vorhanden ist.
     */
    val isCalibrated: Boolean
        get() = calibrationQuaternion != null

    /**
     * true, sobald der Rotation-Vector-Sensor mindestens einen Wert geliefert hat.
     */
    val hasSensorData: Boolean
        get() = currentQuaternion != null

    /**
     * true, solange gerade Kalibrierungs-Samples gesammelt werden.
     */
    val isCalibrationInProgress: Boolean
        get() = calibrationInProgress

    // ========================================================================
    // CALIBRATION STATE
    // ========================================================================

    private var calibrationInProgress = false

    private val calibrationQuaternions =
        mutableListOf<Quaternion>()

    private var calibrationAccelX = 0.0
    private var calibrationAccelY = 0.0
    private var calibrationAccelZ = 0.0
    private var calibrationAccelSamples = 0

    /**
     * 0.0 ... 1.0
     */
    var calibrationProgress: Double = 0.0
        private set

    // ========================================================================
    // LEAN PEAKS
    // ========================================================================

    var maxTourLeft = 0.0
        private set

    var maxTourRight = 0.0
        private set

    private var maxTempLeft = 0.0
    private var maxTempRight = 0.0

    /**
     * Größter Lean-Peak im aktuellen gemeinsamen Peak-Fenster.
     *
     * Negativ = links
     * Positiv = rechts
     */
    private var lastPeakLeanAngle = 0.0

    // ========================================================================
    // ACCELERATION / BRAKING
    // ========================================================================

    var maxAcceleration = 0.0
        private set

    var maxBraking = 0.0
        private set

    var tourMaxAccel = 0.0
        private set

    var tourMaxBrake = 0.0
        private set

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

    fun currentLeanAngleDegrees(): Double = currentFusedAngle - calibrationOffset

    /** Returns the lean estimate aligned to a location fix's monotonic timestamp. */
    fun leanAngleAt(timestampNanos: Long): Double? {
        if (timestampNanos <= 0L) return null

        val samples = synchronized(leanTimestampSamplesLock) {
            leanTimestampSamples.toList()
        }
        if (samples.isEmpty()) return null

        val afterIndex = samples.indexOfFirst { it.timestampNanos >= timestampNanos }
        if (afterIndex > 0) {
            val before = samples[afterIndex - 1]
            val after = samples[afterIndex]
            val gap = after.timestampNanos - before.timestampNanos
            if (before.calibrated && after.calibrated && gap in 1..maxLeanInterpolationGapNanos) {
                val fraction = (timestampNanos - before.timestampNanos).toDouble() / gap
                return (before.leanDegrees + (after.leanDegrees - before.leanDegrees) * fraction)
                    .coerceIn(-65.0, 65.0)
            }
        }

        val nearest = samples.minByOrNull { abs(it.timestampNanos - timestampNanos) } ?: return null
        return if (nearest.calibrated && abs(nearest.timestampNanos - timestampNanos) <= maxLeanNearestSampleAgeNanos) {
            nearest.leanDegrees.coerceIn(-65.0, 65.0)
        } else {
            null
        }
    }

    /**
     * GPS-Daten des letzten tatsächlich gespeicherten Eintrags.
     */
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
        relativeQuaternion = null

        calibrationInProgress = false
        calibrationProgress = 0.0
        calibrationQuaternions.clear()

        rawLeanAngle = 0.0
        currentFusedAngle = 0.0

        /*
         * Die bestehende Kalibrierung bleibt erhalten.
         *
         * Das ist wichtig, wenn die Activity bzw. der Service kurz neu
         * gestartet wird.
         */

        listOfNotNull(
            accelerometerSensor,
            gyroscopeSensor,
            magnetometerSensor,
            rotationSensor,
            linearAccelSensor,
        ).forEach { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
            )
        }

    }

    fun stop() {

        sensorManager.unregisterListener(this)

        peakResetRunnable?.let {
            handler.removeCallbacks(it)
        }

        peakResetRunnable = null
    }

    // ========================================================================
    // CALIBRATION
    // ========================================================================

    /**
     * Startet eine Mehrfach-Sample-Kalibrierung.
     *
     * Der aktuelle Wert wird NICHT sofort als Nullpunkt übernommen.
     */
    fun calibrate() {

        /*
         * Ohne Rotation Vector können wir nicht sinnvoll kalibrieren.
         */
        if (currentQuaternion == null) {
            return
        }

        /*
         * Falls bereits eine Kalibrierung läuft, nicht erneut starten.
         */
        if (calibrationInProgress) {
            return
        }

        calibrationQuaternions.clear()

        calibrationAccelX = 0.0
        calibrationAccelY = 0.0
        calibrationAccelZ = 0.0
        calibrationAccelSamples = 0

        calibrationProgress = 0.0
        calibrationInProgress = true

        /*
         * Alte temporäre Peaks löschen.
         */
        maxTempLeft = 0.0
        maxTempRight = 0.0
        lastPeakLeanAngle = 0.0

        maxAcceleration = 0.0
        maxBraking = 0.0

        cancelPeakTimer()

        notifyUpdates()
    }

    /**
     * Wird automatisch aufgerufen, sobald genügend Samples gesammelt wurden.
     */
    private fun finishCalibration() {

        if (calibrationQuaternions.isEmpty()) {

            calibrationInProgress = false
            calibrationProgress = 0.0

            notifyUpdates()

            return
        }

        /*
         * Quaternionen mitteln.
         *
         * q und -q beschreiben dieselbe Rotation.
         *
         * Deshalb werden alle Quaternionen vor dem Mitteln auf dieselbe
         * Hemisphäre ausgerichtet.
         */
        val reference =
            calibrationQuaternions.first()

        var sumW = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0

        for (sample in calibrationQuaternions) {

            val aligned =
                if (reference.dot(sample) < 0.0) {
                    -sample
                } else {
                    sample
                }

            sumW += aligned.w
            sumX += aligned.x
            sumY += aligned.y
            sumZ += aligned.z
        }

        val count =
            calibrationQuaternions.size.toDouble()

        val averaged =
            Quaternion(
                w = sumW / count,
                x = sumX / count,
                y = sumY / count,
                z = sumZ / count
            ).normalized()

        calibrationQuaternion = averaged
        calibrationGravity = gravityInDeviceFrame(averaged)
        /*
         * Die Schwerkraftrichtung und das Gyroskop sind jetzt kalibriert.
         */
        calibrationOffset = 0.0

        rawLeanAngle = 0.0
        currentFusedAngle = 0.0
        relativeQuaternion = null

        /*
         * Temporäre Peaks der alten Orientierung löschen.
         */
        maxTempLeft = 0.0
        maxTempRight = 0.0
        lastPeakLeanAngle = 0.0

        maxAcceleration = 0.0
        maxBraking = 0.0

        smoothedAccel = 0.0
        smoothedBrake = 0.0

        cancelPeakTimer()

        calibrationInProgress = false
        calibrationProgress = 1.0

        calibrationQuaternions.clear()

        notifyUpdates()

        /*
         * Fortschrittsanzeige nach kurzer Zeit zurücksetzen.
         */
        handler.postDelayed(
            {
                calibrationProgress = 0.0
            },
            500
        )
    }

    // ========================================================================
    // TOUR RESET
    // ========================================================================

    fun resetTour() {

        /*
         * Tour-Maxima.
         */
        maxTourLeft = 0.0
        maxTourRight = 0.0

        tourMaxAccel = 0.0
        tourMaxBrake = 0.0

        /*
         * Temporäre Peaks.
         */
        maxTempLeft = 0.0
        maxTempRight = 0.0

        lastPeakLeanAngle = 0.0

        maxAcceleration = 0.0
        maxBraking = 0.0

        /*
         * Glättungszustand zurücksetzen.
         */
        smoothedAccel = 0.0
        smoothedBrake = 0.0

        /*
         * Gemeinsames Peak-Fenster abbrechen.
         */
        cancelPeakTimer()

        /*
         * GPS-Historie des alten Tourabschnitts nicht in die neue Tour
         * übernehmen.
         */
        lastValidLat = currentLatitude
        lastValidLon = currentLongitude
        lastValidTime = 0L

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

    override fun onSensorChanged(
        event: SensorEvent?
    ) {

        if (event == null) return

        when (event.sensor.type) {

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                handleRotationVector(event.values)
                recordLeanTimestampSample(event.timestamp)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                handleLinearAcceleration(event)
            }

        }

        if (isRecording) {
            listener.onDiagnosticSensorSample(
                sensor = event.sensor,
                timestampNanos = event.timestamp,
                accuracy = event.accuracy,
                values = event.values.copyOf(),
                estimatedLeanDegrees = currentFusedAngle - calibrationOffset,
                calibrated = isCalibrated,
            )
        }
    }

    private fun recordLeanTimestampSample(timestampNanos: Long) {
        if (timestampNanos <= 0L) return
        val sample = LeanTimestampSample(
            timestampNanos = timestampNanos,
            leanDegrees = (currentFusedAngle - calibrationOffset).coerceIn(-65.0, 65.0),
            calibrated = isCalibrated,
        )
        synchronized(leanTimestampSamplesLock) {
            if (leanTimestampSamples.lastOrNull()?.timestampNanos?.let { timestampNanos <= it } == true) return
            leanTimestampSamples.addLast(sample)
            val cutoff = timestampNanos - leanHistoryDurationNanos
            while (leanTimestampSamples.isNotEmpty() &&
                (leanTimestampSamples.first().timestampNanos < cutoff || leanTimestampSamples.size > maxLeanHistorySamples)
            ) {
                leanTimestampSamples.removeFirst()
            }
        }
    }

    fun diagnosticSensors(): List<Sensor> =
        listOfNotNull(
            accelerometerSensor,
            gyroscopeSensor,
            magnetometerSensor,
            rotationSensor,
            linearAccelSensor,
        )

    fun diagnosticSessionMetadata(): String {
        val calibration = calibrationQuaternion
        val gravity = calibrationGravity
        return listOf(
            "manufacturer=" + Build.MANUFACTURER,
            "model=" + Build.MODEL,
            "android_sdk=" + Build.VERSION.SDK_INT,
            "calibrated=" + isCalibrated,
            "calibration_quaternion_wxyz=" + calibration?.let {
                listOf(it.w, it.x, it.y, it.z).joinToString(";")
            }.orEmpty(),
            "calibration_gravity_xyz=" + gravity?.let {
                listOf(it.x, it.y, it.z).joinToString(";")
            }.orEmpty(),
            "display_smoothing_alpha=" + smoothingAlpha,
            "mount_axis_assumption=device_z_along_motorcycle_forward",
            "imu_sampling_hint_us=20000",
            "gps_interval_diagnostic_ms=500",
            "gps_min_distance_diagnostic_m=0",
        ).joinToString("; ")
    }

    // ========================================================================
    // ROTATION VECTOR
    // ========================================================================

    internal fun handleRotationVector(values: FloatArray) {

        val quaternion =
            quaternionFromRotationVector(values) ?: return

        currentQuaternion = quaternion

        /*
         * Startup.
         *
         * Die ersten Samples werden nicht verarbeitet, damit sich der
         * Sensor nach dem Start stabilisieren kann.
         */
        if (sensorStartupCounter < startupSamples) {

            sensorStartupCounter++

            return
        }

        // --------------------------------------------------------------------
        // KALIBRIERUNG
        // --------------------------------------------------------------------

        if (calibrationInProgress) {

            calibrationQuaternions.add(
                quaternion
            )

            calibrationProgress =
                (
                        calibrationQuaternions.size.toDouble() /
                                calibrationSampleCount.toDouble()
                        ).coerceIn(
                        0.0,
                        1.0
                    )

            if (
                calibrationQuaternions.size >=
                calibrationSampleCount
            ) {
                finishCalibration()
            }

            return
        }

        /*
         * Noch nicht kalibriert:
         *
         * Es wird KEINE automatische Kalibrierung mehr durchgeführt.
         *
         * Dadurch bleibt isCalibrated() bis zur tatsächlichen Benutzer-
         * Kalibrierung korrekt false.
         */
        if (calibrationQuaternion == null) {

            rawLeanAngle = 0.0
            currentFusedAngle = 0.0

            notifyUpdates()

            return
        }

        // --------------------------------------------------------------------
        // RELATIVE ROTATION
        // --------------------------------------------------------------------

        /*
         * Schräglage aus der Schwerkraftrichtung in der Telefonebene.
         * Die Android-Rotation-Vector-Fusion kombiniert Gyroskop und
         * Beschleunigung und vermeidet eine eigene, driftende Gyro-Integration.
         */
        val referenceGravity = calibrationGravity ?: return
        val currentGravity = gravityInDeviceFrame(quaternion)
        val calculatedLean =
            extractBankAngle(referenceGravity, currentGravity)

        rawLeanAngle = calculatedLean
        val targetAngle =
            if (abs(rawLeanAngle) < 0.20) 0.0 else rawLeanAngle
        val limitedAngle = targetAngle.coerceIn(-65.0, 65.0)
        val alpha = smoothingAlpha.coerceIn(0.01, 0.50)
        currentFusedAngle += alpha * (limitedAngle - currentFusedAngle)
        processLeanAngle()
    }

    // ========================================================================
    // QUATERNION FROM ROTATION VECTOR
    // ========================================================================

    private fun quaternionFromRotationVector(
        values: FloatArray
    ): Quaternion? {

        if (values.size < 3) {
            return null
        }

        val x =
            values[0].toDouble()

        val y =
            values[1].toDouble()

        val z =
            values[2].toDouble()

        val w =
            if (values.size >= 4) {

                values[3].toDouble()

            } else {

                val ww =
                    1.0 -
                            x * x -
                            y * y -
                            z * z

                if (ww > 0.0) {
                    sqrt(ww)
                } else {
                    0.0
                }
            }

        return Quaternion(
            w = w,
            x = x,
            y = y,
            z = z
        ).normalized()
    }

    // ========================================================================
    // RELATIVE QUATERNION
    // ========================================================================

    private fun calculateRelativeQuaternion(
        calibration: Quaternion,
        current: Quaternion
    ): Quaternion {

        return (
                calibration.inverse() *
                        current
                ).normalized()
    }

    // ========================================================================
    // GRAVITY-BASED BANK ANGLE
    // ========================================================================

    /**
     * Androids Rotation Vector beschreibt die Geräteorientierung relativ
     * zum Weltkoordinatensystem. Welt-Z zeigt nach oben. Invertieren der
     * Orientierung liefert die Schwerkraftrichtung im Gerätekoordinatensystem.
     */
    private fun gravityInDeviceFrame(orientation: Quaternion): Vector3 =
        rotateVector(
            orientation.inverse(),
            Vector3(0.0, 0.0, 1.0)
        )

    /**
     * Winkel zwischen den projizierten Schwerkraftvektoren um die Geräte-Z-Achse.
     * Bei der vorgesehenen Montage liegt diese Achse in Fahrtrichtung.
     */
    private fun extractBankAngle(reference: Vector3, current: Vector3): Double {
        val referenceLength = sqrt(reference.x * reference.x + reference.y * reference.y)
        val currentLength = sqrt(current.x * current.x + current.y * current.y)
        if (referenceLength < 1e-6 || currentLength < 1e-6) return 0.0

        val refX = reference.x / referenceLength
        val refY = reference.y / referenceLength
        val curX = current.x / currentLength
        val curY = current.y / currentLength

        val crossZ = refX * curY - refY * curX
        val dot = refX * curX + refY * curY
        // Bewahrt das Vorzeichen der bisherigen Z-Twist-Berechnung.
        return Math.toDegrees(atan2(crossZ, dot))
    }

    // ========================================================================
    // LINEAR ACCELERATION / BRAKING
    // ========================================================================

    private fun handleLinearAcceleration(
        event: SensorEvent
    ) {

        val x =
            event.values[0].toDouble()

        val y =
            event.values[1].toDouble()

        val z =
            event.values[2].toDouble()

        /*
         * Während der Kalibrierung Werte sammeln.
         *
         * Aktuell werden sie nur erfasst. Die Quaternion-Kalibrierung
         * verwendet sie nicht als mathematischen Teil des Nullpunkts.
         */
        if (calibrationInProgress) {

            calibrationAccelX += x
            calibrationAccelY += y
            calibrationAccelZ += z

            calibrationAccelSamples++

            return
        }

        val calibration =
            calibrationQuaternion

        val forwardG: Double

        if (calibration != null) {

            val acceleration =
                Vector3(
                    x,
                    y,
                    z
                )

            val current =
                currentQuaternion

            if (current != null) {

                val relative =
                    calculateRelativeQuaternion(
                        calibration,
                        current
                    )

                val accelerationInCalibrationFrame =
                    rotateVector(
                        relative,
                        acceleration
                    )

                /*
                 * Montage:
                 *
                 * Rückseite des Telefons zeigt nach vorne.
                 * Android +Z zeigt aus dem Display heraus.
                 *
                 * Daher:
                 *
                 * -Z = Fahrtrichtung.
                 */
                forwardG =
                    -accelerationInCalibrationFrame.z /
                            9.81

            } else {

                forwardG =
                    -z / 9.81
            }

        } else {

            /*
             * Vor der ersten Benutzerkalibrierung wird keine
             * Beschleunigung verarbeitet.
             *
             * Dadurch entstehen keine falschen Recording-Peaks.
             */
            return
        }

        processAcceleration(
            forwardG
        )
    }

    // ========================================================================
    // ACCELERATION PROCESSING
    // ========================================================================

    private fun processAcceleration(
        inputG: Double
    ) {

        var currentForwardG =
            inputG

        /*
         * Kleine Bewegungen ignorieren.
         */
        if (abs(currentForwardG) < 0.05) {
            currentForwardG = 0.0
        }

        /*
         * Sensor-Ausreißer begrenzen.
         */
        currentForwardG =
            currentForwardG.coerceIn(
                -2.0,
                2.0
            )

        /*
         * Beschleunigung.
         */
        if (currentForwardG >= 0.0) {

            smoothedAccel +=
                accelSmoothingAlpha *
                        (
                                currentForwardG -
                                        smoothedAccel
                                )

            smoothedBrake = 0.0

        } else {

            smoothedBrake +=
                accelSmoothingAlpha *
                        (
                                currentForwardG -
                                        smoothedBrake
                                )

            smoothedAccel = 0.0
        }

        var newPeak = false

        /*
         * Beschleunigungspeak.
         */
        if (
            smoothedAccel >
            maxAcceleration
        ) {

            maxAcceleration =
                smoothedAccel

            newPeak = true
        }

        /*
         * Bremspeak.
         */
        if (
            smoothedBrake <
            maxBraking
        ) {

            maxBraking =
                smoothedBrake

            newPeak = true
        }

        /*
         * Tour-Maximum.
         */
        if (
            maxAcceleration >
            tourMaxAccel
        ) {
            tourMaxAccel =
                maxAcceleration
        }

        if (
            maxBraking <
            tourMaxBrake
        ) {
            tourMaxBrake =
                maxBraking
        }

        /*
         * Jeder neue Peak verlängert das gemeinsame Fenster.
         */
        if (newPeak) {
            schedulePeakReset()
        }

        notifyUpdates()
    }

    // ========================================================================
    // LEAN PROCESSING
    // ========================================================================

    private fun processLeanAngle() {

        val calculatedAngle =
            currentFusedAngle -
                    calibrationOffset

        val finalAngle =
            round(
                calculatedAngle / 0.1
            ) * 0.1

        /*
         * Tour-Maxima.
         */
        if (finalAngle < maxTourLeft) {

            maxTourLeft =
                finalAngle
        }

        if (finalAngle > maxTourRight) {

            maxTourRight =
                finalAngle
        }

        /*
         * Temporäre Peaks.
         */
        var newPeak = false

        if (finalAngle < maxTempLeft) {

            maxTempLeft =
                finalAngle

            newPeak = true
        }

        if (finalAngle > maxTempRight) {

            maxTempRight =
                finalAngle

            newPeak = true
        }

        if (newPeak) {

            lastPeakLeanAngle =
                if (
                    abs(maxTempLeft) >
                    abs(maxTempRight)
                ) {
                    maxTempLeft
                } else {
                    maxTempRight
                }

            /*
             * Gemeinsames Peak-Fenster.
             */
            schedulePeakReset()
        }

        notifyUpdates()
    }

    // ========================================================================
    // COMMON PEAK WINDOW
    // ========================================================================

    /**
     * Startet bzw. verlängert das gemeinsame Peak-Fenster.
     *
     * Während dieses Fensters werden Lean-, Beschleunigungs- und
     * Bremswerte gemeinsam gesammelt.
     *
     * Kommt ein neuer Peak hinzu, beginnt das Zeitfenster erneut.
     */
    private fun schedulePeakReset() {

        peakResetRunnable?.let {
            handler.removeCallbacks(it)
        }

        val delay =
            resetDurationMillis.coerceAtLeast(
                1000L
            )

        peakResetRunnable =
            Runnable {

                /*
                 * Nur speichern, wenn tatsächlich aufgezeichnet wird.
                 */
                if (isRecording) {

                    val hasLeanPeak =
                        abs(lastPeakLeanAngle) > 1.0

                    val hasAccelPeak =
                        maxAcceleration > 0.1

                    val hasBrakePeak =
                        abs(maxBraking) > 0.1

                    if (
                        hasLeanPeak ||
                        hasAccelPeak ||
                        hasBrakePeak
                    ) {
                        recordEntry()
                    }
                }

                /*
                 * Temporäre Werte des Fensters zurücksetzen.
                 */
                maxTempLeft = 0.0
                maxTempRight = 0.0

                lastPeakLeanAngle = 0.0

                maxAcceleration = 0.0
                maxBraking = 0.0

                peakResetRunnable = null

                notifyUpdates()
            }

        handler.postDelayed(
            peakResetRunnable!!,
            delay
        )
    }

    private fun cancelPeakTimer() {

        peakResetRunnable?.let {
            handler.removeCallbacks(it)
        }

        peakResetRunnable = null
    }

    // ========================================================================
    // VECTOR ROTATION
    // ========================================================================

    /**
     * Rotiert einen Vektor mit einem Quaternion.
     *
     * v' = q * v * q^-1
     */
    private fun rotateVector(
        q: Quaternion,
        vector: Vector3
    ): Vector3 {

        val vectorQuaternion =
            Quaternion(
                w = 0.0,
                x = vector.x,
                y = vector.y,
                z = vector.z
            )

        val rotated =
            q *
                    vectorQuaternion *
                    q.inverse()

        return Vector3(
            rotated.x,
            rotated.y,
            rotated.z
        )
    }

    // ========================================================================
    // UI
    // ========================================================================

    private fun notifyUpdates() {

        val calculatedAngle =
            currentFusedAngle -
                    calibrationOffset

        val finalAngle =
            round(
                calculatedAngle / 0.1
            ) * 0.1

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

    fun startRecording() {

        /*
         * Alte Peak-Timer nicht in eine neue Aufnahme übernehmen.
         */
        cancelPeakTimer()

        isRecording = true

        /*
         * Temporäre Peak-Werte beginnen bei null.
         */
        maxTempLeft = 0.0
        maxTempRight = 0.0
        lastPeakLeanAngle = 0.0

        maxAcceleration = 0.0
        maxBraking = 0.0

        smoothedAccel = 0.0
        smoothedBrake = 0.0

        /*
         * GPS-Historie beginnt für die neue Aufnahme neu.
         */
        lastValidLat = currentLatitude
        lastValidLon = currentLongitude
        lastValidTime = 0L

        notifyUpdates()
    }

    fun stopRecording() {

        /*
         * Aktives Peak-Fenster abbrechen.
         */
        cancelPeakTimer()

        isRecording = false

        /*
         * Temporäre Peaks löschen.
         */
        maxTempLeft = 0.0
        maxTempRight = 0.0
        lastPeakLeanAngle = 0.0

        maxAcceleration = 0.0
        maxBraking = 0.0

        notifyUpdates()
    }

    /**
     * Beendet eine Aufnahme.
     *
     * Falls noch ein Peak-Fenster aktiv ist, wird der aktuelle Peak
     * unmittelbar gespeichert.
     */
    fun finishRecording() {

        if (isRecording) {

            /*
             * Timer stoppen, damit danach kein zweiter identischer Eintrag
             * entsteht.
             */
            cancelPeakTimer()

            val hasLeanPeak =
                abs(lastPeakLeanAngle) > 1.0

            val hasAccelPeak =
                maxAcceleration > 0.1

            val hasBrakePeak =
                abs(maxBraking) > 0.1

            if (
                hasLeanPeak ||
                hasAccelPeak ||
                hasBrakePeak
            ) {
                recordEntry()
            }
        }

        isRecording = false

        maxTempLeft = 0.0
        maxTempRight = 0.0
        lastPeakLeanAngle = 0.0

        maxAcceleration = 0.0
        maxBraking = 0.0

        notifyUpdates()
    }

    /**
     * Kompatibilitätsmethode.
     *
     * Wird nicht mehr für die normale Peak-Logik benötigt.
     */
    fun recordCurrentState() {

        if (!isRecording) {
            return
        }

        recordEntry()
    }

    /** Fügt bei jedem gültigen Standort-Update einen Punkt zur Tourspur hinzu. */
    fun recordLocation(
        location: android.location.Location,
        speedKmH: Double,
        leanAtFixDegrees: Double? = null,
    ) {
        if (!isRecording || !location.hasAccuracy() || location.accuracy > 100f) return
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return

        currentLatitude = location.latitude
        currentLongitude = location.longitude
        currentAltitude = if (location.hasAltitude()) location.altitude else 0.0
        currentSpeedKmH = speedKmH

        val lean = (leanAtFixDegrees
            ?: leanAngleAt(location.elapsedRealtimeNanos)
            ?: (currentFusedAngle - calibrationOffset)).coerceIn(-65.0, 65.0)
        val entry = TourLogEntry(
            timestamp = gpxDateFormat.format(Date(location.time.takeIf { it > 0L } ?: timeProvider())),
            leanAngleLeft = if (lean < 0.0) abs(lean) else 0.0,
            leanAngleRight = if (lean > 0.0) lean else 0.0,
            acceleration = smoothedAccel,
            braking = smoothedBrake,
            lat = location.latitude,
            lon = location.longitude,
            altitude = currentAltitude,
            speed = speedKmH
        )
        listener.onPeakRecorded(entry)
    }

    private fun recordEntry() {

        val now =
            timeProvider()

        /*
         * GPS-basierte Plausibilitätsprüfung.
         *
         * Die Prüfung wird nur durchgeführt, wenn bereits eine vorherige
         * gültige Position vorhanden ist.
         */
        if (lastValidTime != 0L) {

            val dist =
                calculateDistance(
                    lastValidLat,
                    lastValidLon,
                    currentLatitude,
                    currentLongitude
                )

            val timeSec =
                (now - lastValidTime) /
                        1000.0

            if (timeSec > 0.0) {

                val speedCheck =
                    (dist / timeSec) * 3.6

                if (speedCheck > 300.0) {
                    return
                }
            }
        }

        /*
         * Aktuelle GPS-Position als letzten gültigen Punkt merken.
         */
        lastValidLat =
            currentLatitude

        lastValidLon =
            currentLongitude

        lastValidTime =
            now

        /*
         * Lean-Richtung.
         */
        val leftVal =
            if (lastPeakLeanAngle < 0.0) {
                abs(lastPeakLeanAngle)
            } else {
                0.0
            }

        val rightVal =
            if (lastPeakLeanAngle > 0.0) {
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
                leanAngleLeft =
                    leftVal,
                leanAngleRight =
                    rightVal,
                acceleration =
                    maxAcceleration,
                braking =
                    maxBraking,
                lat =
                    currentLatitude,
                lon =
                    currentLongitude,
                altitude =
                    currentAltitude,
                speed =
                    currentSpeedKmH
            )

        listener.onPeakRecorded(
            entry
        )
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

        val r =
            6371000.0

        val dLat =
            Math.toRadians(
                lat2 - lat1
            )

        val dLon =
            Math.toRadians(
                lon2 - lon1
            )

        val a =
            sin(dLat / 2).pow(2.0) +
                    cos(
                        Math.toRadians(lat1)
                    ) *
                    cos(
                        Math.toRadians(lat2)
                    ) *
                    sin(dLon / 2).pow(2.0)

        val clampedA =
            a.coerceIn(
                0.0,
                1.0
            )

        val c =
            2.0 *
                    atan2(
                        sqrt(clampedA),
                        sqrt(1.0 - clampedA)
                    )

        return r * c
    }

    // ========================================================================
    // VECTOR
    // ========================================================================

    private data class Vector3(
        val x: Double,
        val y: Double,
        val z: Double
    )

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
                    w = 1.0,
                    x = 0.0,
                    y = 0.0,
                    z = 0.0
                )
            }

            return Quaternion(
                w = w / length,
                x = x / length,
                y = y / length,
                z = z / length
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
                    w = 1.0,
                    x = 0.0,
                    y = 0.0,
                    z = 0.0
                )
            }

            return Quaternion(
                w =
                    w / normSquared,
                x =
                    -x / normSquared,
                y =
                    -y / normSquared,
                z =
                    -z / normSquared
            )
        }

        fun dot(
            other: Quaternion
        ): Double {

            return w * other.w +
                    x * other.x +
                    y * other.y +
                    z * other.z
        }

        operator fun unaryMinus():
                Quaternion {

            return Quaternion(
                -w,
                -x,
                -y,
                -z
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
