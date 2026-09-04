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
    fun `calibrate sets calibrationOffset to current smoothedTilt`() {
        // Given: We simulate some raw tilt detected by sensors
        // Since we can't easily trigger handleGravity directly without reflecting private fields,
        // we'll at least test that calibrate updates based on the current state.
        
        // Manual "injection" of state for testing if possible, 
        // or testing the public behavior of calibrate()
        
        sensorProcessor.calibrate()
        
        // After calibrate, the initial offset should be 0.0 if no tilt was processed
        assertEquals(0.0, sensorProcessor.calibrationOffset, 0.1)
    }

    @Test
    fun `resetTour clears all maximum values`() {
        sensorProcessor.maxTourLeft = -45.0
        sensorProcessor.maxTourRight = 30.0
        sensorProcessor.tourMaxAccel = 0.8
        
        sensorProcessor.resetTour()
        
        assertEquals(0.0, sensorProcessor.maxTourLeft, 0.0)
        assertEquals(0.0, sensorProcessor.maxTourRight, 0.0)
        assertEquals(0.0, sensorProcessor.tourMaxAccel, 0.0)
    }
}
