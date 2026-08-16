package com.dpm.pegdown.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dpm.pegdown.data.TourExporter
import com.dpm.pegdown.location.LocationTracker
import com.dpm.pegdown.location.LocationUpdateListener
import com.dpm.pegdown.model.RecordingMode
import com.dpm.pegdown.model.TourLogEntry
import com.dpm.pegdown.sensor.SensorProcessor
import com.dpm.pegdown.sensor.SensorUpdateListener
import kotlin.math.abs

class MainActivity : Activity(), SensorUpdateListener, LocationUpdateListener {

    private lateinit var sensorProcessor: SensorProcessor
    private lateinit var locationTracker: LocationTracker
    private lateinit var tourExporter: TourExporter

    private lateinit var tvStatus: TextView
    private lateinit var tvMaxTour: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var gaugeView: LeanAngleGauge
    private lateinit var btnLockView: Button
    private lateinit var btnInvertAxis: Button
    private lateinit var tvAccelLeft: TextView
    private lateinit var tvAccelRight: TextView
    private lateinit var btnInfo: Button
    private lateinit var btnRecord: Button

    private var isOrientationLocked = false
    private var manualInvert = false
    private var isRecording = false
    private var currentRecordMode = RecordingMode.MANUAL
    private val recordedEntries = mutableListOf<TourLogEntry>()
    private val handler = Handler(Looper.getMainLooper())

    private fun isGerman(): Boolean {
        val locale = resources.configuration.locales[0]
        return locale.language.equals("de", ignoreCase = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Components
        sensorProcessor = SensorProcessor(this, this)
        locationTracker = LocationTracker(this, this)
        tourExporter = TourExporter(this)

        // Permissions
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1001
            )
        }

        // Restore State
        if (savedInstanceState != null) {
            isOrientationLocked = savedInstanceState.getBoolean("isOrientationLocked", false)
            manualInvert = savedInstanceState.getBoolean("manualInvert", false)
            sensorProcessor.calibrationOffset = savedInstanceState.getDouble("calibrationOffset", 0.0)
            sensorProcessor.maxTourLeft = savedInstanceState.getDouble("maxTourLeft", 0.0)
            sensorProcessor.maxTourRight = savedInstanceState.getDouble("maxTourRight", 0.0)
            sensorProcessor.maxAcceleration = savedInstanceState.getDouble("maxAcceleration", 0.0)
            sensorProcessor.maxBraking = savedInstanceState.getDouble("maxBraking", 0.0)
            sensorProcessor.tourMaxAccel = savedInstanceState.getDouble("tourMaxAccel", 0.0)
            sensorProcessor.tourMaxBrake = savedInstanceState.getDouble("tourMaxBrake", 0.0)
        }

        if (isOrientationLocked) lockCurrentOrientation()

