package com.dpm.pegdown

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
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
import kotlin.math.abs
import kotlin.math.atan2

class MainActivity : Activity(), SensorEventListener {

    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var lastPeakLeanAngle = 0.0
    private var tempResetRunnable: Runnable? = null
    private lateinit var sensorManager: SensorManager
    private var linearAccelSensor: Sensor? = null
    private var gravitySensor: Sensor? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvMaxTour: TextView
    private lateinit var gaugeView: LeanAngleGauge
    private lateinit var btnLockView: Button
    private lateinit var btnInvertAxis: Button

    private var calibrationOffset = 0.0
    private var rawTilt = 0.0
    private var smoothedTilt = 0.0
    private val alpha = 0.07

    private var isOrientationLocked = false
    private var manualInvert = false
    private var sensorStartupCounter = 0

    private var maxTourLeft = 0.0
    private var maxTourRight = 0.0
    private var maxTempLeft = 0.0
    private var maxTempRight = 0.0

    private val handler = Handler(Looper.getMainLooper())
    private var resetRunnable: Runnable? = null
    private var accelResetRunnable: Runnable? = null

    // Für Beschleunigung / Bremsen
    private var maxAcceleration = 0.0
    private var maxBraking = 0.0
    private var tourMaxAccel = 0.0
    private var tourMaxBrake = 0.0
    private lateinit var tvAccelLeft: TextView
    private lateinit var tvAccelRight: TextView

    // GPS & Aufzeichnung ---
    private var isRecording = false
    private var hasUserStoppedRecording = false
    private lateinit var locationManager: LocationManager
    private var currentSpeedKmH = 0.0

    // Aufzeichnungs- und Automatik-Modus ---
    private enum class RecordingMode {
        MANUAL,        // Komplett manuell
        AUTO_IDLE,     // Automatik-Modus aktiv, wartet auf > 7 km/h
        AUTO_RECORDING // Automatik-Modus hat automatisch gestartet
    }
    private var currentRecordMode = RecordingMode.MANUAL
    private lateinit var btnRecord: Button

    private val locationListener = android.location.LocationListener { location ->
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        currentSpeedKmH = if (location.hasSpeed()) location.speed * 3.6 else 0.0

        // Automatischer Start im Auto-Modus prüfen
        checkAutoStartCondition()
    }

    // Datenklasse für gespeicherte Events (Peaks / Tour-Max)
    data class TourLogEntry(
        val timestamp: String,
        val leanAngleLeft: Double,
        val leanAngleRight: Double,
        val acceleration: Double,
        val braking: Double,
        val lat: Double,
        val lon: Double
    )
    private val recordedEntries = mutableListOf<TourLogEntry>()

    private fun isGerman(): Boolean {
        val locale = resources.configuration.locales[0]
        return locale.language.equals("de", ignoreCase = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                1001
            )
        }

        if (savedInstanceState != null) {
            isOrientationLocked = savedInstanceState.getBoolean("isOrientationLocked", false)
            manualInvert = savedInstanceState.getBoolean("manualInvert", false)
            calibrationOffset = savedInstanceState.getDouble("calibrationOffset", 0.0)
            maxTourLeft = savedInstanceState.getDouble("maxTourLeft", 0.0)
            maxTourRight = savedInstanceState.getDouble("maxTourRight", 0.0)
            maxTempLeft = savedInstanceState.getDouble("maxTempLeft", 0.0)
            maxTempRight = savedInstanceState.getDouble("maxTempRight", 0.0)
            maxAcceleration = savedInstanceState.getDouble("maxAcceleration", 0.0)
            maxBraking = savedInstanceState.getDouble("maxBraking", 0.0)
            tourMaxAccel = savedInstanceState.getDouble("tourMaxAccel", 0.0)
            tourMaxBrake = savedInstanceState.getDouble("tourMaxBrake", 0.0)
        }

