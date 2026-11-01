package com.dpm.pegdown.sensor

import android.content.Context
import com.dpm.pegdown.BuildConfig
import com.dpm.pegdown.R
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
    private var monotonicNow = 1_000_000_000L
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
        
        every { context.getString(R.string.app_build_id) } returns "ABCDEF012345"
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
        every { context.getSystemService(Context.WINDOW_SERVICE) } returns windowManager
        
        // Modern way (API 30+)
        every { context.display } returns display
        // Fallback for older APIs (still needed for the deprecated branch in code)
        @Suppress("DEPRECATION")
        every { windowManager.defaultDisplay } returns display
        
        monotonicNow = 1_000_000_000L
        sensorProcessor = SensorProcessor(context, listener) { monotonicNow }
    }

    @Test
    fun diagnosticMetadataContainsTheDisplayedBuildIdentityAndStationaryPolicy() {
        val metadata = sensorProcessor.diagnosticSessionMetadata()
            .split("; ").associate { entry ->
                val pair = entry.split("=", limit = 2)
                pair[0] to pair[1]
            }
        assertEquals(BuildConfig.VERSION_NAME, metadata["app_version_name"])
        assertEquals(BuildConfig.VERSION_CODE.toString(), metadata["app_version_code"])
        assertEquals("ABCDEF012345", metadata["app_build_id"])
        assertEquals("2", metadata["experimental_stationary_guard_version"])
        assertEquals("750000000", metadata["experimental_stationary_speed_window_ns"])
        assertEquals("0.5", metadata["experimental_stationary_max_acceleration_upper_mps2"])
    }

    @After
    fun tearDown() {
        unmockkConstructor(Handler::class)
    }

    private fun calibrateExperimental() {
        sensorProcessor.setExperimentalLeanEnabled(true)
        val mounted = floatArrayOf(
            kotlin.math.sin(Math.toRadians(40.0)).toFloat(), 0f, 0f,
            kotlin.math.cos(Math.toRadians(40.0)).toFloat(),
        )
        sensorProcessor.start()
        repeat(15) { sensorProcessor.handleRotationVector(mounted) }
        sensorProcessor.calibrate()
        repeat(30) { sensorProcessor.handleRotationVector(mounted) }
        assertTrue(sensorProcessor.isCalibrated)
        assertFalse(sensorProcessor.isExperimentalReady)
    }

    @Test
    fun `experimental mode defaults off and switching requires a fresh calibration`() {
        assertFalse(sensorProcessor.experimentalLeanEnabled)
        sensorProcessor.setExperimentalLeanEnabled(true)
        assertTrue(sensorProcessor.experimentalLeanEnabled)
        assertFalse(sensorProcessor.isCalibrated)
        assertEquals(null, sensorProcessor.experimentalLeanAngleDegrees())
    }

    @Test
    fun `experimental live gyro supplies gauge and timestamp history while standard remains available`() {
        calibrateExperimental()
        val up = ImuQuaternion.axisAngle(ImuVector(1.0, 0.0, 0.0), Math.toRadians(80.0))
            .inverse().rotate(ImuVector.UP)
        val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(up)
        val gyro = floatArrayOf((forward.x * 0.1).toFloat(), (forward.y * 0.1).toFloat(), (forward.z * 0.1).toFloat())
        for (i in 0..100) sensorProcessor.handleExperimentalGyroscope(1_000_000_000L + i * 20_000_000L, gyro)
        assertTrue(sensorProcessor.isExperimentalReady)
        assertEquals(0.0, sensorProcessor.standardLeanAngleDegrees(), 1e-6)
        assertTrue(sensorProcessor.currentLeanAngleDegrees() > 8.0)
        assertEquals(sensorProcessor.experimentalLeanAngleDegrees()!!, sensorProcessor.currentLeanAngleDegrees(), 0.0)
        assertEquals(sensorProcessor.currentLeanAngleDegrees(), sensorProcessor.leanAngleAt(3_000_000_000L)!!, 1e-9)
        sensorProcessor.isRecording = true
        sensorProcessor.setExperimentalLeanEnabled(false)
        assertTrue(sensorProcessor.experimentalLeanEnabled)
        sensorProcessor.isRecording = false
        sensorProcessor.setExperimentalLeanEnabled(false)
        assertFalse(sensorProcessor.isCalibrated)
        assertEquals(null, sensorProcessor.leanAngleAt(3_000_000_000L))
    }

    @Test
    fun `experimental gyro gap invalidates estimate and requires recalibration`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(1_000_000_000L, floatArrayOf(0f, 0f, 0f))
        sensorProcessor.handleExperimentalGyroscope(1_020_000_000L, floatArrayOf(0f, 0f, 0f))
        assertTrue(sensorProcessor.isExperimentalReady)
        sensorProcessor.handleExperimentalGyroscope(2_000_000_000L, floatArrayOf(0f, 0f, 0f))
        assertFalse(sensorProcessor.isExperimentalReady)
        assertEquals("GYRO_GAP_WAITING_REFERENCE", sensorProcessor.experimentalDiagnosticStatus())
        assertEquals(null, sensorProcessor.leanAngleAt(1_020_000_000L))
        sensorProcessor.handleExperimentalGyroscope(2_020_000_000L, floatArrayOf(0f, 0f, 0f))
        assertFalse(sensorProcessor.isExperimentalReady)
    }

    @Test
    fun `watchdog invalidates when all sensor callbacks cease and stop cancels it`() {
        val scheduled = mutableListOf<Runnable>()
        every { anyConstructed<Handler>().postDelayed(any(), any()) } answers {
            scheduled.add(firstArg())
            true
        }
        calibrateExperimental()
        val watchdog = scheduled.first()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        assertTrue(sensorProcessor.isExperimentalReady)
        monotonicNow += 150_000_000L
        watchdog.run()
        assertTrue(sensorProcessor.isExperimentalReady)
        monotonicNow++
        watchdog.run()
        assertFalse(sensorProcessor.isExperimentalReady)
        assertEquals("GYRO_TIMEOUT_WAITING_REFERENCE", sensorProcessor.experimentalDiagnosticStatus())
        assertEquals(null, sensorProcessor.experimentalLeanAngleDegrees())
        assertEquals(null, sensorProcessor.leanAngleAt(1_000_000_000L))
        io.mockk.verify { listener.onLeanAngleUpdate(any(), any(), any(), any(), any()) }
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        assertFalse(sensorProcessor.isExperimentalReady)
        sensorProcessor.stop()
        io.mockk.verify { anyConstructed<Handler>().removeCallbacks(watchdog) }
        val count = scheduled.size
        watchdog.run()
        assertEquals(count, scheduled.size)
    }

    @Test
    fun `accelerometer and out of order gyro cannot keep an obsolete estimate valid`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 140_000_000L
        sensorProcessor.handleExperimentalAccelerometer(monotonicNow, floatArrayOf(0f, 0f, 9.81f))
        sensorProcessor.handleExperimentalGyroscope(1_000_000_000L, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 20_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        assertFalse(sensorProcessor.isExperimentalReady)
    }

    @Test
    fun `fresh delivery of delayed gyro cannot hide obsolete measurement time`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 140_000_000L
        sensorProcessor.handleExperimentalGyroscope(1_020_000_000L, floatArrayOf(0f, 0f, 0f))
        assertTrue(sensorProcessor.isExperimentalReady)
        monotonicNow += 40_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        assertFalse(sensorProcessor.isExperimentalReady)
        assertEquals("GYRO_TIMEOUT_WAITING_REFERENCE", sensorProcessor.experimentalDiagnosticStatus())
    }

    @Test
    fun `valid timely gyro keeps estimate ready and recalibration recovers after timeout`() {
        calibrateExperimental()
        repeat(10) {
            sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
            monotonicNow += 20_000_000L
            sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
            assertTrue(sensorProcessor.isExperimentalReady)
        }
        monotonicNow += 200_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        assertFalse(sensorProcessor.isExperimentalReady)
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        assertTrue(sensorProcessor.isExperimentalReady)
        assertEquals("GYRO_ONLY", sensorProcessor.experimentalDiagnosticStatus())
    }

    private fun feedRecovery(
        bankDegrees: Double, seconds: Int, provider: String = "gps",
        speedMps: Double = 0.0, yawRate: Double = 0.0,
        provisionalBankDegrees: Double = bankDegrees,
    ) {
        val base = ImuQuaternion.axisAngle(ImuVector(1.0, 0.0, 0.0), Math.toRadians(80.0))
        val up = base.inverse().rotate(ImuVector.UP)
        val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(up)
        repeat(seconds * 50) { index ->
            monotonicNow += 20_000_000L
            val pose = ImuQuaternion.axisAngle(ImuVector.UP, yawRate * monotonicNow / 1e9) *
                base * ImuQuaternion.axisAngle(forward, Math.toRadians(bankDegrees))
            val bodyUp = pose.inverse().rotate(ImuVector.UP)
            val gravity = bodyUp * 9.80665 + forward.cross(bodyUp).unit() * (speedMps * yawRate)
            val rate = bodyUp * yawRate
            val provisional = ImuQuaternion.axisAngle(ImuVector.UP, yawRate * monotonicNow / 1e9) *
                base * ImuQuaternion.axisAngle(forward, Math.toRadians(provisionalBankDegrees))
            val rv = floatArrayOf(provisional.x.toFloat(), provisional.y.toFloat(), provisional.z.toFloat(), provisional.w.toFloat())
            sensorProcessor.handleRotationVector(rv, monotonicNow)
            sensorProcessor.handleExperimentalGyroscope(monotonicNow,
                floatArrayOf(rate.x.toFloat(), rate.y.toFloat(), rate.z.toFloat()))
            sensorProcessor.handleExperimentalAccelerometer(monotonicNow,
                floatArrayOf(gravity.x.toFloat(), gravity.y.toFloat(), gravity.z.toFloat()))
            if (index % 50 == 49) {
                sensorProcessor.handleExperimentalFix(CompensatedImuLeanExperiment.Fix(
                    monotonicNow - 100_000_000L, monotonicNow, speedMps,
                    ((Math.toDegrees(yawRate * (monotonicNow - 100_000_000L) / 1e9) % 360.0) + 360.0) % 360.0,
                    1.0, 0.1, 3.0, true, true, provider,
                ))
            }
        }
    }

    @Test
    fun `automatic recovery preserves side stand bank and original calibration`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 300_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        assertTrue(sensorProcessor.isExperimentalRecovering)
        feedRecovery(-15.0, 2)
        assertFalse(sensorProcessor.isExperimentalReady)
        assertEquals(null, sensorProcessor.experimentalLeanAngleDegrees())
        assertEquals(null, sensorProcessor.leanAngleAt(monotonicNow))
        feedRecovery(-15.0, 4)
        assertTrue(sensorProcessor.isExperimentalReady)
        assertFalse(sensorProcessor.isExperimentalRecovering)
        assertTrue(sensorProcessor.isCalibrated)
        assertEquals(0.0, sensorProcessor.calibrationOffset, 0.0)
        assertEquals(-15.0, sensorProcessor.currentLeanAngleDegrees(), 0.05)
    }

    @Test
    fun `untrusted references do not publish provisional rotation vector during recovery`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 300_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        feedRecovery(20.0, 5, provider = "network")
        assertFalse(sensorProcessor.isExperimentalReady)
        assertTrue(sensorProcessor.isExperimentalRecovering)
        assertEquals(null, sensorProcessor.experimentalLeanAngleDegrees())
        feedRecovery(20.0, 4)
        assertTrue(sensorProcessor.isExperimentalReady)
        assertEquals(20.0, sensorProcessor.currentLeanAngleDegrees(), 0.05)
    }

    @Test
    fun `automatic recovery during a steady motorcycle turn preserves real bank`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 300_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        val yawRate = 9.80665 * kotlin.math.tan(Math.toRadians(20.0)) / 15.0
        feedRecovery(20.0, 6, speedMps = 15.0, yawRate = yawRate)
        assertTrue(sensorProcessor.isExperimentalReady)
        assertFalse(sensorProcessor.isExperimentalRecovering)
        assertEquals(20.0, sensorProcessor.currentLeanAngleDegrees(), 0.1)
    }

    @Test
    fun `recovery waits during stationary yaw rotation despite accepted gravity corrections`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 300_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        feedRecovery(-15.0, 6, yawRate = 0.3)
        assertFalse(sensorProcessor.isExperimentalReady)
        assertTrue(sensorProcessor.isExperimentalRecovering)
    }

    @Test
    fun `bad provisional seed remains hidden and is retried when the reference settles`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 300_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        feedRecovery(-15.0, 5, provisionalBankDegrees = 20.0)
        assertFalse(sensorProcessor.isExperimentalReady)
        assertEquals(null, sensorProcessor.experimentalLeanAngleDegrees())
        feedRecovery(-15.0, 12)
        assertTrue(sensorProcessor.isExperimentalReady)
        assertEquals(-15.0, sensorProcessor.currentLeanAngleDegrees(), 0.05)
    }

    @Test
    fun `another gyro outage discards partial recovery confirmations`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        monotonicNow += 300_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        feedRecovery(-15.0, 2)
        assertFalse(sensorProcessor.isExperimentalReady)
        monotonicNow += 300_000_000L
        sensorProcessor.checkExperimentalGyroFreshness(monotonicNow)
        feedRecovery(-15.0, 2)
        assertFalse(sensorProcessor.isExperimentalReady)
        feedRecovery(-15.0, 3)
        assertTrue(sensorProcessor.isExperimentalReady)
    }

    @Test
    fun `GPS correction diagnostics use supplied callback time and retain unchanged bank on rejection`() {
        calibrateExperimental()
        sensorProcessor.handleExperimentalGyroscope(monotonicNow, floatArrayOf(0f, 0f, 0f))
        sensorProcessor.isRecording = true
        val location = mockk<android.location.Location>(relaxed = true)
        every { location.elapsedRealtimeNanos } returns monotonicNow
        every { location.provider } returns "network"
        val receipt = monotonicNow + 20_000_000L
        sensorProcessor.handleExperimentalLocation(location, receipt)
        io.mockk.verify(exactly = 1) {
            listener.onDiagnosticLeanCorrection(any(), receipt, "NON_GPS", match {
                fun bank(key: String) = Regex("$key=([^;]+)").find(it)!!.groupValues[1].toDouble()
                it.contains("fix_elapsed_realtime_ns=1000000000") &&
                    kotlin.math.abs(bank("bank_before_degrees")) < 1e-6 &&
                    bank("bank_before_degrees") == bank("bank_after_degrees")
            })
        }
    }

    @Test
    fun `stationary guard rejection is included in live correction diagnostics`() {
        calibrateExperimental()
        sensorProcessor.isRecording = true
        feedRecovery(0.0, 2, yawRate = 0.3)
        io.mockk.verify {
            listener.onDiagnosticLeanCorrection(any(), any(), "STATIONARY_UNSTABLE", match {
                it.contains("stationary_history_covered=true") &&
                    it.contains("stationary_qualified=false") &&
                    it.contains("stationary_speed_history_covered=true") &&
                    it.contains("stationary_speed_qualified=true") &&
                    !it.contains("stationary_mean_angular_rate_radps=null") &&
                    it.contains("straight_reason=STATIONARY") &&
                    it.contains("straight_applied_bank_delta_degrees=0.0")
            })
        }
    }

    @Test
    fun stationarySpeedWaitIsLoggedWithoutChangingTheBank() {
        calibrateExperimental()
        sensorProcessor.isRecording = true
        feedRecovery(0.0, 1)
        io.mockk.verify {
            listener.onDiagnosticLeanCorrection(any(), any(), "STATIONARY_SPEED_UNCERTAIN", match {
                fun bank(key: String) = Regex("$key=([^;]+)").find(it)!!.groupValues[1].toDouble()
                it.contains("stationary_speed_history_covered=false") &&
                    it.contains("stationary_speed_qualified=false") &&
                    bank("bank_before_degrees") == bank("bank_after_degrees")
            })
        }
    }

    @Test
    fun `calibration during recording emits reference metadata for replay`() {
        sensorProcessor.isRecording = true
        calibrateExperimental()
        // Changing modes is deliberately blocked during recording.
        sensorProcessor.isRecording = false
        calibrateExperimental()
        sensorProcessor.isRecording = true
        sensorProcessor.calibrate()
        val mounted = floatArrayOf(kotlin.math.sin(Math.toRadians(40.0)).toFloat(), 0f, 0f,
            kotlin.math.cos(Math.toRadians(40.0)).toFloat())
        repeat(30) { sensorProcessor.handleRotationVector(mounted) }
        io.mockk.verify { listener.onDiagnosticCalibration(match {
            it.contains("lean_mode=experimental_full_interval") && it.contains("calibration_gravity_xyz=") &&
                it.contains("app_build_id=ABCDEF012345") &&
                it.contains("app_version_name=${BuildConfig.VERSION_NAME}") &&
                it.contains("experimental_stationary_guard=true") &&
                it.contains("experimental_stationary_guard_version=2") &&
                it.contains("experimental_stationary_window_ns=750000000") &&
                it.contains("experimental_straight_reference=true") &&
                it.contains("experimental_straight_window_ns=5000000000")
        }) }
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

    private fun rotationY(angle: Double): TestQuaternion {
        val half = angle / 2.0
        return TestQuaternion(kotlin.math.cos(half), 0.0, kotlin.math.sin(half), 0.0)
    }

    private fun rotationZ(angle: Double): TestQuaternion {
        val half = angle / 2.0
        return TestQuaternion(kotlin.math.cos(half), 0.0, 0.0, kotlin.math.sin(half))
    }

    @Test
    fun `ten degree pitched mount tracks bike bank through yaw`() {
        val leanUpdates = mutableListOf<Double>()
        every {
            listener.onLeanAngleUpdate(any(), any(), any(), any(), any())
        } answers {
            leanUpdates.add(firstArg())
        }

        // Device Z is pitched 10 degrees away from the motorcycle's forward axis.
        val mountedOrientation = rotationX(Math.toRadians(80.0))
        sensorProcessor.start()
        repeat(15) {
            sensorProcessor.handleRotationVector(mountedOrientation.toRotationVector())
        }
        sensorProcessor.calibrate()
        repeat(30) {
            sensorProcessor.handleRotationVector(mountedOrientation.toRotationVector())
        }
        assertTrue(sensorProcessor.isCalibrated)

        val expectedLeanDegrees = 20.0
        val motorcycleBank = rotationY(Math.toRadians(expectedLeanDegrees))
        val yaw = rotationZ(Math.toRadians(135.0))
        repeat(80) {
            sensorProcessor.handleRotationVector(
                (yaw * motorcycleBank * mountedOrientation).toRotationVector()
            )
        }

        assertEquals(expectedLeanDegrees, kotlin.math.abs(leanUpdates.last()), 0.5)

        // Straightening at another heading must return the reading to zero.
        repeat(80) {
            sensorProcessor.handleRotationVector(
                (rotationZ(Math.toRadians(225.0)) * mountedOrientation).toRotationVector()
            )
        }
        assertEquals(0.0, leanUpdates.last(), 0.2)
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
    fun `motorcycle corner keeps bank angle stable through yaw and returns to zero`() {
        val leanUpdates = mutableListOf<Double>()
        every {
            listener.onLeanAngleUpdate(any(), any(), any(), any(), any())
        } answers {
            leanUpdates.add(firstArg())
        }

        val uprightMount = rotationX(Math.PI / 2.0)
        sensorProcessor.start()
        repeat(15) {
            sensorProcessor.handleRotationVector(uprightMount.toRotationVector())
        }
        sensorProcessor.calibrate()
        repeat(30) {
            sensorProcessor.handleRotationVector(uprightMount.toRotationVector())
        }
        assertTrue(sensorProcessor.isCalibrated)

        var orientation = uprightMount
        fun sendPath(steps: Int, orientationAt: (Double) -> TestQuaternion) {
            repeat(steps) { index ->
                orientation = orientationAt((index + 1).toDouble() / steps)
                sensorProcessor.handleRotationVector(orientation.toRotationVector())
            }
        }

        val leanDegrees = 20.0
        sendPath(50) { fraction ->
            rotationZ(Math.toRadians(90.0 * fraction)) *
                uprightMount *
                rotationZ(Math.toRadians(leanDegrees * fraction))
        }
        repeat(30) {
            sensorProcessor.handleRotationVector(orientation.toRotationVector())
        }
        assertEquals(-leanDegrees, leanUpdates.last(), 0.2)

        // Die Fahrtrichtung ändert sich weiter, während das Motorrad konstant geneigt bleibt.
        sendPath(50) { fraction ->
            rotationZ(Math.toRadians(90.0 + 90.0 * fraction)) *
                uprightMount *
                rotationZ(Math.toRadians(leanDegrees))
        }
        repeat(30) {
            sensorProcessor.handleRotationVector(orientation.toRotationVector())
        }
        assertEquals(-leanDegrees, leanUpdates.last(), 0.2)

        // Beim Kurvenausgang richtet sich das Motorrad bei weiterer Yaw-Drehung auf.
        sendPath(50) { fraction ->
            rotationZ(Math.toRadians(180.0 + 90.0 * fraction)) *
                uprightMount *
                rotationZ(Math.toRadians(leanDegrees * (1.0 - fraction)))
        }
        repeat(50) {
            sensorProcessor.handleRotationVector(orientation.toRotationVector())
        }
        assertEquals(0.0, leanUpdates.last(), 0.2)
    }

    @Test
    fun `device Z gyro integration worsens recorded curve and recovery`() {
        val fixtureNames = listOf(
            "post_turn_left_imu_before_exit_06s.csv",
            "post_turn_left_imu_00_06.csv",
            "post_turn_left_imu_06_12.csv",
            "post_turn_left_imu_12_18.csv"
        )
        val samples = fixtureNames.flatMap { fixtureName ->
            javaClass.getResourceAsStream(
                "/com/dpm/pegdown/sensor/$fixtureName"
            )!!.bufferedReader().useLines { lines ->
                lines.drop(1).map { line ->
                    line.split(';').map(String::toDouble)
                }.toList()
            }
        }.sortedBy { it[0] }

        val calibration = TestQuaternion(
            w = 0.7269597346462376,
            x = 0.26035285947439596,
            y = 0.49928473185726985,
            z = -0.3930148716025785
        )
        val referenceGravityX =
            2.0 * (calibration.x * calibration.z - calibration.w * calibration.y)
        val referenceGravityY =
            2.0 * (calibration.y * calibration.z + calibration.w * calibration.x)

        fun bankAngleFromDeviceXY(x: Double, y: Double): Double =
            Math.toDegrees(
                kotlin.math.atan2(
                    referenceGravityX * y - referenceGravityY * x,
                    referenceGravityX * x + referenceGravityY * y
                )
            )

        fun rotationVectorBank(row: List<Double>): Double {
            val x = row[2]
            val y = row[3]
            val z = row[4]
            val w = row[5]
            val gravityX = 2.0 * (x * z - w * y)
            val gravityY = 2.0 * (y * z + w * x)
            return bankAngleFromDeviceXY(gravityX, gravityY)
        }

        val rotationSamples = samples.filter { it[1].toInt() == 15 }
        var candidateAngle = rotationVectorBank(rotationSamples.first())
        var previousGyroscopeTime = rotationSamples.first()[0]
        var previousAccelerometerTime = rotationSamples.first()[0]
        val candidateByTime = mutableMapOf<Double, Double>()

        // Experimental only: each physical sensor stream keeps its own timestamp.
        // Integrate device-Z gyro rate and correct toward accelerometer tilt only
        // when acceleration magnitude is close to gravity.
        for (sample in samples.filter { it[0] >= rotationSamples.first()[0] }) {
            when (sample[1].toInt()) {
                4 -> {
                    val dt = (sample[0] - previousGyroscopeTime).coerceAtLeast(0.0)
                    candidateAngle -= Math.toDegrees(sample[4] * dt)
                    previousGyroscopeTime = sample[0]
                }
                1 -> {
                    val dt = (sample[0] - previousAccelerometerTime).coerceAtLeast(0.0)
                    val x = sample[2]
                    val y = sample[3]
                    val z = sample[4]
                    val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
                    if (kotlin.math.abs(magnitude - 9.81) <= 0.8) {
                        val accelerationAngle = bankAngleFromDeviceXY(x, y)
                        val correction = 1.0 - kotlin.math.exp(-dt / 2.0)
                        candidateAngle += correction * (accelerationAngle - candidateAngle)
                    }
                    previousAccelerometerTime = sample[0]
                }
                15 -> candidateByTime[sample[0]] = candidateAngle
            }
        }

        fun meanAbsolute(
            startSeconds: Double,
            endSeconds: Double,
            valueAt: (List<Double>) -> Double
        ): Double =
            rotationSamples
                .filter { it[0] >= startSeconds && it[0] < endSeconds }
                .map { kotlin.math.abs(valueAt(it)) }
                .average()

        val baselineCurve = meanAbsolute(-6.0, 0.0) { it[6] }
        val candidateCurve = meanAbsolute(-6.0, 0.0) { candidateByTime.getValue(it[0]) }
        val baselineEarlyRecovery = meanAbsolute(0.0, 6.0) { it[6] }
        val candidateEarlyRecovery =
            meanAbsolute(0.0, 6.0) { candidateByTime.getValue(it[0]) }
        val baselineMidRecovery = meanAbsolute(6.0, 12.0) { it[6] }
        val candidateMidRecovery =
            meanAbsolute(6.0, 12.0) { candidateByTime.getValue(it[0]) }
        val baselineLateRecovery = meanAbsolute(12.0, 18.0) { it[6] }
        val candidateLateRecovery =
            meanAbsolute(12.0, 18.0) { candidateByTime.getValue(it[0]) }

        assertTrue(
            "This naive candidate unexpectedly improved the turn: " +
                "candidate=$candidateCurve°, baseline=$baselineCurve°",
            candidateCurve > baselineCurve + 1.0
        )
        assertTrue(
            "Expected no meaningful early recovery improvement: " +
                "candidate=$candidateEarlyRecovery°, baseline=$baselineEarlyRecovery°",
            candidateEarlyRecovery >= baselineEarlyRecovery
        )
        assertTrue(
            "Expected increased middle recovery residual: " +
                "candidate=$candidateMidRecovery°, baseline=$baselineMidRecovery°",
            candidateMidRecovery > baselineMidRecovery
        )
        assertTrue(
            "Expected increased late recovery residual: " +
                "candidate=$candidateLateRecovery°, baseline=$baselineLateRecovery°",
            candidateLateRecovery > baselineLateRecovery
        )
    }

    @Test
    fun `recorded left turn exit replays the logged lean response`() {
        val replayOutput = mutableListOf<Double>()
        every {
            listener.onLeanAngleUpdate(any(), any(), any(), any(), any())
        } answers {
            replayOutput.add(firstArg())
        }

        // The fixture keeps every fourth IMU sample (~68 ms apart).
        // Match the production EMA's 15% update at the original ~17 ms cadence.
        sensorProcessor.smoothingAlpha = 0.47799375

        val calibration = TestQuaternion(
            w = 0.7269597346462376,
            x = 0.26035285947439596,
            y = 0.49928473185726985,
            z = -0.3930148716025785
        )
        sensorProcessor.start()
        repeat(15) { sensorProcessor.handleRotationVector(calibration.toRotationVector()) }
        sensorProcessor.calibrate()
        repeat(30) { sensorProcessor.handleRotationVector(calibration.toRotationVector()) }
        assertTrue(sensorProcessor.isCalibrated)
        replayOutput.clear()

        val fixtureNames = listOf(
            "post_turn_left_imu_before_exit_06s.csv",
            "post_turn_left_imu_00_06.csv",
            "post_turn_left_imu_06_12.csv",
            "post_turn_left_imu_12_18.csv"
        )
        val samples = fixtureNames.flatMap { fixtureName ->
            javaClass.getResourceAsStream(
                "/com/dpm/pegdown/sensor/$fixtureName"
            )!!.bufferedReader().useLines { lines ->
                lines.drop(1).map { line ->
                    line.split(';').map(String::toDouble)
                }.toList()
            }
        }.sortedBy { it[0] }

        val samplesByType = samples.groupBy { it[1].toInt() }
        assertEquals(mapOf(1 to 352, 4 to 352, 15 to 352), samplesByType.mapValues { it.value.size })
        samplesByType.forEach { (type, typedSamples) ->
            val timestamps = typedSamples.map { it[0] }
            assertTrue(
                "Sensor $type samples must be ordered and have no gap over 150 ms",
                timestamps.zipWithNext().all { (before, after) ->
                    after > before && after - before <= 0.15
                }
            )
        }

        val rotationSamples = samples.filter { it[1].toInt() == 15 }
        rotationSamples.forEach { row ->
            sensorProcessor.handleRotationVector(
                floatArrayOf(
                    row[2].toFloat(),
                    row[3].toFloat(),
                    row[4].toFloat(),
                    row[5].toFloat()
                )
            )
        }

        assertEquals(rotationSamples.size, replayOutput.size)
        val comparable = rotationSamples.indices.filter { rotationSamples[it][0] >= 2.0 }
        val maxReplayError = comparable.maxOf { index ->
            kotlin.math.abs(replayOutput[index] - rotationSamples[index][6])
        }
        assertTrue(
            "Replay must track the recorded app output; max error=$maxReplayError°",
            maxReplayError <= 2.0
        )

        // Characterize the measured post-turn residual for future algorithm comparisons.
        val earlyMean = rotationSamples.indices
            .filter { rotationSamples[it][0] in 2.0..6.0 }
            .map { replayOutput[it] }
            .average()
        val laterMean = rotationSamples.indices
            .filter { rotationSamples[it][0] in 6.0..10.0 }
            .map { replayOutput[it] }
            .average()
        assertTrue("Expected a measurable opposite-side residual: $earlyMean°", earlyMean > 3.0)
        assertTrue("Expected the residual to decay over time: $laterMean°", laterMean < earlyMean)
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
