package com.dpm.pegdown.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class SensorProcessorTest {

    private lateinit var sensorProcessor: SensorProcessor
    private val context = mockk<Context>(relaxed = true)
    private val listener = mockk<SensorUpdateListener>(relaxed = true)
    private val sensorManager = mockk<SensorManager>(relaxed = true)
    private val windowManager = mockk<WindowManager>(relaxed = true)
    private val display = mockk<Display>(relaxed = true)

    @Before
    fun setup() {
        mockkConstructor(Handler::class)
        every { anyConstructed<Handler>().postDelayed(any(), any()) } returns true
        every { anyConstructed<Handler>().removeCallbacks(any()) } answers { }
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns null
        every { sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) } returns null
        every { context.getSystemService(Context.WINDOW_SERVICE) } returns windowManager
        
        // Modern way (API 30+)
        every { context.display } returns display
        // Fallback for older APIs (still needed for the deprecated branch in code)
        @Suppress("DEPRECATION")
        every { windowManager.defaultDisplay } returns display
        
        sensorProcessor = SensorProcessor(context, listener)
    }

    @After
    fun tearDown() {
        unmockkConstructor(Handler::class)
    }

    @Test
    fun `calibration averages rotation samples and treats reference as zero`() {
        val leanUpdates = mutableListOf<Double>()
        every {
            listener.onLeanAngleUpdate(any(), any(), any(), any(), any())
        } answers {
            leanUpdates.add(firstArg())
        }
        sensorProcessor.start()
        repeat(15) { sensorProcessor.handleRotationVector(rotationVector(24.0)) }

        assertTrue(sensorProcessor.hasSensorData)
        assertFalse(sensorProcessor.isCalibrated)
        sensorProcessor.calibrate()
        assertTrue(sensorProcessor.isCalibrationInProgress)

        repeat(29) { index ->
            val sampleAngle = if (index < 15) 20.0 else 28.0
            sensorProcessor.handleRotationVector(rotationVector(sampleAngle))
        }
        assertTrue(sensorProcessor.isCalibrationInProgress)
        sensorProcessor.handleRotationVector(rotationVector(28.0))

        assertTrue(sensorProcessor.isCalibrated)
        assertFalse(sensorProcessor.isCalibrationInProgress)

        // Der Mittelwert aus 15 Samples bei 20° und 15 bei 28° liegt bei 24°.
        sensorProcessor.handleRotationVector(rotationVector(24.0))
        assertEquals(0.0, leanUpdates.last(), 0.1)
    }

    private fun rotationVector(angleDegrees: Double): FloatArray {
        val halfAngleRadians = Math.toRadians(angleDegrees) / 2.0
        return floatArrayOf(
            0f,
            0f,
            kotlin.math.sin(halfAngleRadians).toFloat(),
            kotlin.math.cos(halfAngleRadians).toFloat()
        )
    }

    private data class TestQuaternion(
        val w: Double,
        val x: Double,
        val y: Double,
        val z: Double
    ) {
        operator fun times(other: TestQuaternion) = TestQuaternion(
            w * other.w - x * other.x - y * other.y - z * other.z,
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w
        )

        fun inverse() = TestQuaternion(w, -x, -y, -z)

        fun toRotationVector() = floatArrayOf(
            x.toFloat(),
            y.toFloat(),
            z.toFloat(),
            w.toFloat()
        )
    }

    private fun rotationX(angle: Double): TestQuaternion {
        val half = angle / 2.0
        return TestQuaternion(kotlin.math.cos(half), kotlin.math.sin(half), 0.0, 0.0)
    }

    private fun rotationZ(angle: Double): TestQuaternion {
        val half = angle / 2.0
        return TestQuaternion(kotlin.math.cos(half), 0.0, 0.0, kotlin.math.sin(half))
    }

    private fun angularRate(delta: TestQuaternion, durationSeconds: Double): FloatArray {
        val angle = 2.0 * kotlin.math.acos(delta.w.coerceIn(-1.0, 1.0))
        val sinHalfAngle = kotlin.math.sin(angle / 2.0)
        if (kotlin.math.abs(sinHalfAngle) < 1e-9) return floatArrayOf(0f, 0f, 0f)
        val radiansPerSecond = angle / durationSeconds
        val scale = radiansPerSecond / sinHalfAngle
        return floatArrayOf(
            (delta.x * scale).toFloat(),
            (delta.y * scale).toFloat(),
            (delta.z * scale).toFloat()
        )
    }

    @Test
    fun `bank angle stays stable during yaw and reports both lean directions`() {
        val leanUpdates = mutableListOf<Double>()
        every {
            listener.onLeanAngleUpdate(any(), any(), any(), any(), any())
        } answers {
            leanUpdates.add(firstArg())
        }

        val uprightMount = rotationX(Math.PI / 2.0)
        sensorProcessor.start()
        repeat(15) { sensorProcessor.handleRotationVector(uprightMount.toRotationVector()) }
        sensorProcessor.calibrate()
        repeat(30) { sensorProcessor.handleRotationVector(uprightMount.toRotationVector()) }
        assertTrue(sensorProcessor.isCalibrated)

        // Reine Kursänderung darf keine Schräglage erzeugen, auch nach mehreren Drehungen.
        val yawQuarterTurn = rotationZ(Math.PI / 2.0)
        val yawHalfTurn = rotationZ(Math.PI)
        repeat(50) {
            sensorProcessor.handleRotationVector(
                (yawQuarterTurn * uprightMount).toRotationVector()
            )
        }
        assertEquals(0.0, leanUpdates.last(), 0.1)
        repeat(50) {
            sensorProcessor.handleRotationVector(
                (yawHalfTurn * uprightMount).toRotationVector()
            )
        }
        assertEquals(0.0, leanUpdates.last(), 0.1)

        // Schräglage bleibt nach einer Kursänderung erhalten und kehrt das Vorzeichen um.
        val leftLean = rotationZ(Math.toRadians(25.0))
        repeat(50) {
            sensorProcessor.handleRotationVector(
                (yawHalfTurn * uprightMount * leftLean).toRotationVector()
            )
        }
        val leftReading = leanUpdates.last()
        assertEquals(-25.0, leftReading, 0.2)

        // Gleiche Schräglage bei anderer Fahrtrichtung muss gleich angezeigt werden.
        repeat(70) {
            sensorProcessor.handleRotationVector(
                (yawQuarterTurn * uprightMount * leftLean).toRotationVector()
            )
        }
        assertEquals(leftReading, leanUpdates.last(), 0.2)

        val rightLean = rotationZ(Math.toRadians(-25.0))
        repeat(70) {
            sensorProcessor.handleRotationVector(
                (yawHalfTurn * uprightMount * rightLean).toRotationVector()
            )
        }
        assertEquals(25.0, leanUpdates.last(), 0.2)
    }

    @Test
    fun `gyroscope tracks lean while gravity correction is blocked by cornering acceleration`() {
        every {
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        } returns mockk(relaxed = true)
        every {
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        } returns mockk(relaxed = true)
        val fusionProcessor = SensorProcessor(context, listener)
        val leanUpdates = mutableListOf<Double>()
        every {
            listener.onLeanAngleUpdate(any(), any(), any(), any(), any())
        } answers {
            leanUpdates.add(firstArg())
        }

        val uprightMount = rotationX(Math.PI / 2.0)
        fusionProcessor.gyroFusionAvailable = true
        fusionProcessor.linearAccelerationAvailable = true
        fusionProcessor.start()
        repeat(15) {
            fusionProcessor.handleRotationVector(uprightMount.toRotationVector())
        }
        fusionProcessor.calibrate()
        repeat(30) { index ->
            fusionProcessor.handleGyroscope(floatArrayOf(0f, 0f, 0f), 1_000_000_000L + index * 20_000_000L)
            fusionProcessor.handleRotationVector(uprightMount.toRotationVector())
        }
        assertTrue(fusionProcessor.isCalibrated)

        // Querbeschleunigung darf eine verfälschte Neigung aus der Rotation Vector Messung nicht übernehmen.
        fusionProcessor.updateDynamicAcceleration(floatArrayOf(2f, 0f, 0f))
        repeat(40) {
            fusionProcessor.handleRotationVector(
                (uprightMount * rotationZ(Math.toRadians(20.0))).toRotationVector()
            )
        }
        assertEquals(0.0, leanUpdates.last(), 0.1)

        // Das Gyroskop verfolgt die echte Rollbewegung trotz gesperrter Gravitätskorrektur.
        val startTimestamp = 2_000_000_000L
        val rollRate = Math.toRadians(20.0).toFloat()
        repeat(51) { index ->
            fusionProcessor.handleGyroscope(
                floatArrayOf(0f, 0f, rollRate),
                startTimestamp + index * 20_000_000L
            )
        }
        assertEquals(-20.0, fusionProcessor.gyroLeanAngle, 0.3)

        // Eine Drehung des Stuhls um die Welt-Hochachse darf den Winkel nicht ändern.
        val bankedOrientation = uprightMount * rotationZ(Math.toRadians(20.0))
        val yawedOrientation = rotationZ(Math.PI / 2.0) * bankedOrientation
        val yawDeltaInDeviceFrame = bankedOrientation.inverse() * yawedOrientation
        val yawRate = angularRate(yawDeltaInDeviceFrame, durationSeconds = 1.0)
        repeat(51) { index ->
            fusionProcessor.handleGyroscope(
                yawRate,
                startTimestamp + 1_020_000_000L + index * 20_000_000L
            )
        }
        assertEquals(-20.0, fusionProcessor.gyroLeanAngle, 0.3)

        // Nach dem Aufrichten bleibt der angezeigte Winkel nahe null.
        val uprightAfterYaw = rotationZ(Math.PI) * uprightMount
        val straightenDelta = yawedOrientation.inverse() * uprightAfterYaw
        val straightenRate = angularRate(straightenDelta, durationSeconds = 1.0)
        repeat(51) { index ->
            fusionProcessor.handleGyroscope(
                straightenRate,
                startTimestamp + 2_040_000_000L + index * 20_000_000L
            )
        }
        assertEquals(0.0, fusionProcessor.gyroLeanAngle, 0.6)

        // Sobald die Querbeschleunigung weg ist, korrigiert die Schwerkraft verbleibenden Gyrodrift.
        fusionProcessor.updateDynamicAcceleration(floatArrayOf(0f, 0f, 0f))
        repeat(100) {
            fusionProcessor.handleRotationVector(uprightMount.toRotationVector())
        }
        assertEquals(0.0, leanUpdates.last(), 0.1)
    }

    @Test
    fun `motorcycle corner tracks lean while yaw and lateral acceleration change`() {
        every {
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        } returns mockk(relaxed = true)
        every {
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        } returns mockk(relaxed = true)
        val fusionProcessor = SensorProcessor(context, listener)
        fusionProcessor.gyroFusionAvailable = true
        fusionProcessor.linearAccelerationAvailable = true

        val uprightMount = rotationX(Math.PI / 2.0)
        fusionProcessor.start()
        repeat(15) {
            fusionProcessor.handleRotationVector(uprightMount.toRotationVector())
        }
        fusionProcessor.calibrate()
        repeat(30) { index ->
            fusionProcessor.handleGyroscope(
                floatArrayOf(0f, 0f, 0f),
                1_000_000_000L + index * 20_000_000L
            )
            fusionProcessor.handleRotationVector(uprightMount.toRotationVector())
        }
        assertTrue(fusionProcessor.isCalibrated)

        fusionProcessor.updateDynamicAcceleration(floatArrayOf(3f, 0f, 0f))
        var orientation = uprightMount
        var timestamp = 2_000_000_000L
        fusionProcessor.handleGyroscope(floatArrayOf(0f, 0f, 0f), timestamp)

        fun followPath(steps: Int, orientationAt: (Double) -> TestQuaternion) {
            repeat(steps) { index ->
                val fraction = (index + 1).toDouble() / steps
                val nextOrientation = orientationAt(fraction)
                val bodyDelta = orientation.inverse() * nextOrientation
                val intervalSeconds = 0.02
                timestamp += 20_000_000L
                fusionProcessor.handleGyroscope(
                    angularRate(bodyDelta, intervalSeconds),
                    timestamp
                )
                fusionProcessor.handleRotationVector(nextOrientation.toRotationVector())
                orientation = nextOrientation
            }
        }

        val leanDegrees = 20.0
        followPath(50) { fraction ->
            rotationZ(Math.toRadians(90.0 * fraction)) *
                uprightMount *
                rotationZ(Math.toRadians(leanDegrees * fraction))
        }
        assertEquals(-leanDegrees, fusionProcessor.gyroLeanAngle, 0.5)

        // In der konstanten Schräglage weiter durch die Kurve drehen.
        followPath(50) { fraction ->
            rotationZ(Math.toRadians(90.0 + 90.0 * fraction)) *
                uprightMount *
                rotationZ(Math.toRadians(leanDegrees))
        }
        assertEquals(-leanDegrees, fusionProcessor.gyroLeanAngle, 0.5)

        // Beim Kurvenausgang aufrichten, während sich die Fahrtrichtung weiter ändert.
        followPath(50) { fraction ->
            rotationZ(Math.toRadians(180.0 + 90.0 * fraction)) *
                uprightMount *
                rotationZ(Math.toRadians(leanDegrees * (1.0 - fraction)))
        }
        assertEquals(0.0, fusionProcessor.gyroLeanAngle, 0.5)

        fusionProcessor.updateDynamicAcceleration(floatArrayOf(0f, 0f, 0f))
        repeat(100) {
            fusionProcessor.handleRotationVector(orientation.toRotationVector())
        }
        assertEquals(0.0, fusionProcessor.gyroLeanAngle, 0.2)
    }

    @Test
    fun `resetTour clears all peak values`() {
        sensorProcessor.resetTour()
        
        assertEquals(0.0, sensorProcessor.maxTourLeft, 0.0)
        assertEquals(0.0, sensorProcessor.maxTourRight, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxAccel, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxBrake, 0.0)
    }
}
