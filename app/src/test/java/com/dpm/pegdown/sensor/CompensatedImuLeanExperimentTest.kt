package com.dpm.pegdown.sensor

import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompensatedImuLeanExperimentTest {
    private val mounted = ImuQuaternion.axisAngle(ImuVector(1.0, 0.0, 0.0), Math.toRadians(80.0))
    private val referenceUp = mounted.inverse().rotate(ImuVector.UP)
    private val forward = CompensatedImuLeanExperiment.assumedForwardFromScreen(referenceUp)
    private fun nanos(seconds: Double) = ((seconds + 10.0) * 1e9).roundToLong()
    private fun rotation(axis: ImuVector, degrees: Double) =
        ImuQuaternion.axisAngle(axis, Math.toRadians(degrees))

    private data class Truth(
        val time: Double, val bank: Double, val speed: Double, val course: Double,
        val orientation: ImuQuaternion, val acceleration: ImuVector,
    )
    private data class Reading(val time: Double, val trueBank: Double, val bank: Double)

    private fun simulate(
        bankAt: (Double) -> Double,
        duration: Double = 40.0,
        delay: Double = 0.12,
        gyroBias: ImuVector = ImuVector.ZERO,
        noise: Boolean = false,
        movingCourse: Boolean = true,
        speedAt: (Double) -> Double = { 18.0 },
        useGps: Boolean = true,
        badCourse: Boolean = false,
        compensate: Boolean = true,
        historical: Boolean = true,
        initialBankBias: Double = 0.0,
        bearingLag: Double = 0.0,
        checkYawConsistency: Boolean = false,
        matchGpsInterval: Boolean = false,
        gpsIntervalSeconds: Double = 1.0,
        assumedCourseLag: Double = 0.0,
        bearingErrorDegrees: (Double) -> Double = { 0.0 },
    ): Pair<List<Reading>, CompensatedImuLeanExperiment> {
        val dt = 0.01
        var course = 0.0
        val truth = (0..(duration / dt).toInt()).map { i ->
            val t = i * dt
            val bank = bankAt(t)
            val speed = speedAt(t)
            val rate = if (movingCourse) 9.80665 * tan(Math.toRadians(bank)) / speed else 0.0
            val beforeRate = if (movingCourse && i > 0)
                9.80665 * tan(Math.toRadians(bankAt(t - dt))) / speedAt(t - dt) else rate
            if (i > 0) course += (beforeRate + rate) * dt / 2.0
            val pose = ImuQuaternion.axisAngle(ImuVector.UP, -course) *
                rotation(ImuVector(0.0, 1.0, 0.0), bank) * mounted
            val along = if (i > 0) (speed - speedAt(t - dt)) / dt else 0.0
            val f = ImuVector(sin(course), kotlin.math.cos(course), 0.0)
            val r = ImuVector(kotlin.math.cos(course), -sin(course), 0.0)
            val a = pose.inverse().rotate(ImuVector.UP * 9.80665 + f * along + r * (speed * rate))
            Truth(t, bank, speed, course, pose.unit(), a)
        }
        val experiment = CompensatedImuLeanExperiment(
            referenceUp, forward, truth.first().orientation *
                ImuQuaternion.axisAngle(forward, Math.toRadians(initialBankBias)), nanos(0.0),
            compensateMotion = compensate, historicalAlignment = historical,
            checkYawConsistency = checkYawConsistency,
            matchGpsInterval = matchGpsInterval,
            courseLagSeconds = assumedCourseLag,
        )
        var nextFixIndex = 0
        val output = truth.mapIndexed { index, sample ->
            if (index > 0) {
                val delta = (truth[index - 1].orientation.inverse() * sample.orientation).unit()
                val vector = ImuVector(delta.x, delta.y, delta.z)
                val omega = if (vector.length() < 1e-12) ImuVector.ZERO else
                    vector.unit() * (2.0 * atan2(vector.length(), delta.w) / dt)
                experiment.onGyroscope(nanos(sample.time), omega + gyroBias)
            }
            val vibration = if (noise) ImuVector(
                4.0 * sin(2.0 * Math.PI * 40.0 * sample.time),
                2.0 * sin(2.0 * Math.PI * 33.0 * sample.time),
                sin(2.0 * Math.PI * 37.0 * sample.time),
            ) else ImuVector.ZERO
            experiment.onAccelerometer(nanos(sample.time), sample.acceleration + vibration)
            while (useGps && nextFixIndex < truth.size &&
                truth[nextFixIndex].time + delay <= sample.time + 1e-9
            ) {
                val fixTruth = truth[nextFixIndex]
                val bearingTruth = truth[(nextFixIndex - (bearingLag / dt).roundToLong().toInt()).coerceAtLeast(0)]
                experiment.onFix(CompensatedImuLeanExperiment.Fix(
                    timestampNanos = nanos(fixTruth.time),
                    arrivalNanos = nanos(sample.time),
                    speedMps = fixTruth.speed,
                    bearingDegrees = ((Math.toDegrees(bearingTruth.course) + bearingErrorDegrees(fixTruth.time)) % 360.0 + 360.0) % 360.0,
                    bearingAccuracyDegrees = if (badCourse) 180.0 else 0.3,
                    speedAccuracyMps = 0.2,
                    positionAccuracyMetres = 3.0,
                ))
                nextFixIndex += (gpsIntervalSeconds / dt).roundToLong().toInt()
            }
            Reading(sample.time, sample.bank, experiment.chassisBankDegrees)
        }
        return output to experiment
    }

    @Test
    fun `all three body gyro axes preserve bank through yaw and pitched mounting`() {
        val (readings, experiment) = simulate(
            bankAt = { if (it < 3.0) 25.0 * it / 3.0 else if (it < 30.0) 25.0 else (25.0 * (33.0 - it) / 3.0).coerceAtLeast(0.0) },
            useGps = false,
        )
        assertTrue(readings.maxOf { abs(it.bank - it.trueBank) } < 1e-6)
        assertEquals(0, experiment.acceptedCorrections)
        assertEquals(0, experiment.gyroGaps)
    }

    @Test
    fun `compensated gravity preserves long turn despite gyro bias and vibration`() {
        val profile: (Double) -> Double = { (it * 5.0).coerceAtMost(25.0) }
        val bias = ImuVector(0.003, -0.004, 0.002)
        val (compensated, experiment) = simulate(profile, duration = 60.0, gyroBias = bias, noise = true)
        val (uncompensated, _) = simulate(profile, duration = 60.0, gyroBias = bias, noise = true, compensate = false)
        val error = compensated.filter { it.time >= 45.0 }.map { abs(it.bank - it.trueBank) }.average()
        val naiveError = uncompensated.filter { it.time >= 45.0 }.map { abs(it.bank - it.trueBank) }.average()
        println("Synthetic long turn: compensated error=$error, uncompensated error=$naiveError")
        assertTrue("Compensated error=$error", error < 1.5)
        assertTrue("Uncompensated control must show the lateral-force failure: $naiveError", naiveError > 5.0)
        assertTrue(experiment.acceptedCorrections >= 30)
    }

    @Test
    fun `real small bank on straight crosswind trajectory is retained`() {
        val (readings, experiment) = simulate(bankAt = { 8.0 }, movingCourse = false)
        assertTrue(readings.all { abs(it.bank - 8.0) < 1e-6 })
        assertTrue(experiment.acceptedCorrections > 20)
    }

    @Test
    fun `historical correction prevents delayed turn acceleration from tilting upright bike`() {
        val profile: (Double) -> Double = {
            if (it < 20.0) 20.0 else (20.0 * (20.4 - it) / 0.4).coerceIn(0.0, 20.0)
        }
        val (aligned, _) = simulate(profile, duration = 35.0, delay = 1.2)
        val (currentTimeControl, _) = simulate(profile, duration = 35.0, delay = 1.2, historical = false)
        val peak = aligned.filter { it.time in 20.5..25.0 }.maxOf { abs(it.bank) }
        val controlPeak = currentTimeControl.filter { it.time in 20.5..25.0 }.maxOf { abs(it.bank) }
        println("Synthetic delayed turn exit: aligned peak=$peak, current-time control peak=$controlPeak")
        assertTrue("Historical exit peak=$peak", peak < 1.5)
        assertTrue("Expected stale-acceleration failure in control: $controlPeak", controlPeak > 3.0)
    }

    @Test
    fun `longitudinal acceleration is removed along with lateral acceleration`() {
        val (readings, experiment) = simulate(
            bankAt = { 15.0 }, duration = 25.0,
            speedAt = { 18.0 + 0.8 * it }, gyroBias = ImuVector(0.002, 0.0, 0.002),
        )
        val error = readings.filter { it.time >= 15.0 }.map { abs(it.bank - it.trueBank) }.average()
        assertTrue("Longitudinal-plus-lateral error=$error", error < 1.0)
        assertTrue(experiment.acceptedCorrections > 10)
    }

    @Test
    fun `poor course data leaves moving gyro estimate intact without forcing upright`() {
        val (readings, experiment) = simulate(bankAt = { 25.0 }, badCourse = true)
        assertTrue(readings.all { abs(it.bank - 25.0) < 1e-6 })
        assertEquals(0, experiment.acceptedCorrections)
    }

    @Test
    fun `gyro gaps are reported instead of integrating stale rate across missing time`() {
        val experiment = CompensatedImuLeanExperiment(referenceUp, forward, mounted, nanos(0.0))
        experiment.onGyroscope(nanos(0.01), ImuVector.ZERO)
        experiment.onGyroscope(nanos(1.0), ImuVector(0.0, 0.0, 1.0))
        assertEquals(1, experiment.gyroGaps)
        assertEquals(0.0, experiment.chassisBankDegrees, 1e-9)
    }

    @Test
    fun `dropping gyro samples can alias vibration into false constant roll`() {
        fun experiment() = CompensatedImuLeanExperiment(referenceUp, forward, mounted, nanos(0.0))
        val full = experiment()
        val decimated = experiment()
        val averaged = experiment()
        var sum = ImuVector.ZERO
        for (i in 1..1080) {
            val t = i / 60.0
            // Known stationary attitude; 15 Hz angular vibration sampled at 60 Hz.
            val omega = forward * (0.02 * kotlin.math.cos(2.0 * Math.PI * 15.0 * t))
            full.onGyroscope(nanos(t), omega)
            if (i % 4 == 0) decimated.onGyroscope(nanos(t), omega)
            sum += omega
            if (i % 3 == 0) {
                averaged.onGyroscope(nanos(t), sum * (1.0 / 3.0))
                sum = ImuVector.ZERO
            }
        }
        assertEquals(0.0, full.chassisBankDegrees, 1e-6)
        assertEquals(0.0, averaged.chassisBankDegrees, 1e-6)
        assertTrue(abs(decimated.chassisBankDegrees) > 15.0)
    }

    private data class Row(val fields: List<String>) {
        val event = fields[1].toDouble()
        val arrival = fields[2].toDouble()
        fun vector() = ImuVector(fields[3].toDouble(), fields[4].toDouble(), fields[5].toDouble())
    }
    private data class ReplayOptions(
        val compensateLongitudinal: Boolean = true,
        val compensateLateral: Boolean = true,
        val initialBankOffset: Double = 0.0,
        val initialPitchOffset: Double = 0.0,
        val checkYawConsistency: Boolean = false,
        val matchGpsInterval: Boolean = false,
        val courseLagSeconds: Double = 0.0,
        val withheldReferenceTimes: Set<Long> = emptySet(),
        val gyroBias: ImuVector = ImuVector.ZERO,
        val mountYawDegrees: Double = 0.0,
        val allowSlowCourseCorrections: Boolean = false,
    )

    private data class ReplayReading(val time: Double, val baseline: Double, val candidate: Double, val axisBank: Double)
    /** Receipt time identifies when a past-state correction changes current bank. */
    private data class BankChange(
        val arrival: Double, val event: Double, val type: String,
        val before: Double, val after: Double,
        val decision: CompensatedImuLeanExperiment.CorrectionDecision?,
    )
    private data class ReplayResult(
        val readings: List<ReplayReading>,
        val corrections: Int,
        val rejected: Int,
        val gaps: Int,
        val decisions: List<CompensatedImuLeanExperiment.CorrectionDecision>,
        val bankChanges: List<BankChange>,
    )

    private fun gps(row: Row) = CompensatedImuLeanExperiment.Fix(
        nanos(row.event), nanos(row.arrival),
        row.fields[6].toDoubleOrNull() ?: Double.NaN,
        row.fields[7].toDoubleOrNull() ?: Double.NaN,
        row.fields[8].toDoubleOrNull() ?: Double.NaN,
        row.fields[9].toDoubleOrNull() ?: Double.NaN,
        row.fields[10].toDoubleOrNull() ?: Double.NaN,
        row.fields[11].toBoolean(), row.fields[12].toBoolean(), row.fields[13],
    )

    private fun resource(path: String) = javaClass.getResourceAsStream(
        "/com/dpm/pegdown/sensor/$path"
    ) ?: error("Missing fixture: $path")

    private fun replayNew(name: String, parts: Int, options: ReplayOptions = ReplayOptions()): ReplayResult {
        var metadata: List<String>? = null
        val rows = (0 until parts).flatMap { part ->
            val base64 = resource("imu_gps_20261005_${name}_$part.csv.gz.b64")
                .bufferedReader().use { it.readText().trim() }
            GZIPInputStream(Base64.getDecoder().decode(base64).inputStream())
                .bufferedReader().useLines { lines ->
                    val content = lines.toList()
                    val meta = content.first().split(';')
                    if (metadata == null) metadata = meta else assertEquals(metadata, meta)
                    content.drop(1).filter { it.isNotBlank() }.map { Row(it.split(';')) }
                }
        }
        val meta = metadata!!
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val up = ImuVector(-0.9275271611854533, 0.0011311997772454063, 0.3737540443263699)
        return replay(rows, up, seed, meta[1].toDouble(), options = options)
    }

    private fun replayOld(): ReplayResult {
        val names = listOf("before_exit_06s", "00_06", "06_12", "12_18")
        val oldRows = names.flatMap { name ->
            resource("post_turn_left_imu_$name.csv").bufferedReader().useLines { lines ->
                lines.drop(1).map { it.split(';') }.toList()
            }
        }.sortedBy { it[0].toDouble() }
        val seedRow = oldRows.first { it[1] == "15" }
        val seed = ImuQuaternion(seedRow[5].toDouble(), seedRow[2].toDouble(), seedRow[3].toDouble(), seedRow[4].toDouble())
        val calibration = ImuQuaternion(0.7269597346462376, 0.26035285947439596, 0.49928473185726985, -0.3930148716025785)
        val rows = oldRows.map { f ->
            val type = when (f[1]) { "1" -> "a"; "4" -> "w"; else -> "r" }
            Row(listOf(type, f[0], f[0], if (type == "r") f[6] else f[2], f[3], f[4]))
        } + resource("imu_gps_20261004_left_exit_locations.csv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { Row(it.split(';')) }.toList()
        }
        return replay(rows, calibration.inverse().rotate(ImuVector.UP), seed, seedRow[0].toDouble())
    }

    private fun replay(
        rows: List<Row>, up: ImuVector, seed: ImuQuaternion, seedTime: Double,
        useGps: Boolean = true,
        options: ReplayOptions = ReplayOptions(),
    ): ReplayResult {
        val vehicleForward = rotation(up, options.mountYawDegrees).rotate(
            CompensatedImuLeanExperiment.assumedForwardFromScreen(up)
        )
        val vehicleRight = vehicleForward.cross(up).unit()
        val perturbedSeed = seed *
            ImuQuaternion.axisAngle(vehicleForward, Math.toRadians(options.initialBankOffset)) *
            ImuQuaternion.axisAngle(vehicleRight, Math.toRadians(options.initialPitchOffset))
        val experiment = CompensatedImuLeanExperiment(
            up, vehicleForward, perturbedSeed, nanos(seedTime),
            compensateLongitudinal = options.compensateLongitudinal,
            compensateLateral = options.compensateLateral,
            checkYawConsistency = options.checkYawConsistency,
            allowSlowCourseCorrections = options.allowSlowCourseCorrections,
            matchGpsInterval = options.matchGpsInterval,
            courseLagSeconds = options.courseLagSeconds,
            withheldReferenceTimes = options.withheldReferenceTimes,
            gyroBias = options.gyroBias,
        )
        var display = experiment.leanDegrees
        var bankDisplay = experiment.chassisBankDegrees
        var displayTime = seedTime
        val output = mutableListOf<ReplayReading>()
        val changes = mutableListOf<BankChange>()
        val initialBank = experiment.chassisBankDegrees
        rows.sortedBy { it.arrival }.forEach { row ->
            val before = experiment.chassisBankDegrees
            val previousAccepted = experiment.acceptedCorrections
            when (row.fields[0]) {
                "w" -> {
                    experiment.onGyroscope(nanos(row.event), row.vector())
                    if (row.event > displayTime) {
                        // Same time constant as the app's 15% EMA at about 59 Hz.
                        val alpha = 1.0 - exp(-(row.event - displayTime) / 0.105)
                        display += alpha * (experiment.leanDegrees - display)
                        bankDisplay += alpha * (experiment.chassisBankDegrees - bankDisplay)
                        displayTime = row.event
                    }
                }
                "a" -> experiment.onAccelerometer(nanos(row.event), row.vector())
                "g" -> if (useGps) experiment.onFix(gps(row))
                "r" -> output.add(ReplayReading(row.event, row.fields[3].toDouble(), display, bankDisplay))
            }
            if (row.fields[0] == "w" || (row.fields[0] == "g" && useGps)) {
                val decision = if (experiment.acceptedCorrections > previousAccepted)
                    experiment.decisions.last() else null
                changes.add(BankChange(
                    row.arrival, row.event, row.fields[0], before,
                    experiment.chassisBankDegrees, decision,
                ))
                decision?.gravityTrace?.let { trace ->
                    assertEquals(before, trace.currentBankBefore, 1e-9)
                    assertEquals(experiment.chassisBankDegrees, trace.currentBankAfter!!, 1e-9)
                }
            }
        }
        // Exact signed changes telescope, independently of the display EMA.
        assertEquals(
            experiment.chassisBankDegrees - initialBank,
            changes.sumOf { it.after - it.before }, 1e-8,
        )
        assertEquals(experiment.acceptedCorrections, changes.count { it.decision != null })
        assertTrue(output.size >= 200)
        assertTrue(output.all { it.candidate.isFinite() && abs(it.candidate) <= 180.0 })
        return ReplayResult(output, experiment.acceptedCorrections, experiment.rejectedCorrections, experiment.gyroGaps, experiment.decisions, changes)
    }

    private fun fullRateOldRows(): Pair<List<String>, List<Row>> {
        var metadata: List<String>? = null
        val rows = (0..11).flatMap { part ->
            val base64 = resource("imu_gps_20261004_left_exit_raw_$part.csv.gz.b64")
                .bufferedReader().use { it.readText().trim() }
            GZIPInputStream(Base64.getDecoder().decode(base64).inputStream())
                .bufferedReader().useLines { lines ->
                    val content = lines.toList()
                    val meta = content.first().split(';')
                    if (metadata == null) metadata = meta else assertEquals(metadata, meta)
                    content.drop(1).filter { it.isNotBlank() }.map { Row(it.split(';')) }
                }
        }
        return metadata!! to rows
    }

    private fun averageCausally(rows: List<Row>): List<Row> {
        val averaged = listOf("w" to 3, "a" to 6).flatMap { (type, count) ->
            rows.filter { it.fields[0] == type }.sortedBy { it.event }.chunked(count)
                .filter { it.size == count }.map { group ->
                    val mean = group.fold(ImuVector.ZERO) { total, row -> total + row.vector() } * (1.0 / count)
                    val last = group.last()
                    Row(listOf(type, last.fields[1], last.fields[2], mean.x.toString(), mean.y.toString(), mean.z.toString()))
                }
        }
        return averaged + rows.filter { it.fields[0] !in listOf("w", "a") }
    }

    private fun sparseSelectionWithOriginalReceipt(rawRows: List<Row>): List<Row> {
        val names = listOf("before_exit_06s", "00_06", "06_12", "12_18")
        val sparse = names.flatMap { name ->
            resource("post_turn_left_imu_$name.csv").bufferedReader().useLines { lines ->
                lines.drop(1).map { it.split(';') }.toList()
            }
        }
        val offsets = mutableListOf<Double>()
        val selection = sparse.mapNotNull { fields ->
            val type = when (fields[1]) { "1" -> "a"; "4" -> "w"; else -> "r" }
            val event = fields[0].toDouble()
            val candidates = rawRows.filter { it.fields[0] == type }
            if (event < candidates.minOf { it.event } - 0.00051 ||
                event > candidates.maxOf { it.event } + 0.00051
            ) return@mapNotNull null // Original rounded excerpt edges may extend beyond this raw window.
            // The old fixture rounds its time axis; identify retained samples by
            // their original values instead of silently widening a timestamp tolerance.
            val matches = candidates.filter { row ->
                if (type == "r") row.fields[3].toDouble() == fields[6].toDouble()
                else row.fields[3].toDouble() == fields[2].toDouble() &&
                    row.fields[4].toDouble() == fields[3].toDouble() &&
                    row.fields[5].toDouble() == fields[4].toDouble()
            }
            assertTrue("No original value match for $type at $event", matches.isNotEmpty())
            val nearest = matches.minBy { abs(it.event - event) }
            offsets.add(abs(nearest.event - event))
            if (type != "r") {
                assertEquals(fields[2].toDouble(), nearest.fields[3].toDouble(), 1e-7)
                assertEquals(fields[3].toDouble(), nearest.fields[4].toDouble(), 1e-7)
                assertEquals(fields[4].toDouble(), nearest.fields[5].toDouble(), 1e-7)
            }
            nearest
        }
        assertEquals(1056, selection.size)
        assertTrue(offsets.all { it <= 0.001 })
        println("Old source matching: samples=${selection.size} maximum time-axis difference=${offsets.maxOrNull()} s")
        return selection + rawRows.filter { it.fields[0] == "g" }
    }

    @Test
    fun `full rate old recording separates sample selection from correction rejection`() {
        val (meta, rows) = fullRateOldRows()
        assertEquals(1404, rows.count { it.fields[0] == "w" })
        assertEquals(2809, rows.count { it.fields[0] == "a" })
        assertEquals(1404, rows.count { it.fields[0] == "r" })
        assertEquals(15, rows.count { it.fields[0] == "g" })
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val calibration = ImuQuaternion(0.7269597346462376, 0.26035285947439596, 0.49928473185726985, -0.3930148716025785)
        val up = calibration.inverse().rotate(ImuVector.UP)
        val variants = listOf(
            "original decimated" to replayOld(),
            "same sparse samples, original receipt" to replay(sparseSelectionWithOriginalReceipt(rows), up, seed, meta[1].toDouble()),
            "full rate" to replay(rows, up, seed, meta[1].toDouble()),
            "causal averages" to replay(averageCausally(rows), up, seed, meta[1].toDouble()),
            "full rate gyro only" to replay(rows, up, seed, meta[1].toDouble(), useGps = false),
        )
        val raw = variants.single { it.first == "full rate" }.second
        val averaged = variants.single { it.first == "causal averages" }.second
        fun magnitude(result: ReplayResult) = result.readings.filter { it.time in 2.0..10.0 }
            .map { abs(it.candidate) }.average()
        // Recorded behaviour regression, not accuracy against known chassis angle.
        assertEquals(7, raw.corrections)
        assertEquals(2, raw.decisions.count { it.reason == CompensatedImuLeanExperiment.DecisionReason.CHANGING_YAW })
        assertTrue(abs(magnitude(raw) - magnitude(averaged)) < 0.25)
        variants.forEach { (name, result) ->
            val samples = result.readings.filter { it.time in 2.0..10.0 }
            val magnitude = samples.map { abs(it.candidate) }.average()
            val reasons = result.decisions.groupingBy { it.reason }.eachCount()
            println("Mount old $name: projected=$magnitude axisBank=${samples.map { abs(it.axisBank) }.average()} meanDifference=${samples.map { abs(it.candidate - it.axisBank) }.average()}")
            println("Old sampling $name: magnitude=$magnitude corrections=${result.corrections} rejected=${result.rejected} gaps=${result.gaps} reasons=$reasons")
            result.decisions.filter { it.reason.rejected }.forEach { decision ->
                println("Old rejection $name: time=${decision.timestampNanos / 1e9 - 10.0} reason=${decision.reason} roll=${decision.maxRollRate} yawRange=${decision.yawRateRange} gravity=${decision.gravityMagnitude} innovation=${decision.innovationDegrees}")
            }
            assertEquals(0, result.gaps)
            assertEquals(result.rejected, result.decisions.count { it.reason.rejected })
            assertEquals(result.corrections, result.decisions.count { it.reason == CompensatedImuLeanExperiment.DecisionReason.ACCEPTED })
        }
    }

    @Test
    fun `known initial tilt error converges in ideal motion without requiring upright bank`() {
        val (readings, experiment) = simulate(
            bankAt = { 25.0 }, duration = 40.0, initialBankBias = 5.0,
        )
        val lateError = readings.filter { it.time >= 30.0 }.map { abs(it.bank - it.trueBank) }.average()
        println("Gravity synthetic initial-bias convergence: lateError=$lateError")
        assertTrue(lateError < 0.2)
        assertTrue(experiment.acceptedCorrections > 20)
    }

    @Test
    fun `internally delayed GNSS course causes false bank despite historical timestamp alignment`() {
        val profile: (Double) -> Double = {
            if (it < 20.0) 20.0 else (20.0 * (20.4 - it) / 0.4).coerceIn(0.0, 20.0)
        }
        val (unguarded, _) = simulate(profile, duration = 35.0, bearingLag = 2.0)
        val (guarded, experiment) = simulate(
            profile, duration = 35.0, bearingLag = 2.0, checkYawConsistency = true,
        )
        val falsePeak = unguarded.filter { it.time in 21.0..25.0 }.maxOf { abs(it.bank) }
        val guardedPeak = guarded.filter { it.time in 21.0..25.0 }.maxOf { abs(it.bank) }
        println("Gravity synthetic delayed-bearing: unguardedPeak=$falsePeak guardedPeak=$guardedPeak")
        assertTrue(falsePeak > 3.0)
        // Small course discrepancies below the declared-error allowance still
        // pass: this gate limits the failure, it cannot guarantee zero bank error.
        assertTrue("Expected reduction of the large false correction", guardedPeak < falsePeak / 2.0)
        assertTrue(experiment.decisions.any { it.reason == CompensatedImuLeanExperiment.DecisionReason.COURSE_GYRO_MISMATCH })
        // Known truth in this simulation only; this does not label real ride angles.
    }

    @Test
    fun `course consistency accepts ideal sustained bank without forcing upright`() {
        val (readings, experiment) = simulate(
            bankAt = { 25.0 }, checkYawConsistency = true,
        )
        assertTrue(readings.all { abs(it.bank - 25.0) < 0.01 })
        assertTrue(experiment.acceptedCorrections > 20)
        assertEquals(0, experiment.decisions.count {
            it.reason == CompensatedImuLeanExperiment.DecisionReason.COURSE_GYRO_MISMATCH
        })
    }

    @Test
    fun `gravity trace decomposes measurement movement and remaining gravity`() {
        val (_, experiment) = simulate(
            bankAt = { 15.0 }, duration = 20.0, speedAt = { 18.0 + 0.8 * it },
        )
        val accepted = experiment.decisions.filter {
            it.reason == CompensatedImuLeanExperiment.DecisionReason.ACCEPTED &&
                it.timestampNanos >= nanos(10.0)
        }
        assertTrue(accepted.size >= 5)
        accepted.forEach { decision ->
            val trace = decision.gravityTrace!!
            val subtraction = trace.measured - trace.predictedMovement
            assertEquals(subtraction.x, trace.inferredGravity.x, 1e-12)
            assertEquals(subtraction.y, trace.inferredGravity.y, 1e-12)
            assertEquals(subtraction.z, trace.inferredGravity.z, 1e-12)
            assertEquals(15.0, trace.inferredBank, 0.1)
            assertEquals(9.80665, trace.inferredGravity.length(), 0.02)
            assertEquals(trace.courseRate, trace.meanGyroYawRate!!, 0.005)
            assertTrue(trace.currentBankAfter!!.isFinite())
        }
    }

    @Test
    fun `recorded gravity components and initial state sensitivity remain diagnostic only`() {
        val (meta, rows) = fullRateOldRows()
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val calibration = ImuQuaternion(0.7269597346462376, 0.26035285947439596, 0.49928473185726985, -0.3930148716025785)
        val up = calibration.inverse().rotate(ImuVector.UP)
        val controls = listOf(
            "complete" to ReplayOptions(),
            "no longitudinal subtraction" to ReplayOptions(compensateLongitudinal = false),
            "no lateral subtraction" to ReplayOptions(compensateLateral = false),
            "initial bank plus5" to ReplayOptions(initialBankOffset = 5.0),
            "initial bank minus5" to ReplayOptions(initialBankOffset = -5.0),
            "initial pitch plus5" to ReplayOptions(initialPitchOffset = 5.0),
            "course gyro check" to ReplayOptions(checkYawConsistency = true),
        )
        controls.forEach { (name, options) ->
            val cases = listOf(
                Triple("Oct04 full rate", replay(rows, up, seed, meta[1].toDouble(), options = options), 2.0..10.0),
                Triple("Oct05 faster", replayNew("fast_left_exit", 4, options), 17.0..25.0),
            )
            cases.forEach { (label, result, window) ->
                val samples = result.readings.filter { it.time in window }
                println("Gravity control $label $name: bankMagnitude=${samples.map { abs(it.axisBank) }.average()} signedBank=${samples.map { it.axisBank }.average()} corrections=${result.corrections}")
                if (label == "Oct05 faster") {
                    val post = result.readings.filter { it.time in 25.0..27.0 }
                    println("Gravity between-turn25to27 $name: recordedMagnitude=${post.map { abs(it.baseline) }.average()} bankMagnitude=${post.map { abs(it.axisBank) }.average()} peak=${post.maxOf { abs(it.axisBank) }}")
                }
                if (name == "course gyro check") {
                    result.decisions.filter {
                        it.reason == CompensatedImuLeanExperiment.DecisionReason.COURSE_GYRO_MISMATCH
                    }.forEach {
                        println("Gravity mismatch $label: time=${it.timestampNanos / 1e9 - 10.0} gps=${it.courseRate} gyro=${it.meanGyroYawRate} limit=${it.yawDiscrepancyLimit}")
                    }
                }
                if (name == "complete") {
                    if (label == "Oct05 faster") {
                        result.readings.filter { it.time in 17.0..30.0 }
                            .groupBy { (it.time * 2).toInt() }.toSortedMap().forEach { (bin, entries) ->
                                println("Gravity fast timeline: time=${bin / 2.0} recorded=${entries.map { it.baseline }.average()} bank=${entries.map { it.axisBank }.average()}")
                            }
                    }
                    result.decisions.filter {
                        it.gravityTrace != null && it.timestampNanos / 1e9 - 10.0 in window
                    }.forEach { decision ->
                        val t = decision.gravityTrace!!
                        println("Gravity trace $label: time=${decision.timestampNanos / 1e9 - 10.0} reason=${decision.reason} along=${t.vehicleAcceleration.x} lateral=${t.vehicleAcceleration.y} gpsYaw=${t.courseRate} gyroYaw=${t.meanGyroYawRate} beforeBank=${t.predictedBank} rawAccelBank=${t.measuredBank} inferredBank=${t.inferredBank} beforePitch=${t.predictedPitch} inferredPitch=${t.inferredPitch} gain=${t.gain} currentBankBefore=${t.currentBankBefore} currentBankAfter=${t.currentBankAfter}")
                    }
                }
                assertEquals(0, result.gaps)
                assertTrue(samples.all { it.axisBank.isFinite() })
            }
        }
    }

    private fun continuousOldRows(): Pair<List<String>, List<Row>> {
        var metadata: List<String>? = null
        val rows = (0..10).flatMap { part ->
            val encoded = resource("imu_gps_20261004_continuous_$part.csv.gz.b64")
                .bufferedReader().use { it.readText().trim() }
            GZIPInputStream(Base64.getDecoder().decode(encoded).inputStream())
                .bufferedReader().useLines { lines ->
                    val content = lines.toList()
                    val meta = content.first().split(';')
                    if (metadata == null) metadata = meta else assertEquals(metadata, meta)
                    content.drop(1).filter { it.isNotBlank() }.map { Row(it.split(';')) }
                }
        }
        return metadata!! to rows
    }

    @Test
    fun `continuous source has unique timestamps and no gaps across fixture boundaries`() {
        val (_, rows) = continuousOldRows()
        assertEquals(1637, rows.count { it.fields[0] == "w" })
        assertEquals(1637, rows.count { it.fields[0] == "a" })
        assertEquals(819, rows.count { it.fields[0] == "r" })
        assertEquals(47, rows.count { it.fields[0] == "g" })
        listOf("w", "a", "r", "g").forEach { type ->
            val events = rows.filter { it.fields[0] == type }.map { it.event }.sorted()
            assertEquals(events.size, events.distinct().size)
            if (type == "w" || type == "a") {
                assertTrue(events.last() - events.first() > 83.8)
                assertTrue(events.zipWithNext().all { (before, after) -> after - before < 0.065 })
            }
        }
    }

    @Test
    fun `preceding ride history is compared with restart using identical later inputs`() {
        val (meta, rows) = continuousOldRows()
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val up = ImuVector(-0.9305648836431455, -0.013920558292233265, 0.36586229019543914)
        // This fixture shifts the old excerpt origin by +66 seconds.
        // At second 60 it reaches the original short excerpt's start.
        val restartRow = rows.filter { it.fields[0] == "r" && it.event >= 60.0 }.minBy { it.event }
        val restartSeed = ImuQuaternion(
            restartRow.fields[9].toDouble(), restartRow.fields[6].toDouble(),
            restartRow.fields[7].toDouble(), restartRow.fields[8].toDouble(),
        )
        val afterRestart = rows.filter { it.event >= restartRow.event && it.arrival >= restartRow.arrival }
        val continuous = replay(rows, up, seed, meta[1].toDouble())
        val restarted = replay(afterRestart, up, restartSeed, restartRow.event)
        val variants = listOf(
            "continuous" to continuous,
            "restart at60" to restarted,
            "continuous initial bank plus5" to replay(rows, up, seed, meta[1].toDouble(), options = ReplayOptions(initialBankOffset = 5.0)),
            "continuous initial bank minus5" to replay(rows, up, seed, meta[1].toDouble(), options = ReplayOptions(initialBankOffset = -5.0)),
            "continuous initial pitch plus5" to replay(rows, up, seed, meta[1].toDouble(), options = ReplayOptions(initialPitchOffset = 5.0)),
            "continuous gyro only" to replay(rows, up, seed, meta[1].toDouble(), useGps = false),
        )
        println("History source: seedTime=${meta[1]} restartTime=${restartRow.event} w=${rows.count { it.fields[0] == "w" }} a=${rows.count { it.fields[0] == "a" }}")
        variants.forEach { (name, result) ->
            val sample = result.readings.filter { it.time in 68.0..76.0 }
            assertTrue(sample.size > 70)
            assertEquals(0, result.gaps)
            println("History control $name: recordedMagnitude=${sample.map { abs(it.baseline) }.average()} bankMagnitude=${sample.map { abs(it.axisBank) }.average()} signedBank=${sample.map { it.axisBank }.average()} peak=${sample.maxOf { abs(it.axisBank) }} corrections=${result.corrections} rejected=${result.rejected} reasons=${result.decisions.groupingBy { it.reason }.eachCount()}")
        }
        val commonTimes = continuous.readings.filter { it.time in 68.0..76.0 }.map { it.time }
        assertEquals(commonTimes, restarted.readings.filter { it.time in 68.0..76.0 }.map { it.time })
        val difference = continuous.readings.filter { it.time in 68.0..76.0 }
            .zip(restarted.readings.filter { it.time in 68.0..76.0 })
            .map { (warm, reset) -> abs(warm.axisBank - reset.axisBank) }.average()
        println("History matched output difference=$difference")
        continuous.decisions.filter { it.timestampNanos / 1e9 - 10.0 in 60.0..78.0 }
            .forEach { d ->
                println("History decision: time=${d.timestampNanos / 1e9 - 10.0} reason=${d.reason} predictedPitch=${d.gravityTrace?.predictedPitch} inferredPitch=${d.gravityTrace?.inferredPitch} currentBankAfter=${d.gravityTrace?.currentBankAfter}")
            }
        // Actual bank is unknown; smaller output must never be asserted as accuracy.
    }

    @Test
    fun `time weighted reference integrates irregular sampling and clipped endpoints`() {
        val times = listOf(0.0, 0.03, 0.10, 0.20, 0.30, 0.40, 0.50)
        val samples = times.map { nanos(it) to ImuVector(it, 2.0 * it, 9.80665) }
        val mean = CompensatedImuLeanExperiment.timeWeightedMean(samples, nanos(0.05), nanos(0.45))!!
        assertEquals(0.25, mean.x, 1e-9)
        assertEquals(0.50, mean.y, 1e-9)
        assertEquals(9.80665, mean.z, 1e-9)
    }

    @Test
    fun `time weighted reference refuses missing coverage gaps and reversed timestamps`() {
        val samples = listOf(0.0, 0.1, 0.2).map { nanos(it) to ImuVector.UP }
        assertEquals(null, CompensatedImuLeanExperiment.timeWeightedMean(samples, nanos(-0.01), nanos(0.1)))
        assertEquals(null, CompensatedImuLeanExperiment.timeWeightedMean(samples, nanos(0.0), nanos(0.21)))
        assertEquals(null, CompensatedImuLeanExperiment.timeWeightedMean(samples, nanos(0.1), nanos(0.1)))
        assertEquals(null, CompensatedImuLeanExperiment.timeWeightedMean(samples.reversed(), nanos(0.0), nanos(0.2)))
        val gap = listOf(0.0, 0.2, 0.3).map { nanos(it) to ImuVector.UP }
        assertEquals(null, CompensatedImuLeanExperiment.timeWeightedMean(gap, nanos(0.05), nanos(0.25)))
    }

    @Test
    fun `whole GNSS interval limits false corrections in gently varying known bank`() {
        val profile: (Double) -> Double = { 15.0 + 6.0 * sin(Math.PI * it / 2.0) }
        val (midpoint, control) = simulate(profile, duration = 40.0, gpsIntervalSeconds = 2.0)
        val (matched, experiment) = simulate(
            profile, duration = 40.0, gpsIntervalSeconds = 2.0, matchGpsInterval = true,
        )
        val controlError = midpoint.filter { it.time >= 10.0 }.map { abs(it.bank - it.trueBank) }.average()
        val matchedError = matched.filter { it.time >= 10.0 }.map { abs(it.bank - it.trueBank) }.average()
        println("Interval synthetic transitions: midpointError=$controlError intervalError=$matchedError midpointAccepted=${control.acceptedCorrections} intervalAccepted=${experiment.acceptedCorrections}")
        assertTrue(control.acceptedCorrections > 10)
        assertTrue(experiment.acceptedCorrections > 10)
        assertTrue("Matched error=$matchedError", matchedError < 0.3)
        assertTrue("Expected timing-mismatch reduction", matchedError < controlError / 2.0)
        // Stable turn control covers transport through changing world heading.
        val (steady, steadyExperiment) = simulate(
            bankAt = { 25.0 }, duration = 30.0, matchGpsInterval = true,
        )
        assertTrue(steady.maxOf { abs(it.bank - it.trueBank) } < 0.05)
        assertTrue(steadyExperiment.acceptedCorrections > 20)
    }

    @Test
    fun `interval averaging is evaluated against all recorded windows without accuracy labels`() {
        val (meta, oldRows) = continuousOldRows()
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val oldUp = ImuVector(-0.9305648836431455, -0.013920558292233265, 0.36586229019543914)
        val cases = listOf(
            Triple("Oct04 continuous", "continuous", 68.0..76.0),
            Triple("Oct05 left", "left_exit", 12.0..20.0),
            Triple("Oct05 right", "right_exit", 10.0..15.0),
            Triple("Oct05 following left", "following_left_exit", 10.0..19.0),
            Triple("Oct05 between turns", "fast_left_exit", 25.0..27.0),
        )
        cases.forEach { (label, name, window) ->
            listOf(false, true).forEach { matched ->
                val options = ReplayOptions(matchGpsInterval = matched)
                val result = if (name == "continuous") replay(
                    oldRows, oldUp, seed, meta[1].toDouble(), options = options,
                ) else replayNew(name, if (name == "fast_left_exit") 4 else 3, options)
                val samples = result.readings.filter { it.time in window }
                assertTrue(samples.size > 15)
                assertEquals(0, result.gaps)
                println("Interval recorded $label matched=$matched: bankMagnitude=${samples.map { abs(it.axisBank) }.average()} signedBank=${samples.map { it.axisBank }.average()} peak=${samples.maxOf { abs(it.axisBank) }} accepted=${result.corrections} rejected=${result.rejected} missingAcceleration=${result.decisions.count { it.reason == CompensatedImuLeanExperiment.DecisionReason.INSUFFICIENT_ACCELERATION }}")
            }
        }
        // No assertions that smaller real output means better angle accuracy.
    }

    @Test
    fun `known delayed course correction helps varying bank while wrong assumed lag harms it`() {
        // Abrupt exits are largely rejected by existing roll gates and cannot
        // expose this failure reliably. Use gently varying motion within the gates.
        val profile: (Double) -> Double = { 15.0 + 4.0 * sin(Math.PI * it / 2.0) }
        val (delayed, delayedExperiment) = simulate(
            profile, duration = 40.0, bearingLag = 0.6, matchGpsInterval = true, gpsIntervalSeconds = 2.0,
        )
        val (aligned, experiment) = simulate(
            profile, duration = 40.0, bearingLag = 0.6, assumedCourseLag = 0.6,
            matchGpsInterval = true, gpsIntervalSeconds = 2.0,
        )
        val (wrong, wrongExperiment) = simulate(
            profile, duration = 40.0, assumedCourseLag = 0.6, matchGpsInterval = true, gpsIntervalSeconds = 2.0,
        )
        fun error(values: List<Reading>) = values.filter { it.time >= 10.0 }
            .map { abs(it.bank - it.trueBank) }.average()
        println("Lag synthetic varying bank: delayedError=${error(delayed)} alignedError=${error(aligned)} wrongLagError=${error(wrong)}")
        assertTrue(error(aligned) < 0.1)
        assertTrue(error(delayed) > error(aligned) + 0.2)
        assertTrue("Wrong lag must expose its failure", error(wrong) > error(aligned) + 0.2)
        assertTrue(experiment.acceptedCorrections > 10)
        assertTrue(delayedExperiment.acceptedCorrections > 10)
        assertTrue(wrongExperiment.acceptedCorrections > 10)
    }

    @Test
    fun `lag aligned reference preserves abrupt upright exit with existing motion gates`() {
        val profile: (Double) -> Double = {
            if (it < 20.0) 20.0 else (20.0 * (20.4 - it) / 0.4).coerceIn(0.0, 20.0)
        }
        val (aligned, _) = simulate(
            profile, duration = 35.0, bearingLag = 0.6, assumedCourseLag = 0.6, matchGpsInterval = true,
        )
        val peak = aligned.filter { it.time in 20.5..26.0 }.maxOf { abs(it.bank) }
        assertTrue(peak < 0.05)
    }

    @Test
    fun `delayed bearing uses historically interpolated fresh speed during acceleration`() {
        val (readings, experiment) = simulate(
            bankAt = { 15.0 }, duration = 40.0, speedAt = { 15.0 + 0.3 * it },
            bearingLag = 0.6, assumedCourseLag = 0.6, matchGpsInterval = true,
        )
        val error = readings.filter { it.time >= 10.0 }.map { abs(it.bank - it.trueBank) }.average()
        println("Lag synthetic fresh-speed acceleration: bankError=$error")
        assertTrue(error < 0.1)
        assertTrue(experiment.acceptedCorrections > 20)
    }

    @Test
    fun `shifted reference refuses unavailable historical speed instead of extrapolating`() {
        val experiment = CompensatedImuLeanExperiment(
            referenceUp, forward, mounted, nanos(0.0), courseLagSeconds = 0.6,
        )
        experiment.onFix(CompensatedImuLeanExperiment.Fix(nanos(0.0), nanos(0.12), 18.0, 0.0, 0.3, 0.2, 3.0))
        experiment.onFix(CompensatedImuLeanExperiment.Fix(nanos(1.0), nanos(1.12), 18.0, 1.0, 0.3, 0.2, 3.0))
        assertEquals(0, experiment.acceptedCorrections)
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.MISSING_SPEED_HISTORY, experiment.decisions.last().reason)
    }

    @Test
    fun `preselected course lag compares bank outputs without fitting on their magnitudes`() {
        val (meta, oldRows) = continuousOldRows()
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val oldUp = ImuVector(-0.9305648836431455, -0.013920558292233265, 0.36586229019543914)
        val cases = listOf(
            Triple("Oct04 continuous transfer control", "continuous", 68.0..76.0),
            Triple("Oct05 left", "left_exit", 12.0..20.0),
            Triple("Oct05 right", "right_exit", 10.0..15.0),
            Triple("Oct05 following left", "following_left_exit", 10.0..19.0),
            Triple("Oct05 between turns", "fast_left_exit", 25.0..27.0),
        )
        cases.forEach { (label, name, window) ->
            listOf(false, true).forEach { interval ->
                listOf(0.0, 0.6).forEach { lag ->
                    val options = ReplayOptions(matchGpsInterval = interval, courseLagSeconds = lag)
                    val result = if (name == "continuous") replay(
                        oldRows, oldUp, seed, meta[1].toDouble(), options = options,
                    ) else replayNew(name, if (name == "fast_left_exit") 4 else 3, options)
                    val samples = result.readings.filter { it.time in window }
                    assertTrue(samples.size > 15)
                    assertEquals(0, result.gaps)
                    println("Lag recorded $label interval=$interval lag=$lag: mean=${samples.map { abs(it.axisBank) }.average()} signed=${samples.map { it.axisBank }.average()} peak=${samples.maxOf { abs(it.axisBank) }} accepted=${result.corrections} rejected=${result.rejected} missingSpeed=${result.decisions.count { it.reason == CompensatedImuLeanExperiment.DecisionReason.MISSING_SPEED_HISTORY }}")
                }
            }
        }
        // The 0.6-second hypothesis was selected from independent rate comparisons,
        // not by minimizing these bank outputs. Actual bank remains unknown.
    }

    @Test
    fun `fixed consistency rule rejects synthetic false course rotation without forcing bank zero`() {
        // Predeclared existing rule: 3 course-error scales + 0.02 rad/s.
        // Actual straight trajectory retains a real 8-degree bank.
        val falseCourse: (Double) -> Double = { Math.toDegrees(0.12 * (it - 15.0).coerceAtLeast(0.0)) }
        val (unchecked, _) = simulate(
            bankAt = { 8.0 }, movingCourse = false, matchGpsInterval = true,
            bearingErrorDegrees = falseCourse,
        )
        val (checked, experiment) = simulate(
            bankAt = { 8.0 }, movingCourse = false, matchGpsInterval = true,
            bearingErrorDegrees = falseCourse, checkYawConsistency = true,
        )
        val uncheckedError = unchecked.filter { it.time >= 25.0 }.map { abs(it.bank - it.trueBank) }.average()
        val checkedError = checked.filter { it.time >= 25.0 }.map { abs(it.bank - it.trueBank) }.average()
        println("Confidence synthetic false course: uncheckedError=$uncheckedError checkedError=$checkedError mismatches=${experiment.decisions.count { it.reason == CompensatedImuLeanExperiment.DecisionReason.COURSE_GYRO_MISMATCH }}")
        assertTrue(uncheckedError > 3.0)
        assertTrue(checkedError < 0.01)
        assertTrue(experiment.acceptedCorrections >= 10)
        assertTrue(experiment.decisions.count { it.reason == CompensatedImuLeanExperiment.DecisionReason.COURSE_GYRO_MISMATCH } >= 15)
    }

    @Test
    fun `fixed consistency rule retains valid synthetic turns and bias correction`() {
        listOf(-25.0, 25.0).forEach { bank ->
            val (readings, experiment) = simulate(
                bankAt = { bank }, duration = 45.0, initialBankBias = 3.0,
                matchGpsInterval = true, checkYawConsistency = true,
            )
            val error = readings.filter { it.time >= 30.0 }.map { abs(it.bank - it.trueBank) }.average()
            println("Confidence synthetic valid turn: bank=$bank error=$error accepted=${experiment.acceptedCorrections}")
            assertTrue(error < 0.2)
            assertTrue(experiment.acceptedCorrections > 20)
        }
    }

    @Test
    fun `fixed consistency rule compares older continuous recording without truth labels`() {
        val (meta, rows) = continuousOldRows()
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val up = ImuVector(-0.9305648836431455, -0.013920558292233265, 0.36586229019543914)
        listOf(false, true).forEach { checked ->
            val result = replay(rows, up, seed, meta[1].toDouble(), options = ReplayOptions(
                matchGpsInterval = true, checkYawConsistency = checked,
            ))
            val samples = result.readings.filter { it.time in 68.0..76.0 }
            assertTrue(samples.size > 70)
            assertEquals(0, result.gaps)
            println("Confidence recorded older checked=$checked: magnitude=${samples.map { abs(it.axisBank) }.average()} peak=${samples.maxOf { abs(it.axisBank) }} accepted=${result.corrections} mismatches=${result.decisions.count { it.reason == CompensatedImuLeanExperiment.DecisionReason.COURSE_GYRO_MISMATCH }}")
        }
    }

    @Test
    fun `older continuous replay characterizes mounting axis sensitivity without choosing zero bank`() {
        val (meta, rows) = continuousOldRows()
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val up = ImuVector(-0.9305648836431455, -0.013920558292233265, 0.36586229019543914)
        listOf(-10.0, -5.0, 0.0, 5.0, 10.0).forEach { yaw ->
            val result = replay(rows, up, seed, meta[1].toDouble(), options = ReplayOptions(
                matchGpsInterval = true, checkYawConsistency = true, mountYawDegrees = yaw,
            ))
            val samples = result.readings.filter { it.time in 68.0..76.0 }
            assertTrue(samples.size > 70)
            assertEquals(0, result.gaps)
            println("Axis older yaw=$yaw: mean=${samples.map { abs(it.axisBank) }.average()} signed=${samples.map { it.axisBank }.average()} accepted=${result.corrections}")
        }
    }

    private data class ForwardSample(val time: Double, val along: Double, val acceleration: ImuVector)
    private data class ForwardFit(val count: Int, val slope: ImuVector, val yaw: Double, val rSquared: Double)
    private fun fitForward(samples: List<ForwardSample>, up: ImuVector): ForwardFit? {
        if (samples.size < 4) return null
        val g = up.unit()
        val base = CompensatedImuLeanExperiment.assumedForwardFromScreen(g)
        val right = base.cross(g).unit()
        val meanAlong = samples.map { it.along }.average()
        val meanVector = samples.fold(ImuVector.ZERO) { sum, s -> sum + s.acceleration } * (1.0 / samples.size)
        val variance = samples.sumOf { (it.along - meanAlong) * (it.along - meanAlong) }
        if (variance / samples.size < 0.05) return null
        val fullSlope = samples.fold(ImuVector.ZERO) { sum, s ->
            sum + (s.acceleration - meanVector) * (s.along - meanAlong)
        } * (1.0 / variance)
        val slope = fullSlope - g * fullSlope.dot(g)
        if (slope.length() < 0.1) return null
        val total = samples.sumOf {
            val delta = it.acceleration - meanVector
            val planar = delta - g * delta.dot(g)
            planar.dot(planar)
        }
        val residual = samples.sumOf {
            val delta = it.acceleration - meanVector - slope * (it.along - meanAlong)
            val planar = delta - g * delta.dot(g)
            planar.dot(planar)
        }
        return ForwardFit(samples.size, slope, Math.toDegrees(atan2(slope.dot(right), slope.dot(base))),
            if (total > 1e-12) 1.0 - residual / total else 0.0)
    }

    private fun characterizeForward(label: String, rows: List<Row>, up: ImuVector) {
        val fixes = rows.filter { it.fields[0] == "g" }.sortedBy { it.event }
        val accelerations = rows.filter { it.fields[0] == "a" }.sortedBy { it.event }
        val gyros = rows.filter { it.fields[0] == "w" }.sortedBy { it.event }
        val excluded = mutableMapOf<String, Int>()
        fun reject(reason: String) { excluded[reason] = (excluded[reason] ?: 0) + 1 }
        val selected = mutableListOf<ForwardSample>()
        fixes.zipWithNext().forEach { (before, after) ->
            val a = gps(before)
            val b = gps(after)
            val dt = after.event - before.event
            fun usable(f: CompensatedImuLeanExperiment.Fix) =
                f.provider == "gps" && f.hasSpeed && f.hasBearing &&
                    f.speedMps.isFinite() && f.speedMps in 8.0..80.0 &&
                    f.speedAccuracyMps.isFinite() && f.speedAccuracyMps in 0.0..0.5 &&
                    f.bearingAccuracyDegrees.isFinite() && f.bearingAccuracyDegrees in 0.0..5.0 &&
                    f.bearingDegrees.isFinite() && f.bearingDegrees in 0.0..360.0 &&
                    f.positionAccuracyMetres.isFinite() && f.positionAccuracyMetres in 0.0..15.0 &&
                    (f.arrivalNanos - f.timestampNanos) / 1e9 in 0.0..2.5
            if (dt !in 0.5..1.5 || !usable(a) || !usable(b)) { reject("fix"); return@forEach }
            val along = (b.speedMps - a.speedMps) / dt
            val yaw = Math.toRadians(GpsAidedLeanExperiment.courseDifference(a.bearingDegrees, b.bearingDegrees)) / dt
            if (abs(along) !in 0.5..3.0 || abs(yaw) > 0.02) { reject("motion"); return@forEach }
            val w = gyros.filter { it.event in before.event..after.event }
            if (w.size < 4 || w.last().event - w.first().event < 0.7 * dt ||
                w.map { it.vector().length() }.average() > 0.08 ||
                w.maxOf { it.vector().length() } > 0.3) { reject("gyro"); return@forEach }
            val values = accelerations.filter { it.event in (before.event - 0.15)..(after.event + 0.15) }
                .map { nanos(it.event) to it.vector() }
            val mean = CompensatedImuLeanExperiment.timeWeightedMean(values, a.timestampNanos, b.timestampNanos)
            if (mean == null) { reject("accelerationCoverage"); return@forEach }
            selected.add(ForwardSample((before.event + after.event) / 2.0, along, mean))
        }
        println("Forward selection $label: intervals=${fixes.size - 1} selected=${selected.size} acceleration=${selected.count { it.along > 0 }} braking=${selected.count { it.along < 0 }} excluded=$excluded")
        val split = if (rows.isEmpty()) 0.0 else (rows.minOf { it.event } + rows.maxOf { it.event }) / 2.0
        listOf(
            "all" to selected, "acceleration" to selected.filter { it.along > 0 },
            "braking" to selected.filter { it.along < 0 },
            "firstHalf" to selected.filter { it.time < split },
            "secondHalf" to selected.filter { it.time >= split },
        ).forEach { (part, samples) ->
            println("Forward fit $label $part: result=${fitForward(samples, up)}")
        }
        // No inferred axis is used to rerun bank estimates or optimize their magnitude.
    }

    @Test
    fun `forward regression recovers signed known axis independently from constant gravity offset`() {
        val axis = rotation(referenceUp, -12.0).rotate(forward)
        val offset = referenceUp * 9.80665 + ImuVector(0.1, -0.2, 0.05)
        val samples = listOf(-2.5, -2.0, -1.5, -1.0, 1.0, 1.5, 2.0, 2.5).mapIndexed { i, a ->
            ForwardSample(i.toDouble(), a, offset + axis * a)
        }
        listOf(samples, samples.filter { it.along > 0 }, samples.filter { it.along < 0 }).forEach {
            val fit = fitForward(it, referenceUp)!!
            assertEquals(12.0, fit.yaw, 1e-9) // right-handed basis uses forward cross up.
            assertEquals(1.0, fit.slope.length(), 1e-9)
            assertEquals(1.0, fit.rSquared, 1e-9)
        }
        assertEquals(null, fitForward(samples.take(3), referenceUp))
        assertEquals(null, fitForward(samples.map { it.copy(along = 1.0) }, referenceUp))
    }

    @Test
    fun `acceleration correlated transverse contamination can produce misleading perfect axis fit`() {
        val right = forward.cross(referenceUp).unit()
        val samples = listOf(-2.5, -2.0, -1.5, -1.0, 1.0, 1.5, 2.0, 2.5).mapIndexed { i, a ->
            ForwardSample(i.toDouble(), a, referenceUp * 9.80665 + (forward + right * 0.3) * a)
        }
        val fit = fitForward(samples, referenceUp)!!
        assertEquals(Math.toDegrees(atan2(0.3, 1.0)), fit.yaw, 1e-9)
        assertEquals(1.0, fit.rSquared, 1e-9)
        // Correlation alone cannot distinguish true mounting from motion-correlated force.
    }

    @Test
    fun `older continuous input reports independent acceleration braking axis evidence`() {
        val (_, rows) = continuousOldRows()
        characterizeForward("older", rows, ImuVector(-0.9305648836431455, -0.013920558292233265, 0.36586229019543914))
    }

    private fun continuousNewSubset(): Pair<List<String>, List<Row>> {
        var metadata: List<String>? = null
        val rows = (0..10).flatMap { part ->
            val encoded = resource("imu_gps_20261005_continuous_turns_$part.csv.gz.b64")
                .bufferedReader().use { it.readText().trim() }
            GZIPInputStream(Base64.getDecoder().decode(encoded).inputStream()).bufferedReader().useLines { lines ->
                val content = lines.toList()
                val meta = content.first().split(';')
                if (metadata == null) metadata = meta else assertEquals(metadata, meta)
                content.drop(1).filter { it.isNotBlank() }.map { Row(it.split(';')) }
            }
        }
        return metadata!! to rows
    }

    @Test
    fun `portable newer continuous subset has complete averaged sensor coverage`() {
        val (meta, rows) = continuousNewSubset()
        println("Continuous subset counts: w=${rows.count { it.fields[0] == "w" }} a=${rows.count { it.fields[0] == "a" }} r=${rows.count { it.fields[0] == "r" }} gps=${rows.count { it.fields[0] == "g" }}")
        assertEquals(1716, rows.count { it.fields[0] == "w" })
        assertEquals(1715, rows.count { it.fields[0] == "a" })
        assertEquals(858, rows.count { it.fields[0] == "r" })
        assertEquals(92, rows.count { it.fields[0] == "g" })
        listOf("w", "a", "r").forEach { type ->
            val times = rows.filter { it.fields[0] == type }.map { it.event }.sorted()
            assertEquals(times.size, times.distinct().size)
            assertTrue(times.last() - times.first() > 87.8)
            if (type != "r") assertTrue(times.zipWithNext().all { (a, b) -> b - a < 0.065 })
        }
        val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
        val up = ImuVector(-0.9275271611854533, 0.0011311997772454063, 0.3737540443263699)
        val result = replay(rows, up, seed, meta[1].toDouble())
        assertEquals(0, result.gaps)
    }

    private class QuietBin {
        var gyroCount = 0
        var accelCount = 0
        var sum = ImuVector.ZERO
        var gyroSquares = 0.0
        var accelSum = ImuVector.ZERO
        var accelSquares = 0.0
        var firstGyro = Long.MAX_VALUE
        var lastGyro = Long.MIN_VALUE
        var firstAccel = Long.MAX_VALUE
        var lastAccel = Long.MIN_VALUE
        var maxGpsSpeed = 0.0
        var gpsCount = 0
        fun add(fields: List<String>) {
            if (fields[0] == "location") {
                if (fields[22] == "gps" && fields[28].toBoolean()) {
                    fields[18].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let {
                        maxGpsSpeed = maxOf(maxGpsSpeed, it)
                        gpsCount++
                    }
                }
                return
            }
            val type = fields[3]
            if (type != "4" && type != "1") return
            val t = fields[2].toLong()
            val v = ImuVector(fields[8].toDouble(), fields[9].toDouble(), fields[10].toDouble())
            if (!v.finite()) return
            if (type == "4") {
                gyroCount++; sum += v; gyroSquares += v.dot(v)
                firstGyro = minOf(firstGyro, t); lastGyro = maxOf(lastGyro, t)
            } else {
                accelCount++; accelSum += v; accelSquares += v.dot(v)
                firstAccel = minOf(firstAccel, t); lastAccel = maxOf(lastAccel, t)
            }
        }
        fun mean() = sum * (1.0 / gyroCount)
        fun exclusions(): List<String> {
            val reasons = mutableListOf<String>()
            if (gyroCount < 40 || accelCount < 80 ||
                lastGyro - firstGyro < 800_000_000L ||
                lastAccel - firstAccel < 800_000_000L) reasons.add("coverage")
            if (gpsCount == 0) reasons.add("noGPS")
            if (maxGpsSpeed > 0.4) reasons.add("movingGPS")
            if (gyroCount > 0) {
                val gyroStd = kotlin.math.sqrt((gyroSquares / gyroCount - mean().dot(mean())).coerceAtLeast(0.0))
                if (mean().length() >= 0.05) reasons.add("gyroMean")
                if (gyroStd >= 0.015) reasons.add("gyroVariation")
            }
            if (accelCount > 0) {
                val a = accelSum * (1.0 / accelCount)
                val accelStd = kotlin.math.sqrt((accelSquares / accelCount - a.dot(a)).coerceAtLeast(0.0))
                if (abs(a.length() - 9.80665) >= 0.25) reasons.add("accelMagnitude")
                if (accelStd >= 0.2) reasons.add("accelVariation")
            }
            return reasons
        }
        fun quiet() = exclusions().isEmpty()
    }

    @Test
    fun `quiet gyro candidate requires coverage GPS and independently quiet acceleration`() {
        fun bin(speed: Double = 0.0, gyroScale: Double = 0.0, accelScale: Double = 0.0): QuietBin {
            val result = QuietBin()
            fun sample(type: String, index: Int, rate: Int, value: ImuVector) {
                val fields = MutableList(32) { "" }
                fields[0] = "sensor_sample"; fields[2] = nanos(index.toDouble() / rate).toString()
                fields[3] = type
                fields[8] = value.x.toString(); fields[9] = value.y.toString(); fields[10] = value.z.toString()
                result.add(fields)
            }
            for (i in 0..59) sample("4", i, 60,
                ImuVector(0.001 + gyroScale * sin(2 * Math.PI * i / 12), -0.002, 0.0005))
            for (i in 0..119) sample("1", i, 120,
                ImuVector(accelScale * sin(2 * Math.PI * i / 12), 0.0, 9.80665))
            val fix = MutableList(32) { "" }
            fix[0] = "location"; fix[18] = speed.toString(); fix[22] = "gps"; fix[28] = "true"
            result.add(fix)
            return result
        }
        val stable = bin()
        assertTrue(stable.quiet())
        assertEquals(0.001, stable.mean().x, 1e-12)
        assertEquals(-0.002, stable.mean().y, 1e-12)
        assertEquals(0.0005, stable.mean().z, 1e-12)
        assertTrue(!bin(speed = 1.0).quiet())
        assertTrue(!bin(gyroScale = 0.1).quiet())
        assertTrue(!bin(accelScale = 1.0).quiet())
        stable.gpsCount = 0
        assertTrue(!stable.quiet())
        assertTrue(!QuietBin().quiet())
    }

    private data class NewWindow(val name: String, val startWall: Long, val seconds: Int)
    private class NewCapture(val window: NewWindow) {
        val origin = 247227441009158L + (window.startWall - 1791209637000L) * 1_000_000L
        var metadata: List<String>? = null
        val rows = mutableListOf<List<String>>()
        var gyroCount = 0
        var accelCount = 0
        var rotationCount = 0
        var gyro = ImuVector.ZERO
        var accel = ImuVector.ZERO
        fun add(fields: List<String>) {
            val event = (fields[2].toLong() - origin) / 1e9
            if (event < 0.0 || event >= window.seconds) return
            val arrival = (fields[1].toLong() - window.startWall) / 1000.0
            if (metadata == null && fields[0] == "sensor_sample" && fields[3] == "15")
                metadata = listOf("meta", event.toString()) + fields.subList(8, 12)
            if (metadata == null) return
            val times = listOf(event.toString(), arrival.toString())
            if (fields[0] == "location") {
                rows.add(listOf("g") + times + listOf(
                    "0", "0", "0", fields[18], fields[20], fields[21], fields[19],
                    fields[17], fields[28], fields[29], fields[22],
                ))
                return
            }
            fun vector() = ImuVector(fields[8].toDouble(), fields[9].toDouble(), fields[10].toDouble())
            fun sample(type: String, value: ImuVector) =
                listOf(type) + times + listOf(value.x.toString(), value.y.toString(), value.z.toString())
            when (fields[3]) {
                "4" -> {
                    gyro += vector()
                    if (++gyroCount == 3) {
                        rows.add(sample("w", gyro * (1.0 / 3.0)))
                        gyro = ImuVector.ZERO
                        gyroCount = 0
                    }
                }
                "1" -> {
                    accel += vector()
                    if (++accelCount == 6) {
                        rows.add(sample("a", accel * (1.0 / 6.0)))
                        accel = ImuVector.ZERO
                        accelCount = 0
                    }
                }
                "15" -> if (rotationCount++ % 6 == 0) {
                    rows.add(listOf("r") + times + listOf(fields[12], "0", "0") + fields.subList(8, 12))
                }
            }
        }
    }

    @Test
    fun `external newer ride compares continuous history with matched local restarts`() {
        val path = System.getenv("PEGDOWN_DIAGNOSTICS_ZIP")
        org.junit.Assume.assumeTrue("Set PEGDOWN_DIAGNOSTICS_ZIP for the original newer diagnostics ZIP", !path.isNullOrBlank())
        val file = java.io.File(path!!)
        require(file.isFile) { "Diagnostics ZIP does not exist: $file" }
        val windows = listOf(
            NewWindow("turns", 1791209851000L, 300),
            NewWindow("faster", 1791210628000L, 92),
        )
        val captures = windows.map { NewCapture(it) }
        val wholeAxisCapture = NewCapture(NewWindow("wholeRecordedRide", 1791209637000L, Int.MAX_VALUE))
        val quietBins = sortedMapOf<Long, QuietBin>()
        val lastWall = windows.maxOf { it.startWall + it.seconds * 1000L } + 3000L
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry("PegDown_sensor_diagnostics_20261005_141356.csv")
                ?: error("Missing original Oct05 CSV entry")
            zip.getInputStream(entry).bufferedReader().use { reader ->
                val header = reader.readLine().split(',')
                require(header[0] == "record_type" && header[12] == "lean_estimate_deg")
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("sensor_sample,") && !line.startsWith("location,")) continue
                    val fields = line.split(',')
                    val wall = fields[1].toLong()
                    wholeAxisCapture.add(fields)
                    if (wall <= lastWall) captures.forEach { it.add(fields) }
                    if (wall < windows.first().startWall) {
                        quietBins.getOrPut(wall / 1000L) { QuietBin() }.add(fields)
                    }
                }
            }
        }
        println("Bias exclusions: bins=${quietBins.size} reasons=${quietBins.values.flatMap { it.exclusions() }.groupingBy { it }.eachCount()}")
        val quiet = quietBins.filterValues { it.quiet() }
        val firstRun = quiet.keys.windowed(3).firstOrNull { it.last() - it.first() == 2L }
        val bias = firstRun?.let { times ->
            val bins = times.map { quiet.getValue(it) }
            bins.fold(ImuVector.ZERO) { total, bin -> total + bin.sum } *
                (1.0 / bins.sumOf { it.gyroCount })
        }
        println("Bias candidate: quietBins=${quiet.size} run=$firstRun bias=$bias degreesPerSecond=${bias?.length()?.let { Math.toDegrees(it) }}")
        // Low GPS speed and quiet sensors do not independently prove no slow rotation.
        // The first qualifying pre-window run is fixed; never optimize on bank output.
        val up = ImuVector(-0.9275271611854533, 0.0011311997772454063, 0.3737540443263699)
        characterizeForward("wholeRecordedRide", wholeAxisCapture.rows.map { Row(it) }, up)
        val controls = listOf(
            "midpoint no lag" to ReplayOptions(),
            "midpoint lag0.6" to ReplayOptions(courseLagSeconds = 0.6),
            "interval no lag" to ReplayOptions(matchGpsInterval = true),
            "interval lag0.6" to ReplayOptions(matchGpsInterval = true, courseLagSeconds = 0.6),
            "interval consistency" to ReplayOptions(matchGpsInterval = true, checkYawConsistency = true),
            "axis yaw-10" to ReplayOptions(matchGpsInterval = true, checkYawConsistency = true, mountYawDegrees = -10.0),
            "axis yaw-5" to ReplayOptions(matchGpsInterval = true, checkYawConsistency = true, mountYawDegrees = -5.0),
            "axis yaw+5" to ReplayOptions(matchGpsInterval = true, checkYawConsistency = true, mountYawDegrees = 5.0),
            "axis yaw+10" to ReplayOptions(matchGpsInterval = true, checkYawConsistency = true, mountYawDegrees = 10.0),
        ) + if (bias != null) listOf(
            "quiet bias" to ReplayOptions(matchGpsInterval = true, checkYawConsistency = true, gyroBias = bias),
        ) else emptyList()
        captures.forEach { capture ->
            val rows = capture.rows.map { Row(it) }
            val meta = capture.metadata ?: error("Missing continuous initial orientation")
            characterizeForward(capture.window.name, rows, up)
            if (capture.window.name == "turns") {
                // Independently verify the archived compact subset against the
                // original ZIP, including the nine-significant-digit mean storage.
                val (storedMeta, storedRows) = continuousNewSubset()
                assertEquals(meta.map { it.toDoubleOrNull() ?: it }, storedMeta.map { it.toDoubleOrNull() ?: it })
                val prefix = rows.filter { it.event < 88.0 }
                    .associateBy { it.fields[0] to nanos(it.event) }
                assertEquals(prefix.size, storedRows.size)
                storedRows.forEach { stored ->
                    val original = prefix.getValue(stored.fields[0] to nanos(stored.event))
                    assertEquals(original.arrival, stored.arrival, 1e-9)
                    if (stored.fields[0] in listOf("w", "a")) {
                        val a = original.vector()
                        val b = stored.vector()
                        assertEquals(a.x, b.x, 1e-7)
                        assertEquals(a.y, b.y, 1e-7)
                        assertEquals(a.z, b.z, 1e-7)
                    } else assertEquals(original.fields[3], stored.fields[3])
                }
            }
            val seed = ImuQuaternion(meta[5].toDouble(), meta[2].toDouble(), meta[3].toDouble(), meta[4].toDouble())
            println("Continuous new source ${capture.window.name}: seconds=${capture.window.seconds} w=${rows.count { it.fields[0] == "w" }} a=${rows.count { it.fields[0] == "a" }} r=${rows.count { it.fields[0] == "r" }} gps=${rows.count { it.fields[0] == "g" }}")
            val sections = if (capture.window.name == "turns") listOf(
                Triple("left", 60.0, 72.0..80.0),
                Triple("right", 199.0, 209.0..214.0),
                Triple("following left", 213.0, 223.0..232.0),
            ) else listOf(Triple("between turns", 60.0, 85.0..87.0))
            if (capture.window.name == "faster") {
                val control = replay(rows, up, seed, meta[1].toDouble(), options = ReplayOptions(matchGpsInterval = true))
                // Preselected previously investigated updates: negative turn correction
                // near 82.924 s and positive exit correction near 84.922 s.
                val selected = listOf(82.0..83.0, 84.0..85.0).map { receiptWindow ->
                    control.bankChanges.single { it.arrival in receiptWindow && it.decision != null }
                        .decision!!.timestampNanos
                }
                val variants = listOf(
                    "control" to ReplayOptions(matchGpsInterval = true),
                    "omit82" to ReplayOptions(matchGpsInterval = true, withheldReferenceTimes = setOf(selected[0])),
                    "omit84" to ReplayOptions(matchGpsInterval = true, withheldReferenceTimes = setOf(selected[1])),
                    "omitBoth" to ReplayOptions(matchGpsInterval = true, withheldReferenceTimes = selected.toSet()),
                    "consistency" to ReplayOptions(matchGpsInterval = true, checkYawConsistency = true),
                )
                val ablationResults = mutableMapOf<String, ReplayResult>()
                variants.forEach { (name, options) ->
                    val result = replay(rows, up, seed, meta[1].toDouble(), options = options)
                    ablationResults[name] = result
                    val held = result.decisions.filter { it.reason == CompensatedImuLeanExperiment.DecisionReason.DIAGNOSTIC_HOLDOUT }
                    assertEquals(options.withheldReferenceTimes, held.map { it.timestampNanos }.toSet())
                    held.forEach { assertEquals(it.gravityTrace!!.currentBankBefore, it.gravityTrace.currentBankAfter!!, 0.0) }
                    val samples = result.readings.filter { it.time in 85.0..87.0 }
                    println("Exit ablation $name: mean=${samples.map { abs(it.axisBank) }.average()} signed=${samples.map { it.axisBank }.average()} peak=${samples.maxOf { abs(it.axisBank) }}")
                    result.bankChanges.filter { it.arrival in 80.0..87.0 }
                        .groupBy { it.arrival.toInt() }.toSortedMap().forEach { (second, changes) ->
                            println("Exit balance $name second=$second: from=${changes.first().before} to=${changes.last().after} gyro=${changes.filter { it.type == "w" }.sumOf { it.after - it.before }} correction=${changes.filter { it.type == "g" }.sumOf { it.after - it.before }}")
                        }
                    result.decisions.filter { it.timestampNanos in selected }.forEach {
                        println("Exit decision $name reference=${it.timestampNanos / 1e9 - 10.0} reason=${it.reason}")
                    }
                }
                val omitted = ablationResults.getValue("omitBoth")
                val gated = ablationResults.getValue("consistency")
                assertEquals(omitted.readings.map { it.time }, gated.readings.map { it.time })
                omitted.readings.zip(gated.readings).forEach { (a, b) ->
                    assertEquals(a.axisBank, b.axisBank, 1e-9)
                }
                assertEquals(2, gated.decisions.count {
                    it.reason == CompensatedImuLeanExperiment.DecisionReason.COURSE_GYRO_MISMATCH
                })
                ablationResults.values.forEach { result ->
                    assertEquals(0, result.gaps)
                    assertEquals(
                        control.readings.filter { it.time < 82.9 },
                        result.readings.filter { it.time < 82.9 },
                    )
                }
            }
            controls.forEach { (mode, options) ->
                val warm = replay(rows, up, seed, meta[1].toDouble(), options = options)
                sections.forEach { (label, restartTime, window) ->
                    if (options.matchGpsInterval && label in listOf("following left", "between turns")) {
                        // Fixed receipt-time windows, including five seconds before
                        // the observations. Report every accepted update, not just peaks.
                        val updates = warm.bankChanges.filter {
                            it.arrival in (window.start - 5.0)..window.endInclusive
                        }
                        assertTrue(updates.isNotEmpty())
                        val gyroDelta = updates.filter { it.type == "w" }.sumOf { it.after - it.before }
                        val correctionDelta = updates.filter { it.type == "g" }.sumOf { it.after - it.before }
                        assertEquals(updates.last().after - updates.first().before, gyroDelta + correctionDelta, 1e-8)
                        println("Attribution summary ${capture.window.name} $label $mode: from=${updates.first().before} to=${updates.last().after} gyroDelta=$gyroDelta correctionDelta=$correctionDelta")
                        updates.filter { it.decision != null }.forEach { update ->
                            val decision = update.decision!!
                            val trace = decision.gravityTrace!!
                            println("Attribution update ${capture.window.name} $mode: receipt=${update.arrival} fix=${update.event} reference=${decision.timestampNanos / 1e9 - 10.0} before=${update.before} after=${update.after} jump=${update.after - update.before} predictedBank=${trace.predictedBank} inferredBank=${trace.inferredBank} pitchInnovation=${trace.inferredPitch - trace.predictedPitch} lateral=${trace.vehicleAcceleration.y} gpsYaw=${trace.courseRate} gyroYaw=${trace.meanGyroYawRate} gain=${trace.gain}")
                        }
                    }
                    val restart = rows.filter { it.fields[0] == "r" && it.event >= restartTime }.minBy { it.event }
                    val localSeed = ImuQuaternion(
                        restart.fields[9].toDouble(), restart.fields[6].toDouble(),
                        restart.fields[7].toDouble(), restart.fields[8].toDouble(),
                    )
                    val localRows = rows.filter { it.event >= restart.event && it.arrival >= restart.arrival }
                    val local = replay(localRows, up, localSeed, restart.event, options = options)
                    val warmSamples = warm.readings.filter { it.time in window }
                    val localSamples = local.readings.filter { it.time in window }
                    assertEquals(warmSamples.map { it.time }, localSamples.map { it.time })
                    assertTrue(warmSamples.size > 15)
                    assertEquals(0, warm.gaps)
                    assertEquals(0, local.gaps)
                    println("Continuous new ${capture.window.name} $label $mode: baseline=${warmSamples.map { abs(it.baseline) }.average()} warmMean=${warmSamples.map { abs(it.axisBank) }.average()} warmSigned=${warmSamples.map { it.axisBank }.average()} warmPeak=${warmSamples.maxOf { abs(it.axisBank) }} localMean=${localSamples.map { abs(it.axisBank) }.average()} localPeak=${localSamples.maxOf { abs(it.axisBank) }} warmAccepted=${warm.corrections} warmRejected=${warm.rejected}")
                }
            }
        }
        // Displayed magnitudes are not chassis-truth errors. Lags are not fitted here.
    }

    @Test
    fun `invalid speed is diagnosed without accepting correction`() {
        val experiment = CompensatedImuLeanExperiment(referenceUp, forward, mounted, nanos(0.0))
        experiment.onFix(CompensatedImuLeanExperiment.Fix(
            nanos(0.0), nanos(0.12), Double.NaN, 0.0, 1.0, 0.2, 3.0,
        ))
        assertEquals(1, experiment.rejectedCorrections)
        assertEquals(CompensatedImuLeanExperiment.DecisionReason.BAD_FIX, experiment.decisions.single().reason)
    }

    @Test
    fun `both rides characterize compensated fusion without claiming known true bank`() {
        val cases = listOf(
            Triple("Oct04 left exit", replayOld(), 2.0..10.0),
            Triple("Oct05 left exit", replayNew("left_exit", 3), 12.0..20.0),
            Triple("Oct05 right exit", replayNew("right_exit", 3), 10.0..15.0),
            Triple("Oct05 following left exit", replayNew("following_left_exit", 3), 10.0..19.0),
            Triple("Oct05 faster exit", replayNew("fast_left_exit", 4), 17.0..25.0),
        )
        cases.forEach { (label, result, window) ->
            val samples = result.readings.filter { it.time in window }
            assertTrue(samples.size > 30)
            val baseline = samples.map { abs(it.baseline) }.average()
            val candidate = samples.map { abs(it.candidate) }.average()
            val signedBaseline = samples.map { it.baseline }.average()
            val signedCandidate = samples.map { it.candidate }.average()
            val axisMagnitude = samples.map { abs(it.axisBank) }.average()
            val projectionDifference = samples.map { abs(it.candidate - it.axisBank) }.average()
            println("Mount replay $label: projected=$candidate axisBank=$axisMagnitude meanDifference=$projectionDifference")
            println(
                "$label: magnitude baseline=$baseline candidate=$candidate; " +
                    "signed baseline=$signedBaseline candidate=$signedCandidate; " +
                    "corrections=${result.corrections} rejected=${result.rejected} gaps=${result.gaps}; " +
                    "reasons=${result.decisions.groupingBy { it.reason }.eachCount()}"
            )
            if (label == "Oct05 faster exit") {
                result.decisions.filter { it.timestampNanos / 1e9 - 10.0 in 15.0..27.0 }
                    .forEach { decision ->
                        println("Fast decision: time=${decision.timestampNanos / 1e9 - 10.0} reason=${decision.reason} roll=${decision.maxRollRate} yawRange=${decision.yawRateRange} gravity=${decision.gravityMagnitude} innovation=${decision.innovationDegrees}")
                    }
            }
            assertEquals("No source-stream gaps expected in $label", 0, result.gaps)
            // There is no independent measured bank label: do not assert that
            // smaller displayed angles on these real excerpts imply higher accuracy.
        }
    }

    @Test
    fun `slow course extension transfers to earlier ride excerpts without refitting`() {
        val (oldMeta, oldRows) = continuousOldRows()
        val oldSeed = ImuQuaternion(oldMeta[5].toDouble(), oldMeta[2].toDouble(),
            oldMeta[3].toDouble(), oldMeta[4].toDouble())
        val oldUp = ImuVector(-0.9305648836431455, -0.013920558292233265, 0.36586229019543914)
        val (newMeta, newRows) = continuousNewSubset()
        val newSeed = ImuQuaternion(newMeta[5].toDouble(), newMeta[2].toDouble(),
            newMeta[3].toDouble(), newMeta[4].toDouble())
        val newUp = ImuVector(-0.9275271611854533, 0.0011311997772454063, 0.3737540443263699)
        val cases = listOf(
            Triple("Oct04 continuous", "old", 68.0..76.0),
            Triple("Oct05 continuous", "new", 72.0..80.0),
            Triple("Oct05 left", "left_exit", 12.0..20.0),
            Triple("Oct05 right", "right_exit", 10.0..15.0),
            Triple("Oct05 following left", "following_left_exit", 10.0..19.0),
            Triple("Oct05 faster", "fast_left_exit", 17.0..25.0),
        )
        for ((label, name, window) in cases) {
            fun result(enabled: Boolean): ReplayResult {
                val options = ReplayOptions(matchGpsInterval = true, allowSlowCourseCorrections = enabled)
                return when (name) {
                    "old" -> replay(oldRows, oldUp, oldSeed, oldMeta[1].toDouble(), options = options)
                    "new" -> replay(newRows, newUp, newSeed, newMeta[1].toDouble(), options = options)
                    else -> replayNew(name, if (name == "fast_left_exit") 4 else 3, options)
                }
            }
            val control = result(false)
            val candidate = result(true)
            val a = control.readings.filter { it.time in window }
            val b = candidate.readings.filter { it.time in window }
            assertTrue(a.size > 15)
            assertEquals(a.map { it.time }, b.map { it.time })
            assertEquals(0, control.gaps)
            assertEquals(0, candidate.gaps)
            val difference = a.zip(b).map { (x, y) -> abs(x.axisBank - y.axisBank) }
            println("Slow transfer $label: controlAbs=${a.map { abs(it.axisBank) }.average()} candidateAbs=${b.map { abs(it.axisBank) }.average()} meanDifference=${difference.average()} maxDifference=${difference.maxOrNull()} controlAccepted=${control.corrections} candidateAccepted=${candidate.corrections} reasons=${candidate.decisions.groupingBy { it.reason }.eachCount()}")
        }
        // Historical real rides have no independent bank labels.
    }
}