        if (isOrientationLocked) {
            lockCurrentOrientation()
        }

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
                smoothedTilt = rawTilt
                sensorStartupCounter = 0
                calibrationOffset = smoothedTilt
                tvStatus.text = if (isGerman()) String.format("Kalibriert (%.1f°)", calibrationOffset) else String.format("Calibrated (%.1f°)", calibrationOffset)
                tvStatus.setTextColor("#00E676".toColorInt())
            }
        }
        rootLayout.addView(gaugeView)

        val density = resources.displayMetrics.density
        val buttonWidthPx = (170 * density).toInt()
        val buttonHeightPx = (50 * density).toInt()

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
            if (isOrientationLocked) {
                if (isGerman()) "GESPERRT" else "LOCKED"
            } else {
                if (isGerman()) "Ansicht fixieren" else "Lock View"
            },
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
            if (manualInvert) {
                if (isGerman()) "Achsen: Invertiert" else "Axis: Inverted"
            } else {
                if (isGerman()) "Achsen: Normal" else "Axis: Normal"
            },
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
                maxTourLeft = 0.0
                maxTourRight = 0.0
                maxTempLeft = 0.0
                maxTempRight = 0.0
                maxAcceleration = 0.0
                maxBraking = 0.0
                tourMaxAccel = 0.0
                tourMaxBrake = 0.0

                accelResetRunnable?.let { handler.removeCallbacks(it) }
                tvAccelLeft.text = String.format("Acc\n+0.00g")
                tvAccelRight.text = String.format("Brake\n0.00g")

                gaugeView.updateData(0.0, 0.0, 0.0, 0.0, 0.0)
                tvMaxTour.text = String.format("Tour Max — L: 0.0° | R: 0.0° | Acc: +0.00g | Brake: 0.00g")
            }
        }

        btnRecord = createCornerButton(
            if (isGerman()) "⏺ Aufzeichnung" else "⏺ Record",
            "#222222",
            8
        ).apply {
            setOnClickListener {
                handleRecordButtonClick()
            }
            setOnLongClickListener {
                toggleAutoMode()
                true
            }
        }

        val topLeftContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        }

        tvStatus = TextView(this).apply {
            textSize = 11f
            if (calibrationOffset != 0.0) {
                text = if (isGerman()) String.format("Kalibriert (%.1f°)", calibrationOffset) else String.format("Calibrated (%.1f°)", calibrationOffset)
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
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }

        topRightContainer.addView(btnLockView)
        topRightContainer.addView(btnResetTour)
        rootLayout.addView(topRightContainer)

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        tvAccelLeft = TextView(this).apply {
            textSize = if (isLandscape) 22f else 16f
            text = String.format("Acc\n+%.2fg", maxAcceleration)
            setTextColor("#00E676".toColorInt())
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
            background = bgDrawable

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                val cY = if (isLandscape) screenHeight * 0.95f else screenHeight * 0.52f
                val rad = if (isLandscape) kotlin.math.min(screenHeight * 0.82f, screenWidth * 0.38f) else kotlin.math.min(screenWidth, screenHeight * 2.2f) * 0.42f
                val targetY = if (isLandscape) cY - (rad * 0.15f) else cY + (rad * 0.25f)

                topMargin = (targetY - 30f).toInt()
                leftMargin = 24
            }
        }
        rootLayout.addView(tvAccelLeft)

        tvAccelRight = TextView(this).apply {
            textSize = if (isLandscape) 22f else 16f
            text = String.format("Brake\n%.2fg", abs(maxBraking))
            setTextColor("#FF3D00".toColorInt())
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
            background = bgDrawable

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                val cY = if (isLandscape) screenHeight * 0.95f else screenHeight * 0.52f
                val rad = if (isLandscape) kotlin.math.min(screenHeight * 0.82f, screenWidth * 0.38f) else kotlin.math.min(screenWidth, screenHeight * 2.2f) * 0.42f
                val targetY = if (isLandscape) cY - (rad * 0.15f) else cY + (rad * 0.25f)

                topMargin = (targetY - 30f).toInt()
                rightMargin = 24
            }
        }
        rootLayout.addView(tvAccelRight)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val topMarginValue = statusBarInsets.top + 10

            val leftParams = topLeftContainer.layoutParams as FrameLayout.LayoutParams
            leftParams.topMargin = topMarginValue
            topLeftContainer.layoutParams = leftParams

            val rightParams = topRightContainer.layoutParams as FrameLayout.LayoutParams
            rightParams.topMargin = topMarginValue
            topRightContainer.layoutParams = rightParams

            insets
        }

        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
            background = bgDrawable

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
            text = String.format("Tour Max — L: %.1f° | R: %.1f° | Acc: +%.2fg | Brake: %.2fg", abs(maxTourLeft), abs(maxTourRight), tourMaxAccel, abs(tourMaxBrake))
            setTextColor("#00B0FF".toColorInt())
            gravity = Gravity.CENTER
        }

        bottomContainer.addView(tvMaxTour)
        rootLayout.addView(bottomContainer)

        setContentView(rootLayout)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 2000L
        ).setMinUpdateIntervalMillis(1000L).build()

        val locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                for (location in locationResult.locations) {
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude
                }
            }
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, android.os.Looper.getMainLooper())
        }
    }

    private fun handleRecordButtonClick() {
        val bgDrawable = btnRecord.background as GradientDrawable

        when (currentRecordMode) {
            RecordingMode.MANUAL -> {
                if (!isRecording) {
                    isRecording = true
                    hasUserStoppedRecording = false
                    recordedEntries.clear()

                    btnRecord.text = if (isGerman()) "🔴 Aufz. läuft" else "🔴 Recording"
                    bgDrawable.setColor("#B71C1C".toColorInt())

                    tvStatus.text = if (isGerman()) "Aufzeichnung aktiv" else "Recording active"
                    tvStatus.setTextColor("#00E676".toColorInt())
                } else {
                    isRecording = false
                    hasUserStoppedRecording = true
                    showSaveDialog()

                    btnRecord.text = if (isGerman()) "⏺ Aufzeichnung" else "⏺ Record"
                    bgDrawable.setColor("#222222".toColorInt())
                }
            }
            RecordingMode.AUTO_IDLE -> {
                isRecording = true
                recordedEntries.clear()
                currentRecordMode = RecordingMode.AUTO_RECORDING

                btnRecord.text = if (isGerman()) "🔴 Auto: Läuft" else "🔴 Auto: Running"
                bgDrawable.setColor("#B71C1C".toColorInt())
            }
            RecordingMode.AUTO_RECORDING -> {
                isRecording = false
                hasUserStoppedRecording = true
                showSaveDialog()

                currentRecordMode = RecordingMode.AUTO_IDLE
                btnRecord.text = if (isGerman()) "🤖 Auto-Modus (Stand)" else "🤖 Auto-Mode (Idle)"
                bgDrawable.setColor("#0D47A1".toColorInt())
            }
        }
    }

    private fun toggleAutoMode() {
        val bgDrawable = btnRecord.background as GradientDrawable

        if (currentRecordMode == RecordingMode.MANUAL || currentRecordMode == RecordingMode.AUTO_RECORDING) {
            currentRecordMode = RecordingMode.AUTO_IDLE
            isRecording = false
            hasUserStoppedRecording = false

            btnRecord.text = if (isGerman()) "🤖 Auto-Modus (Stand)" else "🤖 Auto-Mode (Idle)"
            bgDrawable.setColor("#0D47A1".toColorInt())

            android.widget.Toast.makeText(this, if (isGerman()) "Automatik-Modus aktiviert (> 7 km/h)" else "Auto mode activated", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            currentRecordMode = RecordingMode.MANUAL
            isRecording = false

            btnRecord.text = if (isGerman()) "⏺ Aufzeichnung" else "⏺ Record"
            bgDrawable.setColor("#222222".toColorInt())

            android.widget.Toast.makeText(this, if (isGerman()) "Manueller Modus" else "Manual mode", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAutoStartCondition() {
        if (currentRecordMode == RecordingMode.AUTO_IDLE && !isRecording && currentSpeedKmH > 7.0) {
            isRecording = true
            recordedEntries.clear()
            currentRecordMode = RecordingMode.AUTO_RECORDING

            handler.post {
                btnRecord.text = if (isGerman()) "🔴 Auto: Läuft" else "🔴 Auto: Running"
                val bgDrawable = btnRecord.background as GradientDrawable
                bgDrawable.setColor("#B71C1C".toColorInt())

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
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setBackgroundColor("#222222".toColorInt())

            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(50, 20, 50, 20)
            }
            setPadding(40, 30, 40, 30)
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(if (isGerman()) "Tour speichern" else "Save Tour")
            .setMessage(if (isGerman()) "Dateinamen eingeben:" else "Enter file name:")
            .setView(container)
            .setPositiveButton(if (isGerman()) "Speichern" else "Save") { _, _ ->
                val fileName = input.text.toString().trim()
                saveTourToCsv(fileName.ifEmpty { defaultFileName })
                isRecording = false
                hasUserStoppedRecording = true
            }
            .setNegativeButton(if (isGerman()) "Abbrechen" else "Cancel", null)
            .show()
    }

    private fun saveTourToCsv(fileName: String) {
        try {
            val csvHeader = "Timestamp;LeanAngleLeft;LeanAngleRight;Acceleration;Braking;Latitude;Longitude\n"
            val csvContent = StringBuilder(csvHeader)

            for (entry in recordedEntries) {
                csvContent.append("${entry.timestamp};${entry.leanAngleLeft};${entry.leanAngleRight};${entry.acceleration};${entry.braking};${entry.lat};${entry.lon}\n")
            }

            val finalFileName = if (fileName.endsWith(".csv")) fileName else "$fileName.csv"
            val fileContentBytes = csvContent.toString().toByteArray(Charsets.UTF_8)

            var fileUri: android.net.Uri? = null

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(fileContentBytes)
                    }
                    fileUri = uri
                }
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = java.io.File(downloadsDir, finalFileName)
                file.writeText(csvContent.toString())
                fileUri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }

            android.widget.Toast.makeText(this, if (isGerman()) "Im Download-Ordner gespeichert!" else "Saved to Downloads!", android.widget.Toast.LENGTH_LONG).show()

            if (fileUri != null) {
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(shareIntent, if (isGerman()) "Tour teilen via" else "Share tour via"))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, if (isGerman()) "Fehler beim Speichern!" else "Error saving file!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun lockCurrentOrientation() {
        val currentOrientation = resources.configuration.orientation
        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (rotation == android.view.Surface.ROTATION_270) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        } else {
            if (rotation == android.os.Build.VERSION.SDK_INT && rotation == android.view.Surface.ROTATION_180) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("isOrientationLocked", isOrientationLocked)
        outState.putBoolean("manualInvert", manualInvert)
        outState.putDouble("calibrationOffset", calibrationOffset)
        outState.putDouble("maxTourLeft", maxTourLeft)
        outState.putDouble("maxTourRight", maxTourRight)
        outState.putDouble("maxTempLeft", maxTempLeft)
        outState.putDouble("maxTempRight", maxTempRight)
        outState.putDouble("maxAcceleration", maxAcceleration)
        outState.putDouble("maxBraking", maxBraking)
        outState.putDouble("tourMaxAccel", tourMaxAccel)
        outState.putDouble("tourMaxBrake", tourMaxBrake)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        sensorStartupCounter = 0
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, locationListener)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(locationListener)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Wird hier nicht benötigt, muss aber implementiert werden
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.rotation ?: android.view.Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            val newRawTilt = if (isLandscape) {
                val invertSign = if (rotation == android.view.Surface.ROTATION_270) 1.0 else -1.0
                Math.toDegrees(atan2((-y * invertSign), kotlin.math.sqrt((x * x + z * z).toDouble())))
            } else {
                Math.toDegrees(atan2(-x.toDouble(), kotlin.math.sqrt((y * y + z * z).toDouble())))
            }

            if (abs(newRawTilt - rawTilt) > 5.0 || sensorStartupCounter < 15) {
                smoothedTilt = newRawTilt
                sensorStartupCounter++
            } else {
                smoothedTilt = smoothedTilt + alpha * (newRawTilt - smoothedTilt)
            }
            rawTilt = newRawTilt

            gaugeView.setInverted(manualInvert)

            val calculatedAngle = smoothedTilt - calibrationOffset
            val finalAngle = kotlin.math.round(calculatedAngle / 0.1) * 0.1

            if (finalAngle < maxTourLeft) maxTourLeft = finalAngle
            if (finalAngle > maxTourRight) maxTourRight = finalAngle

            var newTempPeak = false
            if (finalAngle < maxTempLeft) {
                maxTempLeft = finalAngle
                newTempPeak = true
            }
            if (finalAngle > maxTempRight) {
                maxTempRight = finalAngle
                newTempPeak = true
            }

            if (newTempPeak) {
                lastPeakLeanAngle = if (abs(maxTempLeft) > abs(maxTempRight)) maxTempLeft else maxTempRight

                tempResetRunnable?.let { handler.removeCallbacks(it) }
                tempResetRunnable = Runnable {
                    if (isRecording && (abs(lastPeakLeanAngle) > 0.5 || maxAcceleration > 0.05 || abs(maxBraking) > 0.05)) {
                        val leftVal = if (lastPeakLeanAngle < 0) abs(lastPeakLeanAngle) else 0.0
                        val rightVal = if (lastPeakLeanAngle > 0) abs(lastPeakLeanAngle) else 0.0

                        recordedEntries.add(
                            TourLogEntry(
                                timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                                leanAngleLeft = leftVal,
                                leanAngleRight = rightVal,
                                acceleration = maxAcceleration,
                                braking = maxBraking,
                                lat = currentLatitude,
                                lon = currentLongitude
                            )
                        )
                    }

                    maxTempLeft = 0.0
                    maxTempRight = 0.0
                    lastPeakLeanAngle = 0.0
                    maxAcceleration = 0.0
                    maxBraking = 0.0
                    gaugeView.updateData(finalAngle, maxTempLeft, maxTempRight, maxTourLeft, maxTourRight)
                }
                handler.postDelayed(tempResetRunnable!!, 7000)
            }

            gaugeView.updateData(finalAngle, maxTempLeft, maxTempRight, maxTourLeft, maxTourRight)
            tvMaxTour.text = String.format("Tour Max — L: %.1f° | R: %.1f° | Acc: +%.2fg | Brake: %.2fg", abs(maxTourLeft), abs(maxTourRight), tourMaxAccel, abs(tourMaxBrake))

        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val rawAccel = if (isLandscape) y else x

            var forwardAcceleration = (rawAccel / 9.81)

            if (abs(forwardAcceleration) < 0.04) {
                forwardAcceleration = 0.0
            }

            var newAccelPeak = false
            if (forwardAcceleration > maxAcceleration) {
                maxAcceleration = forwardAcceleration
                newAccelPeak = true
            }
            if (forwardAcceleration < maxBraking) {
                maxBraking = forwardAcceleration
                newAccelPeak = true
            }

            if (maxAcceleration > tourMaxAccel) {
                tourMaxAccel = maxAcceleration
            }
            if (maxBraking < tourMaxBrake) {
                tourMaxBrake = maxBraking
            }

            if (newAccelPeak) {
                val peakAccelToSave = maxAcceleration
                val peakBrakeToSave = maxBraking

                accelResetRunnable?.let { handler.removeCallbacks(it) }
                accelResetRunnable = Runnable {
                    if (isRecording && (abs(lastPeakLeanAngle) > 0.5 || peakAccelToSave > 0.05 || abs(peakBrakeToSave) > 0.05)) {
                        val leftVal = if (lastPeakLeanAngle < 0) abs(lastPeakLeanAngle) else 0.0
                        val rightVal = if (lastPeakLeanAngle > 0) abs(lastPeakLeanAngle) else 0.0

                        recordedEntries.add(
                            TourLogEntry(
                                timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                                leanAngleLeft = leftVal,
                                leanAngleRight = rightVal,
                                acceleration = peakAccelToSave,
                                braking = peakBrakeToSave,
                                lat = currentLatitude,
                                lon = currentLongitude
                            )
                        )
                    }

                    maxAcceleration = 0.0
                    maxBraking = 0.0
                    tvAccelLeft.text = String.format("Acc\n+0.00g")
                    tvAccelRight.text = String.format("Brake\n0.00g")
                }
                handler.postDelayed(accelResetRunnable!!, 7000)
            }

            tvAccelLeft.text = String.format("Acc\n+%.2fg", maxAcceleration)
            tvAccelRight.text = String.format("Brake\n%.2fg", abs(maxBraking))
            tvMaxTour.text = String.format("Tour Max — L: %.1f° | R: %.1f° | Acc: +%.2fg | Brake: %.2fg", abs(maxTourLeft), abs(maxTourRight), tourMaxAccel, abs(tourMaxBrake))
        }
    }
}