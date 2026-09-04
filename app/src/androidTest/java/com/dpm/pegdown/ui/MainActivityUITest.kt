package com.dpm.pegdown.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.dpm.pegdown.R
import org.hamcrest.Matchers.containsString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Context

@RunWith(AndroidJUnit4::class)
class MainActivityUITest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    @Before
    fun setup() {
        // Clear settings BEFORE activity starts
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pegdown_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun calibration_click_updates_status_text() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Wait for activity to be fully resumed
            Thread.sleep(1500)
            
            // Tap the gauge view
            onView(withId(R.id.gauge_view)).perform(click())

            // Check if status text now contains parentheses which indicate a value
            onView(withId(R.id.tv_status)).check(matches(withText(containsString("("))))
        }
    }

    @Test
    fun lock_button_toggles_text() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Wait for activity to be fully resumed
            Thread.sleep(1500)

            // Check initial state or toggle and check
            onView(withId(R.id.btn_lock)).perform(click())
            
            // After clicking, check if it changed to the "LOCKED" state.
            onView(withId(R.id.btn_lock)).check(matches(withText(containsString("LOCKED"))))
        }
    }
}
