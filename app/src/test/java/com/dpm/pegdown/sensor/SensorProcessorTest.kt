package com.dpm.pegdown.sensor

import android.content.Context
import android.hardware.SensorManager
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
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

    @Test
    fun `calibrate sets calibrationOffset to current state`() {
        sensorProcessor.calibrate()
        assertEquals(0.0, sensorProcessor.calibrationOffset, 0.1)
    }

    @Test
    fun `resetTour clears all peak values`() {
        sensorProcessor.maxTourLeft = -45.0
        sensorProcessor.maxTourRight = 30.0
        sensorProcessor.tourMaxAccel = 0.8
        sensorProcessor.tourMaxBrake = -0.5
        
        sensorProcessor.resetTour()
        
        assertEquals(0.0, sensorProcessor.maxTourLeft, 0.0)
        assertEquals(0.0, sensorProcessor.maxTourRight, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxAccel, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxBrake, 0.0)
    }

    @Test
    fun `auto-zero adjusts calibrationOffset after long stable straight riding`() {
        var simulatedTime = 100000L
        sensorProcessor.timeProvider = { simulatedTime }
        sensorProcessor.currentSpeedKmH = 50.0
        
        // Initial check - should initialize straightDriveStartTime
        // Angle must be < autoZeroThresholdAngle (1.0)
        sensorProcessor.checkAutoZero(0.5) 
        
        // Advance time by 16 seconds (must be > autoZeroDurationMs which is 15s)
        simulatedTime += 16000L
        
        // Second check - should trigger the correction
        sensorProcessor.checkAutoZero(0.5)
        
        // offset += currentAngle * 0.005 -> 0.5 * 0.005 = 0.0025
        assertEquals(0.0025, sensorProcessor.calibrationOffset, 0.0001)
    }

    @Test
    fun `auto-zero does not adjust when speed is low`() {
        var simulatedTime = 100000L
        sensorProcessor.timeProvider = { simulatedTime }
        sensorProcessor.currentSpeedKmH = 30.0 // Below threshold (40.0)
        
        sensorProcessor.checkAutoZero(0.5)
        simulatedTime += 16000L
        sensorProcessor.checkAutoZero(0.5)
        
        assertEquals(0.0, sensorProcessor.calibrationOffset, 0.001)
    }
}
