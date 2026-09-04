package com.dpm.pegdown.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsManagerTest {

    private lateinit var settingsManager: SettingsManager
    private val context = mockk<Context>(relaxed = true)
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    @Before
    fun setup() {
        every { context.getSharedPreferences("pegdown_settings", Context.MODE_PRIVATE) } returns sharedPrefs
        every { sharedPrefs.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        
        settingsManager = SettingsManager(context)
    }

    @Test
    fun `lockedOrientation stores value correctly`() {
        settingsManager.lockedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        verify { editor.putInt(SettingsManager.KEY_LOCKED_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) }
    }

    @Test
    fun `isOrientationSaveEnabled stores boolean correctly`() {
        settingsManager.isOrientationSaveEnabled = true
        
        verify { editor.putBoolean(SettingsManager.KEY_SAVE_ORIENTATION, true) }
    }

    @Test
    fun `resetDurationSeconds reads default value if not set`() {
        every { sharedPrefs.getInt(SettingsManager.KEY_RESET_DURATION, 7) } returns 7
        
        assertEquals(7, settingsManager.resetDurationSeconds)
    }
}
