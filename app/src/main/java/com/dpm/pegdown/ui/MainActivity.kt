package com.dpm.pegdown.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(),
    RecordingService.RecordingUpdateListener {

    private var recordingService: RecordingService? = null
    private var isBound = false

    private lateinit var tourExporter: TourExporter
    private lateinit var settingsManager: SettingsManager

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

    /**
     * Lokaler UI-Zustand.
     *
     * Der eigentliche Aufnahmezustand kommt immer vom RecordingService.
     */
    private var isRecording = false

    private var currentRecordMode = RecordingMode.MANUAL

    private val handler = Handler(Looper.getMainLooper())

    private var lastGlobalUIUpdateTime = 0L
    private val UI_UPDATE_INTERVAL_MS = 16L

    private var lastTourL = 0.0
    private var lastTourR = 0.0
    private var lastTourAcc = 0.0
    private var lastTourBrake = 0.0

    override fun attachBaseContext(newBase: Context) {
        val manager = SettingsManager(newBase)

        super.attachBaseContext(
            LocaleHelper.wrapContext(
                newBase,
                manager.selectedLanguage
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager(this)
        tourExporter = TourExporter(this)

        checkPermissions()

        /*
         * Service starten.
         *
         * Der Service bleibt auch bestehen, wenn die Activity z. B.
         * durch Rotation neu aufgebaut wird.
         */
        val serviceIntent = Intent(this, RecordingService::class.java)
        startService(serviceIntent)

        setupUI()
    }

    override fun onStart() {
        super.onStart()

        val intent = Intent(this, RecordingService::class.java)

        bindService(
            intent,
            serviceConnection,
            BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()

        if (isBound) {
            recordingService?.setUpdateListener(null)

            unbindService(serviceConnection)

            isBound = false
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // SERVICE CONNECTION
    // -------------------------------------------------------------------------

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            val binder = service as? RecordingService.LocalBinder
                ?: return

            recordingService = binder.getService()
            isBound = true

            recordingService?.setUpdateListener(this@MainActivity)

            applySettingsToService()
            syncRecordingStateFromService()
            updateCalibrationUI()
            updateRecordButtonUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            recordingService = null
        }
    }

    // -------------------------------------------------------------------------
    // CONFIGURATION / ROTATION
    // -------------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        /*
         * Nur die Oberfläche neu aufbauen.
         *
         * Der RecordingService und seine Sensoren laufen weiter.
         */
        setupUI()

        applySettingsToService()
        syncRecordingStateFromService()
        updateCalibrationUI()
        updateRecordButtonUI()
    }

    // -------------------------------------------------------------------------
    // PERMISSIONS
    // -------------------------------------------------------------------------

    private fun checkPermissions() {

        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            permissions.add(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            recordingService?.startTracking()
        }
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private fun setupUI() {

        val contentView =
            findViewById<ViewGroup>(android.R.id.content)

        contentView.removeAllViews()

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ---------------------------------------------------------------------
        // GAUGE
        // ---------------------------------------------------------------------

        gaugeView = LeanAngleGauge(this).apply {

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            setOnClickListener {
                startCalibration()
            }
        }

        rootLayout.addView(gaugeView)

        val density = resources.displayMetrics.density

        val btnWidthPx =
            (145f * density).toInt()

        val btnHeightPx =
            (45f * density).toInt()

        // ---------------------------------------------------------------------
        // BUTTON FACTORY
        // ---------------------------------------------------------------------

        fun createButton(
            textValue: String,
            color: String,
            topMarginDp: Int
        ): Button {

            return Button(this).apply {

                text = textValue
                textSize = 13f
                isAllCaps = false

                setTextColor(Color.WHITE)

                layoutParams =
                    LinearLayout.LayoutParams(
                        btnWidthPx,
                        btnHeightPx
                    ).apply {
                        topMargin =
                            (topMarginDp * density).toInt()
                    }

                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 16f
                        setColor(color.toColorInt())
                        setStroke(
                            1,
                            "#555555".toColorInt()
                        )
                    }

                setPadding(
                    12,
                    4,
                    12,
                    4
                )
            }
        }

        // ---------------------------------------------------------------------
        // LOCK BUTTON
        // ---------------------------------------------------------------------

        btnLockView = createButton(
            getString(R.string.btn_lock_view),
            "#222222",
            8
        ).apply {

            setOnClickListener {

                isOrientationLocked =
                    !isOrientationLocked

                val bg =
                    background as GradientDrawable

                if (isOrientationLocked) {

                    lockCurrentOrientation()

                    text =
                        getString(
                            R.string.btn_locked
                        )

                    bg.setColor(
                        "#FF1744".toColorInt()
                    )

                } else {

                    requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR

                    text =
                        getString(
                            R.string.btn_lock_view
                        )

                    bg.setColor(
                        "#222222".toColorInt()
                    )
                }

                if (
                    settingsManager.isOrientationSaveEnabled
                ) {
                    settingsManager.lockedOrientation =
                        if (isOrientationLocked) {
                            requestedOrientation
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                }
            }
        }

        // ---------------------------------------------------------------------
        // RESET BUTTON
        // ---------------------------------------------------------------------

        val btnReset = createButton(
            getString(R.string.btn_reset_tour),
            "#222222",
            8
        ).apply {

            setOnClickListener {
                recordingService?.resetTour()

                lastTourL = 0.0
                lastTourR = 0.0
                lastTourAcc = 0.0
                lastTourBrake = 0.0

                updateTourMax()
            }
        }

        // ---------------------------------------------------------------------
        // INFO BUTTON
        // ---------------------------------------------------------------------

        btnInfo = createButton(
            getString(R.string.btn_instructions),
            "#222222",
            8
        ).apply {

            setOnClickListener {
                showInstructionsDialog()
            }
        }

        // ---------------------------------------------------------------------
        // SETTINGS BUTTON
        // ---------------------------------------------------------------------

        btnSettings = createButton(
            getString(R.string.btn_settings),
            "#222222",
            8
        ).apply {

            setOnClickListener {
                startActivity(
                    Intent(
                        this@MainActivity,
                        SettingsActivity::class.java
                    )
                )
            }
        }

        // ---------------------------------------------------------------------
        // RECORD BUTTON
        // ---------------------------------------------------------------------

        btnRecord = createButton(
            getString(R.string.btn_record),
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

        // ---------------------------------------------------------------------
        // LEFT TOP CONTAINER
        // ---------------------------------------------------------------------

        val leftContainer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    20,
                    10,
                    20,
                    10
                )

                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity =
                            Gravity.TOP or Gravity.START
                    }
            }

        tvStatus = TextView(this).apply {

            textSize = 11f

            text =
                getString(
                    R.string.not_calibrated
                )

            setTextColor(
                "#FF5252".toColorInt()
            )

            setPadding(
                0,
                0,
                0,
                4
            )
        }

        leftContainer.addView(tvStatus)
        leftContainer.addView(btnSettings)
        leftContainer.addView(btnRecord)

        rootLayout.addView(leftContainer)

        // ---------------------------------------------------------------------
        // RIGHT TOP CONTAINER
        // ---------------------------------------------------------------------

        val rightContainer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.END

                setPadding(
                    10,
                    5,
                    10,
                    5
                )

                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity =
                            Gravity.TOP or Gravity.END
                    }
            }

        rightContainer.addView(btnLockView)
        rightContainer.addView(btnReset)
        rightContainer.addView(btnInfo)

        rootLayout.addView(rightContainer)

        // ---------------------------------------------------------------------
        // ACCELERATION / BRAKING
        // ---------------------------------------------------------------------

        val isLandscape =
            resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE

        tvAccelLeft =
            createValueView(Gravity.TOP or Gravity.START)

        tvAccelRight =
            createValueView(Gravity.TOP or Gravity.END)

        rootLayout.addView(tvAccelLeft)
        rootLayout.addView(tvAccelRight)

        // ---------------------------------------------------------------------
        // WINDOW INSETS
        // ---------------------------------------------------------------------

        ViewCompat.setOnApplyWindowInsetsListener(
            rootLayout
        ) { _, insets ->

            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.statusBars()
                )

            val leftParams =
                leftContainer.layoutParams
                        as FrameLayout.LayoutParams

            leftParams.topMargin =
                bars.top + 10

            leftContainer.layoutParams =
                leftParams

            val rightParams =
                rightContainer.layoutParams
                        as FrameLayout.LayoutParams

            rightParams.topMargin =
                bars.top + 10

            rightContainer.layoutParams =
                rightParams

            insets
        }

        // ---------------------------------------------------------------------
        // MAX TOUR
        // ---------------------------------------------------------------------

        val bottomContainer =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    24,
                    12,
                    24,
                    12
                )

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.RECTANGLE

                        cornerRadius = 16f

                        setColor(
                            "#CC111111".toColorInt()
                        )

                        setStroke(
                            1,
                            "#333333".toColorInt()
                        )
                    }

                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {

                        gravity =
                            Gravity.BOTTOM or
                                    Gravity.CENTER_HORIZONTAL

                        bottomMargin = 24
                    }
            }

        tvMaxTour = TextView(this).apply {

            textSize = 14f

            setTextColor(
                "#00B0FF".toColorInt()
            )

            gravity =
                Gravity.CENTER
        }

        bottomContainer.addView(tvMaxTour)

        rootLayout.addView(
            bottomContainer
        )

        // ---------------------------------------------------------------------
        // SPEED
        // ---------------------------------------------------------------------

        tvSpeed = TextView(this).apply {

            textSize =
                if (isLandscape) {
                    32f
                } else {
                    24f
                }

            text =
                getString(
                    R.string.speed_format,
                    0.0
                )

            setTextColor(Color.WHITE)

            gravity =
                Gravity.CENTER

            setPadding(
                20,
                10,
                20,
                10
            )

            background =
                GradientDrawable().apply {

                    shape =
                        GradientDrawable.RECTANGLE

                    cornerRadius = 16f

                    setColor(
                        "#CC111111".toColorInt()
                    )

                    setStroke(
                        1,
                        "#333333".toColorInt()
                    )
                }
        }

        rootLayout.addView(
            tvSpeed,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.BOTTOM or
                            Gravity.CENTER_HORIZONTAL

                bottomMargin =
                    (80 * density).toInt()
            }
        )

        setContentView(rootLayout)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        /*
         * Nach jedem Neuaufbau der UI den aktuellen
         * Tourzustand wieder anzeigen.
         */
        updateTourMax()
    }

    // -------------------------------------------------------------------------
    // VALUE VIEW
    // -------------------------------------------------------------------------

    private fun createValueView(
        gravityValue: Int
    ): TextView {

        val isLandscape =
            resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE

        val isLeft =
            (gravityValue and Gravity.START) ==
                    Gravity.START

        return TextView(this).apply {

            textSize =
                if (isLandscape) {
                    22f
                } else {
                    16f
                }

            text =
                getString(
                    if (isLeft) {
                        R.string.acc_format
                    } else {
                        R.string.brake_format
                    },
                    0.0
                )

            setTextColor(
                if (isLeft) {
                    "#00E676".toColorInt()
                } else {
                    "#FF3D00".toColorInt()
                }
            )

            gravity =
                Gravity.CENTER

            setPadding(
                14,
                8,
                14,
                8
            )

            background =
                GradientDrawable().apply {

                    shape =
                        GradientDrawable.RECTANGLE

                    cornerRadius = 16f

                    setColor(
                        "#CC111111".toColorInt()
                    )

                    setStroke(
                        1,
                        "#333333".toColorInt()
                    )
                }

            layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {

                    gravity =
                        Gravity.TOP or
                                if (isLeft) {
                                    Gravity.START
                                } else {
                                    Gravity.END
                                }

                    val height =
                        resources.displayMetrics
                            .heightPixels
                            .toFloat()

                    val width =
                        resources.displayMetrics
                            .widthPixels
                            .toFloat()

                    if (isLandscape) {

                        topMargin =
                            (height * 0.75f).toInt()

                        if (isLeft) {
                            leftMargin = 100
                        } else {
                            rightMargin = 100
                        }

                    } else {

                        val radius =
                            width * 0.42f

                        topMargin =
                            (
                                    height * 0.52f +
                                            radius * 0.25f +
                                            40
                                    ).toInt()

                        if (isLeft) {
                            leftMargin = 40
                        } else {
                            rightMargin = 40
                        }
                    }
                }
        }
    }

    // -------------------------------------------------------------------------
    // SENSOR CALLBACK
    // -------------------------------------------------------------------------

    override fun onSensorUpdate(
        current: Double,
        tempL: Double,
        tempR: Double,
        tourL: Double,
        tourR: Double
    ) {

        if (!::gaugeView.isInitialized) {
            return
        }

        gaugeView.updateData(
            current,
            tempL,
            tempR,
            tourL,
            tourR
        )

        lastTourL = tourL
        lastTourR = tourR

        updateTourMax()

        updateCalibrationUI()
    }

    // -------------------------------------------------------------------------
    // ACCEL CALLBACK
    // -------------------------------------------------------------------------

    override fun onAccelUpdate(
        accel: Double,
        brake: Double,
        tourMaxAccel: Double,
        tourMaxBrake: Double
    ) {

        val now =
            System.currentTimeMillis()

        if (
            now - lastGlobalUIUpdateTime <
            UI_UPDATE_INTERVAL_MS
        ) {
            return
        }

        if (!::tvAccelLeft.isInitialized) {
            return
        }

        tvAccelLeft.text =
            getString(
                R.string.acc_format,
                accel
            )

        tvAccelRight.text =
            getString(
                R.string.brake_format,
                abs(brake)
            )

        lastTourAcc = tourMaxAccel
        lastTourBrake = tourMaxBrake

        updateTourMax()

        lastGlobalUIUpdateTime = now
    }

    // -------------------------------------------------------------------------
    // LOCATION CALLBACK
    // -------------------------------------------------------------------------

    override fun onLocationUpdate(
        location: Location,
        speedKmH: Double
    ) {

        if (!::tvSpeed.isInitialized) {
            return
        }

        handler.post {

            if (!::tvSpeed.isInitialized) {
                return@post
            }

            tvSpeed.text =
                getString(
                    R.string.speed_format,
                    speedKmH
                )
        }

        checkAutoStart(speedKmH)
    }

    // -------------------------------------------------------------------------
    // TOUR MAX DISPLAY
    // -------------------------------------------------------------------------

    private fun updateTourMax() {

        if (!::tvMaxTour.isInitialized) {
            return
        }

        tvMaxTour.text =
            getString(
                R.string.tour_max_format,
                abs(lastTourL),
                abs(lastTourR),
                lastTourAcc,
                abs(lastTourBrake)
            )
    }

    // -------------------------------------------------------------------------
    // CALIBRATION
    // -------------------------------------------------------------------------

    private fun startCalibration() {

        val service =
            recordingService ?: return

        /*
         * Keine zweite Kalibrierung starten,
         * wenn bereits eine läuft.
         */
        if (service.isCalibrationInProgress()) {
            return
        }

        /*
         * Ohne Sensor-Quaternion keine sinnvolle
         * Kalibrierung möglich.
         */
        if (!service.hasSensorData()) {

            tvStatus.text =
                getString(R.string.status_sensor_not_ready)

            tvStatus.setTextColor(
                "#FF5252".toColorInt()
            )

            return
        }

        tvStatus.text =
            getString(R.string.status_calibrating)

        tvStatus.setTextColor(
            "#FFB300".toColorInt()
        )

        service.calibrate()
    }

    private fun updateCalibrationUI() {

        if (!::tvStatus.isInitialized) {
            return
        }

        val service =
            recordingService ?: return

        when {

            service.isCalibrationInProgress() -> {

                tvStatus.text =
                    getString(R.string.status_calibrating)

                tvStatus.setTextColor(
                    "#FFB300".toColorInt()
                )
            }

            service.isCalibrated() -> {

                tvStatus.text =
                    getString(R.string.status_calibrated)

                tvStatus.setTextColor(
                    "#00E676".toColorInt()
                )
            }

            else -> {

                tvStatus.text =
                    getString(
                        R.string.not_calibrated
                    )

                tvStatus.setTextColor(
                    "#FF5252".toColorInt()
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // SERVICE SETTINGS
    // -------------------------------------------------------------------------

    private fun applySettingsToService() {

        val service =
            recordingService

        if (service != null) {

            service.updateSettings(
                settingsManager.resetDurationSeconds * 1000L,
                settingsManager.smoothingFactor.toDouble()
            )

            /*
             * Wichtig:
             *
             * startTracking() muss im Service idempotent sein.
             * Dadurch ist dieser Aufruf auch nach Activity-Rebuild
             * bzw. erneutem Binding sicher.
             */
            service.startTracking()

            syncRecordingStateFromService()

            /*
             * Nur dann Default-Modus übernehmen,
             * wenn gerade keine Aufnahme läuft.
             */
            if (!isRecording) {

                currentRecordMode =
                    settingsManager.defaultRecordingMode
            }

            updateRecordButtonUI()
            updateCalibrationUI()
        }

        if (::gaugeView.isInitialized) {

            gaugeView.setInverted(
                settingsManager.isAxisInverted
            )
        }

        applyOrientationSettings()
    }

    // -------------------------------------------------------------------------
    // RECORDING STATE
    // -------------------------------------------------------------------------

    private fun syncRecordingStateFromService() {

        val service =
            recordingService ?: return

        isRecording =
            service.isRecording
    }

    // -------------------------------------------------------------------------
    // RECORD BUTTON
    // -------------------------------------------------------------------------

    private fun handleRecordButtonClick() {

        val service =
            recordingService ?: return

        /*
         * Immer zuerst den tatsächlichen Servicezustand
         * verwenden.
         */
        syncRecordingStateFromService()

        when (currentRecordMode) {

            RecordingMode.MANUAL -> {

                if (!isRecording) {

                    service.startTourRecording()

                } else {

                    service.stopTourRecording()

                    showSaveDialog()
                }
            }

            RecordingMode.AUTO_IDLE -> {

                /*
                 * Manuelles Starten aus AUTO_IDLE
                 * führt in AUTO_RECORDING.
                 */
                service.startTourRecording()

                currentRecordMode =
                    RecordingMode.AUTO_RECORDING
            }

            RecordingMode.AUTO_RECORDING -> {

                service.stopTourRecording()

                currentRecordMode =
                    RecordingMode.AUTO_IDLE

                showSaveDialog()
            }
        }

        syncRecordingStateFromService()
        updateRecordButtonUI()
    }

    // -------------------------------------------------------------------------
    // RECORD BUTTON UI
    // -------------------------------------------------------------------------

    private fun updateRecordButtonUI() {

        if (!::btnRecord.isInitialized) {
            return
        }

        val bg =
            btnRecord.background
                    as? GradientDrawable
                ?: return

        when {

            isRecording -> {

                btnRecord.text =
                    getString(
                        R.string.status_recording
                    )

                bg.setColor(
                    "#B71C1C".toColorInt()
                )
            }

            currentRecordMode ==
                    RecordingMode.AUTO_IDLE -> {

                btnRecord.text =
                    getString(
                        R.string.status_auto_idle
                    )

                bg.setColor(
                    "#0D47A1".toColorInt()
                )
            }

            else -> {

                btnRecord.text =
                    getString(
                        R.string.btn_record
                    )

                bg.setColor(
                    "#222222".toColorInt()
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // AUTO MODE
    // -------------------------------------------------------------------------

    private fun toggleAutoMode() {

        val service =
            recordingService

        syncRecordingStateFromService()

        /*
         * AUTO_RECORDING -> Aufnahme beenden
         * und danach in AUTO_IDLE wechseln.
         */
        if (
            currentRecordMode ==
            RecordingMode.AUTO_RECORDING &&
            isRecording
        ) {

            service?.stopTourRecording()

            isRecording = false

            currentRecordMode =
                RecordingMode.AUTO_IDLE

            showSaveDialog()

            updateRecordButtonUI()

            return
        }

        currentRecordMode =
            if (
                currentRecordMode ==
                RecordingMode.AUTO_IDLE
            ) {
                RecordingMode.MANUAL
            } else {
                RecordingMode.AUTO_IDLE
            }

        updateRecordButtonUI()
    }

    // -------------------------------------------------------------------------
    // AUTO START
    // -------------------------------------------------------------------------

    private fun checkAutoStart(
        speedKmH: Double
    ) {

        val service =
            recordingService ?: return

        /*
         * Auto-Start nur wenn:
         *
         * - AUTO_IDLE
         * - keine laufende Aufnahme
         * - Geschwindigkeit >= Grenzwert
         * - Sensor ist kalibriert
         */
        if (
            currentRecordMode !=
            RecordingMode.AUTO_IDLE
        ) {
            return
        }

        if (isRecording) {
            return
        }

        if (
            speedKmH <
            settingsManager.autoStartSpeedKmH
        ) {
            return
        }

        /*
         * Ohne Kalibrierung sollte keine echte
         * Touraufzeichnung gestartet werden.
         */
        if (!service.isCalibrated()) {
            return
        }

        service.startTourRecording()

        isRecording = true

        currentRecordMode =
            RecordingMode.AUTO_RECORDING

        handler.post {
            updateRecordButtonUI()
        }
    }

    // -------------------------------------------------------------------------
    // SAVE DIALOG
    // -------------------------------------------------------------------------

    private fun showSaveDialog() {

        val dateFormat =
            SimpleDateFormat(
                "yyyy-MM-dd_HH-mm",
                Locale.getDefault()
            )

        val name =
            dateFormat.format(Date())

        val input =
            EditText(this).apply {

                setText(name)

                setTextColor(Color.WHITE)

                setBackgroundColor(
                    "#222222".toColorInt()
                )

                setPadding(
                    40,
                    30,
                    40,
                    30
                )
            }

        android.app.AlertDialog.Builder(
            this,
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        )
            .setTitle(
                getString(
                    R.string.save_tour_title
                )
            )
            .setMessage(
                getString(
                    R.string.save_tour_msg
                )
            )
            .setView(input)
            .setPositiveButton(
                getString(
                    R.string.btn_save
                )
            ) { _, _ ->

                /*
                 * Snapshot erstellen.
                 *
                 * Damit der Export nicht von späteren
                 * Änderungen an recordedEntries beeinflusst wird.
                 */
                val entries =
                    recordingService
                        ?.recordedEntries
                        ?.toList()
                        ?: emptyList()

                val fileName =
                    input.text
                        .toString()
                        .trim()

                if (fileName.isEmpty()) {
                    return@setPositiveButton
                }

                if (
                    settingsManager.exportFormat ==
                    ExportFormat.GPX
                ) {

                    tourExporter.saveTourToGpx(
                        fileName,
                        entries
                    )

                } else {

                    tourExporter.saveTourToCsv(
                        fileName,
                        entries
                    )
                }
            }
            .setNegativeButton(
                getString(
                    R.string.btn_cancel
                ),
                null
            )
            .show()
    }

    // -------------------------------------------------------------------------
    // INSTRUCTIONS
    // -------------------------------------------------------------------------

    private fun showInstructionsDialog() {

        val textView =
            TextView(this).apply {

                text =
                    getString(
                        R.string.instructions_msg
                    )

                textSize = 14f

                setTextColor(Color.WHITE)

                setPadding(
                    50,
                    30,
                    50,
                    30
                )
            }

        val scrollView =
            ScrollView(this).apply {

                addView(textView)

                setBackgroundColor(
                    Color.BLACK
                )
            }

        android.app.AlertDialog.Builder(
            this,
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        )
            .setTitle(
                getString(
                    R.string.instructions_title
                )
            )
            .setView(scrollView)
            .setPositiveButton(
                "OK",
                null
            )
            .show()
    }

    // -------------------------------------------------------------------------
    // ORIENTATION
    // -------------------------------------------------------------------------

    private fun applyOrientationSettings() {

        if (!::btnLockView.isInitialized) {
            return
        }

        val bg =
            btnLockView.background
                    as? GradientDrawable
                ?: return

        if (
            settingsManager.isOrientationSaveEnabled
        ) {

            val savedOrientation =
                settingsManager.lockedOrientation

            if (
                savedOrientation !=
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ) {

                isOrientationLocked = true

                requestedOrientation =
                    savedOrientation

                btnLockView.text =
                    getString(
                        R.string.btn_locked
                    )

                bg.setColor(
                    "#FF1744".toColorInt()
                )

            } else {

                isOrientationLocked = false

                requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR

                btnLockView.text =
                    getString(
                        R.string.btn_lock_view
                    )

                bg.setColor(
                    "#222222".toColorInt()
                )
            }

        } else {

            /*
             * Wenn Speichern der Orientierung deaktiviert
             * ist, darf eine alte gespeicherte Sperre
             * nicht weiter angewendet werden.
             */
            isOrientationLocked = false

            requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR

            btnLockView.text =
                getString(
                    R.string.btn_lock_view
                )

            bg.setColor(
                "#222222".toColorInt()
            )
        }
    }

    private fun lockCurrentOrientation() {

        val currentOrientation =
            resources.configuration.orientation

        val rotation =
            if (
                android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.R
            ) {

                display?.rotation
                    ?: Surface.ROTATION_0

            } else {

                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }

        requestedOrientation =
            if (
                currentOrientation ==
                Configuration.ORIENTATION_LANDSCAPE
            ) {

                if (
                    rotation ==
                    Surface.ROTATION_270
                ) {

                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE

                } else {

                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }

            } else {

                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
    }

    // -------------------------------------------------------------------------
    // RESUME
    // -------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()

        if (
            ::settingsManager.isInitialized &&
            ::gaugeView.isInitialized
        ) {

            /*
             * Einstellungen aktualisieren.
             *
             * startTracking() im Service muss idempotent sein.
             */
            applySettingsToService()

            syncRecordingStateFromService()
            updateCalibrationUI()
            updateRecordButtonUI()
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}