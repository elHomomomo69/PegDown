package com.dpm.pegdown.sensor

import android.content.Context
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
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
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

    @Test
    fun `resetTour clears all peak values`() {
        sensorProcessor.resetTour()
        
        assertEquals(0.0, sensorProcessor.maxTourLeft, 0.0)
        assertEquals(0.0, sensorProcessor.maxTourRight, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxAccel, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxBrake, 0.0)
    }
}
