package com.dpm.pegdown.ui

import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
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
import com.dpm.pegdown.model.ExportFormat
import com.dpm.pegdown.model.RecordingMode
import com.dpm.pegdown.service.RecordingService
import com.dpm.pegdown.util.LocaleHelper
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), RecordingService.RecordingUpdateListener {

    private var recordingService: RecordingService? = null
    private var isBound = false

    private lateinit var tourExporter: TourExporter
    private lateinit var settingsManager: SettingsManager

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true
            recordingService?.setUpdateListener(this@MainActivity)
            applySettingsToService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            recordingService = null
        }
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
    private var isRecording = false
    private var currentRecordMode = RecordingMode.MANUAL
    private val handler = Handler(Looper.getMainLooper())
    private var lastUIUpdateTime = 0L

    private var lastTourL = 0.0
    private var lastTourR = 0.0
    private var lastTourAcc = 0.0
    private var lastTourBrake = 0.0

    override fun attachBaseContext(newBase: Context) {
        val manager = SettingsManager(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, manager.selectedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager(this)
        tourExporter = TourExporter(this)

        checkPermissions()

        val serviceIntent = Intent(this, RecordingService::class.java)
        startService(serviceIntent)

        setupUI()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            if (!isRecording) {
                recordingService?.stopTracking()
            }
            recordingService?.setUpdateListener(null)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun setupUI() {
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor("#000000".toColorInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        gaugeView = LeanAngleGauge(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setOnClickListener {
                recordingService?.calibrate()
                tvStatus.text = getString(R.string.calibrated_format, 0.0)
                tvStatus.setTextColor("#00E676".toColorInt())
            }
        }
        rootLayout.addView(gaugeView)

        val density = resources.displayMetrics.density
        val btnWPx = (145 * density).toInt()
        val btnHPx = (45 * density).toInt()

        fun createBtn(txt: String, color: String, topM: Int): Button {
            return Button(this).apply {
                text = txt
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(btnWPx, btnHPx).apply { topMargin = (topM * density).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f
                    setColor(color.toColorInt())
                    setStroke(1, "#555555".toColorInt())
                }
                setPadding(12, 4, 12, 4)
            }
        }

        btnLockView = createBtn(getString(R.string.btn_lock_view), "#222222", 8).apply {
            setOnClickListener {
                isOrientationLocked = !isOrientationLocked
                if (isOrientationLocked) {
                    lockCurrentOrientation()
                    text = getString(R.string.btn_locked)
                    (background as GradientDrawable).setColor("#FF1744".toColorInt())
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    text = getString(R.string.btn_lock_view)
                    (background as GradientDrawable).setColor("#222222".toColorInt())
                }
                if (settingsManager.isOrientationSaveEnabled) settingsManager.lockedOrientation = requestedOrientation
            }
        }

        val btnReset = createBtn(getString(R.string.btn_reset_tour), "#222222", 8).apply {
            setOnClickListener { recordingService?.resetTour() }
        }

        btnInfo = createBtn(getString(R.string.btn_instructions), "#222222", 8).apply {
            setOnClickListener { showInstructionsDialog() }
        }

        btnSettings = createBtn(getString(R.string.btn_settings), "#222222", 8).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        btnRecord = createBtn(getString(R.string.btn_record), "#222222", 8).apply {
            setOnClickListener { handleRecordButtonClick() }
            setOnLongClickListener { toggleAutoMode(); true }
        }

        val leftC = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.START }
        }
        tvStatus = TextView(this).apply {
            textSize = 11f
            text = getString(R.string.not_calibrated)
            setTextColor("#FF5252".toColorInt())
            setPadding(0, 0, 0, 4)
        }
        leftC.addView(tvStatus); leftC.addView(btnSettings); leftC.addView(btnRecord)
        rootLayout.addView(leftC)

        val rightC = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(10, 5, 10, 5)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.END }
        }
        rightC.addView(btnLockView); rightC.addView(btnReset); rightC.addView(btnInfo)
        rootLayout.addView(rightC)

        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        tvAccelLeft = createValueView(Gravity.TOP or Gravity.START, 24, 0)
        tvAccelRight = createValueView(Gravity.TOP or Gravity.END, 0, 24)
        rootLayout.addView(tvAccelLeft); rootLayout.addView(tvAccelRight)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            (leftC.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + 10
            (rightC.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + 10
            insets
        }

        val bottomC = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 24
            }
        }
        tvMaxTour = TextView(this).apply { textSize = 14f; setTextColor("#00B0FF".toColorInt()); gravity = Gravity.CENTER }
        bottomC.addView(tvMaxTour); rootLayout.addView(bottomC)

        tvSpeed = TextView(this).apply {
            textSize = if (isLand) 32f else 24f
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
        rootLayout.addView(tvSpeed, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (80 * density).toInt()
        })

        setContentView(rootLayout)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun createValueView(grav: Int, leftM: Int, rightM: Int): TextView {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return TextView(this).apply {
            textSize = if (isLandscape) 22f else 16f
            setTextColor(if (leftM > 0) "#00E676".toColorInt() else "#FF3D00".toColorInt())
            gravity = Gravity.CENTER
            setPadding(14, 8, 14, 8)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#CC111111".toColorInt())
                setStroke(1, "#333333".toColorInt())
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = grav
                val h = resources.displayMetrics.heightPixels.toFloat()
                val w = resources.displayMetrics.widthPixels.toFloat()
                val cY = if (isLandscape) h * 0.95f else h * 0.52f
                val rad = if (isLandscape) kotlin.math.min(h * 0.82f, w * 0.38f) else kotlin.math.min(w, h * 2.2f) * 0.42f
                topMargin = (if (isLandscape) cY - (rad * 0.15f) else (cY + (rad * 0.25f) - 30f)).toInt()
                leftMargin = leftM; rightMargin = rightM
            }
        }
    }

    override fun onSensorUpdate(current: Double, tempL: Double, tempR: Double, tourL: Double, tourR: Double) {
        val now = System.currentTimeMillis()
        if ((now - lastUIUpdateTime) > 16) {
            gaugeView.updateData(current, tempL, tempR, tourL, tourR)
            lastTourL = tourL; lastTourR = tourR
            updateTourMax()
            lastUIUpdateTime = now
        }
    }

    override fun onAccelUpdate(accel: Double, brake: Double, tourMaxAccel: Double, tourMaxBrake: Double) {
        val now = System.currentTimeMillis()
        if ((now - lastUIUpdateTime) > 16) {
            tvAccelLeft.text = getString(R.string.acc_format, accel)
            tvAccelRight.text = getString(R.string.brake_format, abs(brake))
            lastTourAcc = tourMaxAccel; lastTourBrake = tourMaxBrake
            updateTourMax()
            lastUIUpdateTime = now
        }
    }

    override fun onLocationUpdate(location: Location, speedKmH: Double) {
        handler.post { tvSpeed.text = getString(R.string.speed_format, speedKmH) }
        checkAutoStart(speedKmH)
    }

    private fun updateTourMax() {
        tvMaxTour.text = getString(R.string.tour_max_format, abs(lastTourL), abs(lastTourR), lastTourAcc, abs(lastTourBrake))
    }

    private fun applySettingsToService() {
        recordingService?.let { s ->
            s.updateSettings(settingsManager.resetDurationSeconds * 1000L, settingsManager.smoothingFactor.toDouble())
            s.startTracking()
            isRecording = s.isRecording
            currentRecordMode = settingsManager.defaultRecordingMode
            updateRecordButtonUI()
        }
        gaugeView.setInverted(settingsManager.isAxisInverted)
        if (settingsManager.isOrientationSaveEnabled) {
            val orient = settingsManager.lockedOrientation
            if (orient != -1) {
                isOrientationLocked = true
                requestedOrientation = orient
                (btnLockView.background as GradientDrawable).setColor("#FF1744".toColorInt())
                btnLockView.text = getString(R.string.btn_locked)
            }
        }
    }

    private fun handleRecordButtonClick() {
        recordingService?.let { s ->
            when (currentRecordMode) {
                RecordingMode.MANUAL -> {
                    if (!isRecording) { s.startTourRecording(); isRecording = true }
                    else { s.stopTourRecording(); isRecording = false; showSaveDialog() }
                }
                RecordingMode.AUTO_IDLE -> { s.startTourRecording(); isRecording = true; currentRecordMode = RecordingMode.AUTO_RECORDING }
                RecordingMode.AUTO_RECORDING -> { s.stopTourRecording(); isRecording = false; currentRecordMode = RecordingMode.AUTO_IDLE; showSaveDialog() }
            }
            updateRecordButtonUI()
        }
    }

    private fun updateRecordButtonUI() {
        val bg = btnRecord.background as GradientDrawable
        when {
            isRecording -> { btnRecord.text = getString(R.string.status_recording); bg.setColor("#B71C1C".toColorInt()) }
            currentRecordMode == RecordingMode.AUTO_IDLE -> { btnRecord.text = getString(R.string.status_auto_idle); bg.setColor("#0D47A1".toColorInt()) }
            else -> { btnRecord.text = getString(R.string.btn_record); bg.setColor("#222222".toColorInt()) }
        }
    }

    private fun toggleAutoMode() {
        if (currentRecordMode == RecordingMode.MANUAL || currentRecordMode == RecordingMode.AUTO_RECORDING) {
            currentRecordMode = RecordingMode.AUTO_IDLE; isRecording = false
        } else {
            currentRecordMode = RecordingMode.MANUAL; isRecording = false
        }
        updateRecordButtonUI()
    }

    private fun checkAutoStart(spd: Double) {
        if (currentRecordMode == RecordingMode.AUTO_IDLE && !isRecording && (spd > settingsManager.autoStartSpeedKmH)) {
            recordingService?.startTourRecording()
            isRecording = true; currentRecordMode = RecordingMode.AUTO_RECORDING
            handler.post { updateRecordButtonUI(); tvStatus.text = getString(R.string.auto_recording_status); tvStatus.setTextColor("#00E676".toColorInt()) }
        }
    }

    private fun showSaveDialog() {
        val df = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val name = df.format(java.util.Date())
        val input = android.widget.EditText(this).apply { setText(name); setTextColor(Color.WHITE); setBackgroundColor("#222222".toColorInt()); setPadding(40, 30, 40, 30) }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.save_tour_title)).setMessage(getString(R.string.save_tour_msg)).setView(input)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val entries = recordingService?.recordedEntries ?: emptyList()
                if (settingsManager.exportFormat == ExportFormat.GPX) tourExporter.saveTourToGpx(input.text.toString(), entries)
                else tourExporter.saveTourToCsv(input.text.toString(), entries)
            }.setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun showInstructionsDialog() {
        val tv = TextView(this).apply { text = getString(R.string.instructions_msg); textSize = 14f; setTextColor(Color.WHITE); setPadding(50, 30, 50, 30) }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.instructions_title)).setView(android.widget.ScrollView(this).apply { addView(tv) }).setPositiveButton("OK", null).show()
    }

    private fun lockCurrentOrientation() {
        val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display?.rotation ?: 0 else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (rot == android.view.Surface.ROTATION_270) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onResume() { super.onResume(); applySettingsToService() }
}
