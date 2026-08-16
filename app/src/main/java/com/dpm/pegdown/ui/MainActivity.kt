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
import com.dpm.pegdown.R
import com.dpm.pegdown.data.SettingsManager
import com.dpm.pegdown.data.TourExporter
import com.dpm.pegdown.location.LocationTracker
import com.dpm.pegdown.location.LocationUpdateListener
import com.dpm.pegdown.model.RecordingMode
import com.dpm.pegdown.model.TourLogEntry
import com.dpm.pegdown.sensor.SensorProcessor
import com.dpm.pegdown.sensor.SensorUpdateListener
import com.dpm.pegdown.util.LocaleHelper
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), SensorUpdateListener, LocationUpdateListener {

    private lateinit var sensorProcessor: SensorProcessor
    private lateinit var locationTracker: LocationTracker
    private lateinit var tourExporter: TourExporter
    private lateinit var settingsManager: SettingsManager

    override fun attachBaseContext(newBase: android.content.Context) {
        val manager = SettingsManager(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, manager.selectedLanguage))
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvMaxTour: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var gaugeView: LeanAngleGauge
    private lateinit var btnLockView: Button
    private lateinit var tvAccelLeft: TextView
    private lateinit var tvAccelRight: TextView
    private lateinit var btnInfo: Button
    private lateinit var btnRecord: Button
    private lateinit var btnSettings: Button

    private var isOrientationLocked = false
    private var manualInvert = false
    private var isRecording = false
    private var currentRecordMode = RecordingMode.MANUAL
    private val recordedEntries = mutableListOf<TourLogEntry>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Components
        settingsManager = SettingsManager(this)
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
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
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
        applySettings()
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
                tvStatus.text = getString(R.string.calibrated_format, sensorProcessor.calibrationOffset)
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
            if (isOrientationLocked) getString(R.string.btn_locked)
            else getString(R.string.btn_lock_view),
            if (isOrientationLocked) "#FF1744" else "#222222",
            8
        ).apply {
            setOnClickListener {
                isOrientationLocked = !isOrientationLocked
                val bgDrawable = background as GradientDrawable
                if (isOrientationLocked) {
                    lockCurrentOrientation()
                    text = getString(R.string.btn_locked)
                    bgDrawable.setColor("#FF1744".toColorInt())
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    text = getString(R.string.btn_lock_view)
                    bgDrawable.setColor("#222222".toColorInt())
                }
                
                if (settingsManager.isOrientationSaveEnabled) {
                    settingsManager.lockedOrientation = requestedOrientation
                }
            }
        }

        val btnResetTour = createCornerButton(
            getString(R.string.btn_reset_tour),
            "#222222",
            8
        ).apply {
            setOnClickListener {
                sensorProcessor.resetTour()
            }
        }

        btnInfo = createCornerButton(
            getString(R.string.btn_instructions),
            "#222222",
            8
        ).apply {
            setOnClickListener { showInstructionsDialog() }
        }

        btnSettings = createCornerButton(
            getString(R.string.btn_settings),
            "#222222",
            8
        ).apply {
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        btnRecord = createCornerButton(
            getString(R.string.btn_record),
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
                text = getString(R.string.calibrated_format, sensorProcessor.calibrationOffset)
                setTextColor("#00E676".toColorInt())
            } else {
                text = getString(R.string.not_calibrated)
                setTextColor("#FF5252".toColorInt())
            }
            setPadding(0, 0, 0, 4)
        }

        topLeftContainer.addView(tvStatus)
        topLeftContainer.addView(btnSettings)
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
            text = getString(R.string.acc_format, 0.0)
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
            text = getString(R.string.brake_format, 0.0)
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
            text = getString(R.string.speed_format, 0.0)
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
        tvMaxTour.text = getString(
            R.string.tour_max_format,
            abs(sensorProcessor.maxTourLeft),
            abs(sensorProcessor.maxTourRight),
            sensorProcessor.tourMaxAccel,
            abs(sensorProcessor.tourMaxBrake),
        )
    }

    override fun onLeanAngleUpdate(current: Double, tempL: Double, tempR: Double, tourL: Double, tourR: Double) {
        gaugeView.updateData(current, tempL, tempR, tourL, tourR)
        updateTourMaxText()
    }

    override fun onAccelerationUpdate(accel: Double, brake: Double, tourMaxAccel: Double, tourMaxBrake: Double) {
        tvAccelLeft.text = getString(R.string.acc_format, accel)
        tvAccelRight.text = getString(R.string.brake_format, abs(brake))
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
            tvSpeed.text = getString(R.string.speed_format, speedKmH)
        }
        checkAutoStartCondition(speedKmH)
    }

    private fun handleRecordButtonClick() {
        val bgDrawable = btnRecord.background as GradientDrawable
        when (currentRecordMode) {
            RecordingMode.MANUAL -> {
                if (!isRecording) {
                    startRecording()
                    btnRecord.text = getString(R.string.status_recording)
                    bgDrawable.setColor("#B71C1C".toColorInt())
                    tvStatus.text = getString(R.string.recording_active)
                    tvStatus.setTextColor("#00E676".toColorInt())
                } else {
                    stopRecording()
                    btnRecord.text = getString(R.string.btn_record)
                    bgDrawable.setColor("#222222".toColorInt())
                }
            }
            RecordingMode.AUTO_IDLE -> {
                startRecording()
                currentRecordMode = RecordingMode.AUTO_RECORDING
                btnRecord.text = getString(R.string.status_auto_running)
                bgDrawable.setColor("#B71C1C".toColorInt())
            }
            RecordingMode.AUTO_RECORDING -> {
                stopRecording()
                currentRecordMode = RecordingMode.AUTO_IDLE
                btnRecord.text = getString(R.string.status_auto_idle)
                bgDrawable.setColor("#0D47A1".toColorInt())
            }
        }
    }

    private fun startRecording() {
        isRecording = true
        sensorProcessor.isRecording = true
        recordedEntries.clear()
    }

    private fun applySettings() {
        sensorProcessor.resetDurationMillis = settingsManager.resetDurationSeconds * 1000L
        sensorProcessor.smoothingAlpha = settingsManager.smoothingFactor.toDouble()
        gaugeView.setInverted(settingsManager.isAxisInverted)
        
        if (settingsManager.isOrientationSaveEnabled) {
            val savedOrient = settingsManager.lockedOrientation
            if (savedOrient != -1) {
                isOrientationLocked = true
                requestedOrientation = savedOrient
                (btnLockView.background as GradientDrawable).setColor("#FF1744".toColorInt())
                btnLockView.text = getString(R.string.btn_locked)
            }
        }

        if (!isRecording) {
            currentRecordMode = settingsManager.defaultRecordingMode
            val bgDrawable = btnRecord.background as GradientDrawable
            when (currentRecordMode) {
                RecordingMode.MANUAL -> {
                    btnRecord.text = getString(R.string.btn_record)
                    bgDrawable.setColor("#222222".toColorInt())
                }
                RecordingMode.AUTO_IDLE -> {
                    btnRecord.text = getString(R.string.status_auto_idle)
                    bgDrawable.setColor("#0D47A1".toColorInt())
                }
                else -> {}
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        sensorProcessor.isRecording = false
        showSaveDialog()
    }

    private fun toggleAutoMode() {
        val bgDrawable = btnRecord.background as GradientDrawable
        if ((currentRecordMode == RecordingMode.MANUAL) || (currentRecordMode == RecordingMode.AUTO_RECORDING)) {
            currentRecordMode = RecordingMode.AUTO_IDLE
            isRecording = false
            sensorProcessor.isRecording = false
            btnRecord.text = getString(R.string.status_auto_idle)
            bgDrawable.setColor("#0D47A1".toColorInt())
            android.widget.Toast.makeText(this, getString(R.string.toast_auto_mode), android.widget.Toast.LENGTH_SHORT).show()
        } else {
            currentRecordMode = RecordingMode.MANUAL
            isRecording = false
            sensorProcessor.isRecording = false
            btnRecord.text = getString(R.string.btn_record)
            bgDrawable.setColor("#222222".toColorInt())
            android.widget.Toast.makeText(this, getString(R.string.toast_manual_mode), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAutoStartCondition(speedKmH: Double) {
        if (currentRecordMode == RecordingMode.AUTO_IDLE && !isRecording && speedKmH > settingsManager.autoStartSpeedKmH) {
            startRecording()
            currentRecordMode = RecordingMode.AUTO_RECORDING
            handler.post {
                btnRecord.text = getString(R.string.status_auto_running)
                (btnRecord.background as GradientDrawable).setColor("#B71C1C".toColorInt())
                tvStatus.text = getString(R.string.auto_recording_status)
                tvStatus.setTextColor("#00E676".toColorInt())
            }
        }
    }

    private fun showSaveDialog() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val defaultFileName = dateFormat.format(java.util.Date())
        val input = android.widget.EditText(this).apply {
            setText(defaultFileName)
            setTextColor(Color.WHITE)
            setBackgroundColor("#222222".toColorInt())
            setPadding(40, 30, 40, 30)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.save_tour_title))
            .setMessage(getString(R.string.save_tour_msg))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val fileName = input.text.toString().trim().ifEmpty { defaultFileName }
                tourExporter.saveTourToGpx(fileName, recordedEntries)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showInstructionsDialog() {
        val title = getString(R.string.instructions_title)
        val message = getString(R.string.instructions_msg)
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
            display?.rotation ?: android.view.Surface.ROTATION_0
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
        applySettings()
        sensorProcessor.start()
        locationTracker.start()
    }

    override fun onPause() {
        super.onPause()
        sensorProcessor.stop()
        locationTracker.stop()
    }
}
