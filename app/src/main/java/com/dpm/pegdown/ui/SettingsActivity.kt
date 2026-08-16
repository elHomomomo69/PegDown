package com.dpm.pegdown.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dpm.pegdown.R
import com.dpm.pegdown.data.SettingsManager
import com.dpm.pegdown.model.ExportFormat
import com.dpm.pegdown.model.RecordingMode
import com.dpm.pegdown.util.LocaleHelper

class SettingsActivity : Activity() {

    private lateinit var settingsManager: SettingsManager

    override fun attachBaseContext(newBase: android.content.Context) {
        val manager = SettingsManager(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, manager.selectedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        val root = ScrollView(this).apply {
            setBackgroundColor("#000000".toColorInt())
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            container.setPadding(40, systemBars.top + 20, 40, systemBars.bottom + 40)
            insets
        }

        // Title
        container.addView(
            TextView(this).apply {
                text = getString(R.string.title_settings)
                textSize = 24f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 40)
            },
        )

        // 1. Reset Duration
        val tvResetLabel = createLabel(getString(R.string.setting_reset_duration, settingsManager.resetDurationSeconds))
        container.addView(tvResetLabel)
        container.addView(createDesc(getString(R.string.desc_reset_duration)))
        container.addView(
            SeekBar(this).apply {
                max = 30
                min = 1
                progress = settingsManager.resetDurationSeconds
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            val valP = if (p < 1) 1 else p
                            settingsManager.resetDurationSeconds = valP
                            tvResetLabel.text = getString(R.string.setting_reset_duration, valP)
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    },
                )
                setPadding(0, 20, 0, 40)
            },
        )

        // 2. Auto-Start Speed
        val tvSpeedLabel = createLabel(getString(R.string.setting_auto_start_speed, settingsManager.autoStartSpeedKmH))
        container.addView(tvSpeedLabel)
        container.addView(createDesc(getString(R.string.desc_auto_start_speed)))
        container.addView(SeekBar(this).apply {
            max = 50
            min = 1
            progress = settingsManager.autoStartSpeedKmH.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val valP = if (p < 1) 1 else p
                    settingsManager.autoStartSpeedKmH = valP.toFloat()
                    tvSpeedLabel.text = getString(R.string.setting_auto_start_speed, valP.toFloat())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            setPadding(0, 20, 0, 40)
        })

        // 3. Smoothing
        val tvSmoothLabel = createLabel(getString(R.string.setting_smoothing_with_val, String.format(java.util.Locale.US, "%.2f", settingsManager.smoothingFactor)))
        container.addView(tvSmoothLabel)
        container.addView(createDesc(getString(R.string.desc_smoothing)))
        container.addView(SeekBar(this).apply {
            max = 50 // 0.01 to 0.50
            min = 1
            progress = (settingsManager.smoothingFactor * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val valF = (if (p < 1) 1 else p) / 100f
                    settingsManager.smoothingFactor = valF
                    tvSmoothLabel.text = getString(R.string.setting_smoothing_with_val, String.format(java.util.Locale.US, "%.2f", valF))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            setPadding(0, 20, 0, 40)
        })

        // 4. Default Recording Mode
        container.addView(createLabel(getString(R.string.setting_default_mode)))
        container.addView(createDesc(getString(R.string.desc_default_mode)))
        val rgMode = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 10, 0, 40)
        }
        val rbManual = RadioButton(this).apply {
            text = getString(R.string.mode_manual)
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        val rbAuto = RadioButton(this).apply {
            text = getString(R.string.mode_auto)
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        rgMode.addView(rbManual)
        rgMode.addView(rbAuto)
        if (settingsManager.defaultRecordingMode == RecordingMode.MANUAL) rbManual.isChecked = true else rbAuto.isChecked = true
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            settingsManager.defaultRecordingMode = if (checkedId == rbManual.id) RecordingMode.MANUAL else RecordingMode.AUTO_IDLE
        }
        container.addView(rgMode)

        // 5. Export Format
        container.addView(createLabel(getString(R.string.setting_export_format)))
        container.addView(createDesc(getString(R.string.desc_export_format)))
        val rgExport = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 10, 0, 40)
        }
        val rbGpx = RadioButton(this).apply {
            text = getString(R.string.format_gpx)
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        val rbCsv = RadioButton(this).apply {
            text = getString(R.string.format_csv)
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        rgExport.addView(rbGpx)
        rgExport.addView(rbCsv)
        if (settingsManager.exportFormat == ExportFormat.GPX) rbGpx.isChecked = true else rbCsv.isChecked = true
        rgExport.setOnCheckedChangeListener { _, checkedId ->
            settingsManager.exportFormat = if (checkedId == rbGpx.id) ExportFormat.GPX else ExportFormat.CSV
        }
        container.addView(rgExport)

        // 6. Invert Axis
        val invertContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 10)
        }
        invertContainer.addView(createLabel(getString(R.string.setting_invert_axis)).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 0, 0)
        })
        invertContainer.addView(Switch(this).apply {
            isChecked = settingsManager.isAxisInverted
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isAxisInverted = isChecked
            }
        })
        container.addView(invertContainer)
        container.addView(createDesc(getString(R.string.desc_invert_axis)))
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // 6. Save Orientation
        val switchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 10)
        }
        switchContainer.addView(createLabel(getString(R.string.setting_save_orientation)).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 0, 0)
        })
        switchContainer.addView(Switch(this).apply {
            isChecked = settingsManager.isOrientationSaveEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isOrientationSaveEnabled = isChecked
            }
        })
        container.addView(switchContainer)
        container.addView(createDesc(getString(R.string.desc_save_orientation)))
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // 7. Language Selection
        val languages = listOf("auto", "en", "de", "es", "fr", "pl", "zh", "ja")
        val langNames = listOf(
            getString(R.string.lang_auto),
            getString(R.string.lang_en),
            getString(R.string.lang_de),
            getString(R.string.lang_es),
            getString(R.string.lang_fr),
            getString(R.string.lang_pl),
            getString(R.string.lang_zh),
            getString(R.string.lang_ja)
        )
        
        val currentLangIndex = languages.indexOf(settingsManager.selectedLanguage)
        val currentLangName = langNames[if (currentLangIndex != -1) currentLangIndex else 0]

        val langContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setFocusable(true)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor("#1A1A1A".toColorInt())
            }
            setPadding(30, 20, 30, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
                bottomMargin = 40
            }

            addView(createLabel(getString(R.string.setting_language)).apply { setPadding(0, 0, 0, 0) })
            addView(createDesc(getString(R.string.desc_language, currentLangName)))

            setOnClickListener {
                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.setting_language))
                    .setItems(langNames.toTypedArray()) { _, which ->
                        val newLang = languages[which]
                        if (newLang != settingsManager.selectedLanguage) {
                            settingsManager.selectedLanguage = newLang
                            recreate()
                        }
                    }
                    .show()
            }
        }
        container.addView(langContainer)

        // Back Button
        container.addView(Button(this).apply {
            text = getString(R.string.btn_save)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor("#222222".toColorInt())
                setStroke(1, "#555555".toColorInt())
            }
            setOnClickListener { finish() }
        })

        root.addView(container)
        setContentView(root)
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor("#00B0FF".toColorInt())
            setPadding(0, 20, 0, 0)
        }
    }

    private fun createDesc(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor("#AAAAAA".toColorInt())
            setPadding(0, 4, 0, 0)
        }
    }
}
