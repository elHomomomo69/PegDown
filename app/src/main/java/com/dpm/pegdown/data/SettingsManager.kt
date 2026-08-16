package com.dpm.pegdown.data

import android.content.Context
import android.content.SharedPreferences
import com.dpm.pegdown.model.RecordingMode

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pegdown_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_RESET_DURATION = "reset_duration_sec"
        const val KEY_RECORDING_MODE = "default_recording_mode"
        const val KEY_SAVE_ORIENTATION = "save_orientation"
        const val KEY_LOCKED_ORIENTATION = "locked_orientation"
        const val KEY_AUTO_START_SPEED = "auto_start_speed"
        const val KEY_SMOOTHING_FACTOR = "smoothing_factor"
        const val KEY_INVERT_AXIS = "invert_axis"
        const val KEY_LANGUAGE = "selected_language"
    }

    var selectedLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var isAxisInverted: Boolean
        get() = prefs.getBoolean(KEY_INVERT_AXIS, false)
        set(value) = prefs.edit().putBoolean(KEY_INVERT_AXIS, value).apply()

    var resetDurationSeconds: Int
        get() = prefs.getInt(KEY_RESET_DURATION, 7)
        set(value) = prefs.edit().putInt(KEY_RESET_DURATION, value).apply()

    var defaultRecordingMode: RecordingMode
        get() {
            val modeStr = prefs.getString(KEY_RECORDING_MODE, RecordingMode.MANUAL.name)
            return try { RecordingMode.valueOf(modeStr!!) } catch (e: Exception) { RecordingMode.MANUAL }
        }
        set(value) = prefs.edit().putString(KEY_RECORDING_MODE, value.name).apply()

    var isOrientationSaveEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAVE_ORIENTATION, false)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_ORIENTATION, value).apply()

    var lockedOrientation: Int
        get() = prefs.getInt(KEY_LOCKED_ORIENTATION, -1) // -1 for unspecified
        set(value) = prefs.edit().putInt(KEY_LOCKED_ORIENTATION, value).apply()

    var autoStartSpeedKmH: Float
        get() = prefs.getFloat(KEY_AUTO_START_SPEED, 7.0f)
        set(value) = prefs.edit().putFloat(KEY_AUTO_START_SPEED, value).apply()

    var smoothingFactor: Float
        get() = prefs.getFloat(KEY_SMOOTHING_FACTOR, 0.07f)
        set(value) = prefs.edit().putFloat(KEY_SMOOTHING_FACTOR, value).apply()
}
