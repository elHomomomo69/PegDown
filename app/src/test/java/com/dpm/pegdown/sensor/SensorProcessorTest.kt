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
    fun `auto-zero adjusts calibrationOffset after 10 seconds of straight riding`() {
        mockkStatic(System::class)
        
        // Step 1: Start driving straight at 50 km/h
        sensorProcessor.currentSpeedKmH = 50.0
        every { System.currentTimeMillis() } returns 1000L
        sensorProcessor.checkAutoZero(1.0) // 1 degree tilt while "straight"
        
        // Step 2: 5 seconds later (still straight)
        every { System.currentTimeMillis() } returns 6000L
        sensorProcessor.checkAutoZero(1.0)
        assertEquals(0.0, sensorProcessor.calibrationOffset, 0.001) // No correction yet
        
        // Step 3: 11 seconds total elapsed
        every { System.currentTimeMillis() } returns 12000L
        sensorProcessor.checkAutoZero(1.0)
        
        // Should have adjusted: offset += 1.0 * 0.01 = 0.01
        assertEquals(0.01, sensorProcessor.calibrationOffset, 0.001)
    }

    @Test
    fun `auto-zero does not adjust when speed is low`() {
        mockkStatic(System::class)
        sensorProcessor.currentSpeedKmH = 30.0 // Below threshold
        every { System.currentTimeMillis() } returns 1000L
        
        sensorProcessor.checkAutoZero(1.0)
        every { System.currentTimeMillis() } returns 12000L
        sensorProcessor.checkAutoZero(1.0)
        
        assertEquals(0.0, sensorProcessor.calibrationOffset, 0.001)
    }
}
