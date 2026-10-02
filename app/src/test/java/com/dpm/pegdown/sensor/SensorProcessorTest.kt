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
        sensorProcessor.resetTour()
        
        assertEquals(0.0, sensorProcessor.maxTourLeft, 0.0)
        assertEquals(0.0, sensorProcessor.maxTourRight, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxAccel, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxBrake, 0.0)
    }
}