        setupUI()
        updateTourMaxText()
    }

    private fun setupUI() {
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor("#080808".toColorInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        gaugeView = LeanAngleGauge(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                sensorProcessor.calibrate()
                tvStatus.text = if (isGerman()) String.format(
                    "Kalibriert (%.1f°)",
                    sensorProcessor.calibrationOffset
                ) else String.format("Calibrated (%.1f°)", sensorProcessor.calibrationOffset)
                tvStatus.setTextColor("#00E676".toColorInt())
            }
        }
        rootLayout.addView(gaugeView)

        val density = resources.displayMetrics.density
        val buttonWidthPx = (145 * density).toInt()
        val buttonHeightPx = (45 * density).toInt()

        fun createCornerButton(buttonText: String, normalColor: String, topMarginDp: Int): Button {
            return Button(this).apply {
                text = buttonText
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(buttonWidthPx, buttonHeightPx).apply {
                    topMargin = (topMarginDp * density).toInt()
                }
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f
                    setColor(normalColor.toColorInt())
                    setStroke(1, "#555555".toColorInt())
                }
                background = bgDrawable
                setPadding(12, 4, 12, 4)
            }
        }

        btnLockView = createCornerButton(
            if (isOrientationLocked) (if (isGerman()) "GESPERRT" else "LOCKED")
            else (if (isGerman()) "Ansicht fixieren" else "Lock View"),
            if (isOrientationLocked) "#FF1744" else "#222222",
            8
        ).apply {
            setOnClickListener {
                isOrientationLocked = !isOrientationLocked
                val bgDrawable = background as GradientDrawable
                if (isOrientationLocked) {
                    lockCurrentOrientation()
                    text = if (isGerman()) "GESPERRT" else "LOCKED"
                    bgDrawable.setColor("#FF1744".toColorInt())
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    text = if (isGerman()) "Ansicht fixieren" else "Lock View"
                    bgDrawable.setColor("#222222".toColorInt())
                }
            }
        }

        btnInvertAxis = createCornerButton(
            if (manualInvert) (if (isGerman()) "Achsen: Invertiert" else "Axis: Inverted")
            else (if (isGerman()) "Achsen: Normal" else "Axis: Normal"),
            if (manualInvert) "#FF9800" else "#222222",
            4
        ).apply {
            setOnClickListener {
                manualInvert = !manualInvert
                gaugeView.setInverted(manualInvert)
                val bgDrawable = background as GradientDrawable
                if (manualInvert) {
                    text = if (isGerman()) "Achsen: Invertiert" else "Axis: Inverted"
                    bgDrawable.setColor("#FF9800".toColorInt())
                } else {
                    text = if (isGerman()) "Achsen: Normal" else "Axis: Normal"
                    bgDrawable.setColor("#222222".toColorInt())
                }
            }
        }

        val btnResetTour = createCornerButton(
            if (isGerman()) "Tour Reset" else "Reset Tour",
            "#222222",
            8
        ).apply {
            setOnClickListener {
                sensorProcessor.resetTour()
            }
        }

        btnInfo = createCornerButton(
            if (isGerman()) "💡 Anleitung" else "💡 Instructions",
            "#222222",
            8
        ).apply {
            setOnClickListener { showInstructionsDialog() }
        }

        btnRecord = createCornerButton(
            if (isGerman()) "⏺ Aufzeichnung" else "⏺ Record",
            "#222222",
            8
        ).apply {
            setOnClickListener { handleRecordButtonClick() }
            setOnLongClickListener { toggleAutoMode(); true }
        }

        val topLeftContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        }

        tvStatus = TextView(this).apply {
            textSize = 11f
            if (sensorProcessor.calibrationOffset != 0.0) {
                text = if (isGerman()) String.format("Kalibriert (%.1f°)", sensorProcessor.calibrationOffset)
                else String.format("Calibrated (%.1f°)", sensorProcessor.calibrationOffset)
                setTextColor("#00E676".toColorInt())
            } else {
                text = if (isGerman()) "Nicht kalibriert" else "Not calibrated"
                setTextColor("#FF5252".toColorInt())
            }
            setPadding(0, 0, 0, 4)
        }

        topLeftContainer.addView(tvStatus)
        topLeftContainer.addView(btnInvertAxis)
        topLeftContainer.addView(btnRecord)
        rootLayout.addView(topLeftContainer)

        val topRightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(10, 5, 10, 5)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.END }
        }

        topRightContainer.addView(btnLockView)
        topRightContainer.addView(btnResetTour)
        topRightContainer.addView(btnInfo)
        rootLayout.addView(topRightContainer)

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        tvAccelLeft = TextView(this).apply {
            textSize = if (isLandscape) 22f else 16f
            text = "Acc\n+0.00g"
            setTextColor("#00E676".toColorInt())
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                val cY = if (isLandscape) screenHeight * 0.95f else screenHeight * 0.52f
                val rad = if (isLandscape) kotlin.math.min(screenHeight * 0.82f, screenWidth * 0.38f) 
                          else kotlin.math.min(screenWidth, screenHeight * 2.2f) * 0.42f
                val targetY = if (isLandscape) cY - (rad * 0.15f) else cY + (rad * 0.25f)
                topMargin = (targetY - 30f).toInt()
                leftMargin = 24
            }
        }
        rootLayout.addView(tvAccelLeft)

        tvAccelRight = TextView(this).apply {
            textSize = if (isLandscape) 22f else 16f
            text = "Brake\n0.00g"
            setTextColor("#FF3D00".toColorInt())
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                val cY = if (isLandscape) screenHeight * 0.95f else screenHeight * 0.52f
                val rad = if (isLandscape) kotlin.math.min(screenHeight * 0.82f, screenWidth * 0.38f) 
                          else kotlin.math.min(screenWidth, screenHeight * 2.2f) * 0.42f
                val targetY = if (isLandscape) cY - (rad * 0.15f) else cY + (rad * 0.25f)
                topMargin = (targetY - 30f).toInt()
                rightMargin = 24
            }
        }
        rootLayout.addView(tvAccelRight)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val topMarginValue = statusBarInsets.top + 10
            (topLeftContainer.layoutParams as FrameLayout.LayoutParams).topMargin = topMarginValue
            (topRightContainer.layoutParams as FrameLayout.LayoutParams).topMargin = topMarginValue
            insets
        }

        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 24
            }
        }

        tvMaxTour = TextView(this).apply {
            textSize = 14f
            setTextColor("#00B0FF".toColorInt())
            gravity = Gravity.CENTER
        }
        bottomContainer.addView(tvMaxTour)

        tvSpeed = TextView(this).apply {
            textSize = if (isLandscape) 32f else 24f
            text = "0 km/h"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 10, 20, 10)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
        }
        val speedLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (80 * density).toInt()
        }
        rootLayout.addView(tvSpeed, speedLayoutParams)

        rootLayout.addView(bottomContainer)
        setContentView(rootLayout)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateTourMaxText() {
        tvMaxTour.text = String.format(
            "Tour Max — L: %.1f° | R: %.1f° | Acc: +%.2fg | Brake: %.2fg",
            abs(sensorProcessor.maxTourLeft),
            abs(sensorProcessor.maxTourRight),
            sensorProcessor.tourMaxAccel,
            abs(sensorProcessor.tourMaxBrake)
        )
    }

    override fun onLeanAngleUpdate(current: Double, tempL: Double, tempR: Double, tourL: Double, tourR: Double) {
        gaugeView.updateData(current, tempL, tempR, tourL, tourR)
        updateTourMaxText()
    }

    override fun onAccelerationUpdate(accel: Double, brake: Double, tourMaxAccel: Double, tourMaxBrake: Double) {
        tvAccelLeft.text = String.format("Acc\n+%.2fg", accel)
        tvAccelRight.text = String.format("Brake\n%.2fg", abs(brake))
        updateTourMaxText()
    }

    override fun onPeakRecorded(entry: TourLogEntry) {
        recordedEntries.add(entry)
    }

    override fun onLocationUpdate(location: Location, speedKmH: Double) {
        sensorProcessor.currentLatitude = location.latitude
        sensorProcessor.currentLongitude = location.longitude
        sensorProcessor.currentSpeedKmH = speedKmH
        
        handler.post {
            tvSpeed.text = String.format("%.0f km/h", speedKmH)
        }
        checkAutoStartCondition(speedKmH)
    }

    private fun handleRecordButtonClick() {
        val bgDrawable = btnRecord.background as GradientDrawable
        when (currentRecordMode) {
            RecordingMode.MANUAL -> {
                if (!isRecording) {
                    startRecording()
                    btnRecord.text = if (isGerman()) "🔴 Aufz. läuft" else "🔴 Recording"
                    bgDrawable.setColor("#B71C1C".toColorInt())
                    tvStatus.text = if (isGerman()) "Aufzeichnung aktiv" else "Recording active"
                    tvStatus.setTextColor("#00E676".toColorInt())
                } else {
                    stopRecording()
                    btnRecord.text = if (isGerman()) "⏺ Aufzeichnung" else "⏺ Record"
                    bgDrawable.setColor("#222222".toColorInt())
                }
            }
            RecordingMode.AUTO_IDLE -> {
                startRecording()
                currentRecordMode = RecordingMode.AUTO_RECORDING
                btnRecord.text = if (isGerman()) "🔴 Auto: Läuft" else "🔴 Auto: Running"
                bgDrawable.setColor("#B71C1C".toColorInt())
            }
            RecordingMode.AUTO_RECORDING -> {
                stopRecording()
                currentRecordMode = RecordingMode.AUTO_IDLE
                btnRecord.text = if (isGerman()) "🤖 Auto-Modus (Stand)" else "🤖 Auto-Mode (Idle)"
                bgDrawable.setColor("#0D47A1".toColorInt())
            }
        }
    }

    private fun startRecording() {
        isRecording = true
        sensorProcessor.isRecording = true
        recordedEntries.clear()
    }

    private fun stopRecording() {
        isRecording = false
        sensorProcessor.isRecording = false
        showSaveDialog()
    }

    private fun toggleAutoMode() {
        val bgDrawable = btnRecord.background as GradientDrawable
        if (currentRecordMode == RecordingMode.MANUAL || currentRecordMode == RecordingMode.AUTO_RECORDING) {
            currentRecordMode = RecordingMode.AUTO_IDLE
            isRecording = false
            sensorProcessor.isRecording = false
            btnRecord.text = if (isGerman()) "🤖 Auto-Modus (Stand)" else "🤖 Auto-Mode (Idle)"
            bgDrawable.setColor("#0D47A1".toColorInt())
        } else {
            currentRecordMode = RecordingMode.MANUAL
            isRecording = false
            sensorProcessor.isRecording = false
            btnRecord.text = if (isGerman()) "⏺ Aufzeichnung" else "⏺ Record"
            bgDrawable.setColor("#222222".toColorInt())
        }
    }

    private fun checkAutoStartCondition(speedKmH: Double) {
        if (currentRecordMode == RecordingMode.AUTO_IDLE && !isRecording && speedKmH > 7.0) {
            startRecording()
            currentRecordMode = RecordingMode.AUTO_RECORDING
            handler.post {
                btnRecord.text = if (isGerman()) "🔴 Auto: Läuft" else "🔴 Auto: Running"
                (btnRecord.background as GradientDrawable).setColor("#B71C1C".toColorInt())
                tvStatus.text = if (isGerman()) "Auto-Aufzeichnung läuft (>7km/h)" else "Auto-recording..."
                tvStatus.setTextColor("#00E676".toColorInt())
            }
        }
    }

    private fun showSaveDialog() {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.getDefault())
        val defaultFileName = dateFormat.format(java.util.Date())
        val input = android.widget.EditText(this).apply {
            setText(defaultFileName)
            setTextColor(Color.WHITE)
            setBackgroundColor("#222222".toColorInt())
            setPadding(40, 30, 40, 30)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isGerman()) "Tour speichern" else "Save Tour")
            .setView(container)
            .setPositiveButton(if (isGerman()) "Speichern" else "Save") { _, _ ->
                val fileName = input.text.toString().trim().ifEmpty { defaultFileName }
                tourExporter.saveTourToGpx(fileName, recordedEntries)
            }
            .setNegativeButton(if (isGerman()) "Abbrechen" else "Cancel", null)
            .show()
    }

    private fun showInstructionsDialog() {
        val title = if (isGerman()) "Gebrauchsanweisung" else "Instructions"
        val message = if (isGerman()) {
            """
            1. Hauptansicht (Gauge)
            Die Schräglagenanzeige zeigt den aktuellen Winkel in Echtzeit.
            - Grün (bis 20°): Entspannte Schräglage.
            - Gelb (20°-35°): Sportliche Schräglage.
            - Rot (über 35°): Ambitionierte Schräglage.
            - Gelbe Marker (oben): Spitzenwert der aktuellen Kurve (Reset nach 7s).
            - Blaue Marker (unten): Absoluter Höchstwert der gesamten Tour.

            2. Kalibrierung
            Handy sicher in der Halterung fixieren und einmal auf die große Gauge tippen, um den aktuellen Winkel als Nullpunkt (0°) zu setzen. Status oben links prüfen.

            3. Funktionstasten
            - Lock View: Fixiert die Orientierung (Hoch/Quer), damit das Display bei Schräglage nicht springt.
            - Achsen: Kehrt die Wirkungsrichtung der Schräglage um, falls das Handy "falsch herum" montiert ist.
            - Tour Reset: Setzt alle Maximalwerte (Winkel, G-Kräfte) der aktuellen Tour zurück.

            4. Aufzeichnung
            - Manueller Modus: Tippe auf ⏺ Aufzeichnung.
            - Auto-Modus: Halte ⏺ Aufzeichnung lange gedrückt. Die Aufnahme startet automatisch ab 7 km/h.

            5. Daten & Export
            Beendete Touren werden als GPX-Datei im Download-Ordner gespeichert. Diese Datei ist optimiert für Calimoto und andere Karten-Viewer.

            Sicherheit zuerst: Bedienen Sie die App niemals während der Fahrt!
            """.trimIndent()
        } else {
            """
            1. Main View (Gauge)
            Shows your current lean angle in real-time.
            - Green (up to 20°): Relaxed lean.
            - Yellow (20°-35°): Sporty lean.
            - Red (over 35°): Ambitious lean.
            - Yellow markers (top): Peak of the current curve (resets after 7s).
            - Blue markers (bottom): Absolute peak of the entire tour.

            2. Calibration
            Fix the phone securely in its mount and tap the large gauge once to set the current angle as zero (0°). Check the status in the top left.

            3. Controls
            - Lock View: Prevents the screen from rotating during lean, keeping your preferred orientation (Portrait/Landscape).
            - Axis: Reverses the lean direction if the phone is mounted "upside down".
            - Tour Reset: Resets all maximum values (Angle, G-forces) for the current tour.

            4. Recording
            - Manual Mode: Tap ⏺ Record to start/stop.
            - Auto Mode: Long press ⏺ Record. Recording starts automatically when speed exceeds 7 km/h.

            5. Data & Export
            Finished tours are saved as GPX files in your Downloads folder. The format is optimized for Calimoto and other map viewers.

            Safety first: Never operate the app while riding!
            """.trimIndent()
        }
        val textView = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(50, 30, 50, 30)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(android.widget.ScrollView(this).apply { addView(textView) })
            .setPositiveButton("OK", null)
            .show()
            .apply { window?.setBackgroundDrawableResource(android.R.color.background_dark) }
    }

    private fun lockCurrentOrientation() {
        val currentOrientation = resources.configuration.orientation
        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (rotation == android.view.Surface.ROTATION_270) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("isOrientationLocked", isOrientationLocked)
        outState.putBoolean("manualInvert", manualInvert)
        outState.putDouble("calibrationOffset", sensorProcessor.calibrationOffset)
        outState.putDouble("maxTourLeft", sensorProcessor.maxTourLeft)
        outState.putDouble("maxTourRight", sensorProcessor.maxTourRight)
        outState.putDouble("maxAcceleration", sensorProcessor.maxAcceleration)
        outState.putDouble("maxBraking", sensorProcessor.maxBraking)
        outState.putDouble("tourMaxAccel", sensorProcessor.tourMaxAccel)
        outState.putDouble("tourMaxBrake", sensorProcessor.tourMaxBrake)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        sensorProcessor.start()
        locationTracker.start()
    }

    override fun onPause() {
        super.onPause()
        sensorProcessor.stop()
        locationTracker.stop()
    }
}
