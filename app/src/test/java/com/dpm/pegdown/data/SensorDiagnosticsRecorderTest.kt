package com.dpm.pegdown.data

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import java.util.concurrent.ArrayBlockingQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SensorDiagnosticsRecorderTest {
    private lateinit var recorder: SensorDiagnosticsRecorder
    private lateinit var queue: ArrayBlockingQueue<String>

    @Before
    fun setup() {
        mockkConstructor(Handler::class)
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        recorder = SensorDiagnosticsRecorder(context)
        // Exercise actual row construction without starting an Android output worker.
        @Suppress("UNCHECKED_CAST")
        val pending = SensorDiagnosticsRecorder::class.java.getDeclaredField("queue")
            .apply { isAccessible = true }.get(recorder) as ArrayBlockingQueue<String>
        queue = pending
    }

    @After
    fun cleanup() {
        unmockkConstructor(Handler::class)
        unmockkStatic(Looper::class)
    }

    private fun enableRecording() {
        SensorDiagnosticsRecorder::class.java.getDeclaredField("acceptingSamples")
            .apply { isAccessible = true }.setBoolean(recorder, true)
    }

    @Test
    fun `location retains native fix time and separate exact callback time`() {
        enableRecording()
        val location = mockk<Location>(relaxed = true)
        every { location.elapsedRealtimeNanos } returns 1_000_000_000L
        every { location.provider } returns "gps"
        recorder.recordLocation(location, 2.0, true, callbackElapsedRealtimeNanos = 1_230_456_789L)
        val fields = queue.remove().split(',')
        assertEquals(44, fields.size)
        assertEquals("location", fields[0])
        assertEquals("1000000000", fields[2])
        assertEquals("1000000000", fields[24])
        assertEquals("1230456789", fields[43])
        assertEquals("2.0", fields[37])
        assertEquals("DISABLED", fields[42])
    }

    @Test
    fun `accepted and rejected corrections retain reference time reason and metadata`() {
        enableRecording()
        for (reason in listOf("ACCEPTED", "COURSE_GYRO_MISMATCH")) {
            recorder.recordLeanCorrection(700L, 1200L, reason,
                "fix_elapsed_realtime_ns=1000; bank_before_degrees=-9; bank_after_degrees=-7")
            val fields = queue.remove().split(',')
            assertEquals(44, fields.size)
            assertEquals("lean_correction", fields[0])
            assertEquals("700", fields[2])
            assertEquals("1200", fields[43])
            assertEquals(reason, fields[42])
            assertTrue(fields[36].contains("fix_elapsed_realtime_ns=1000"))
            assertTrue(fields[36].contains("bank_before_degrees=-9"))
        }
        recorder.stop()
        recorder.recordLeanCorrection(800L, 1300L, "ACCEPTED", "")
        assertTrue(queue.isEmpty())
    }
}
