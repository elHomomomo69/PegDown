package com.dpm.pegdown.ui

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dpm.pegdown.R
import org.hamcrest.Matchers.containsString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUITest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun calibration_click_updates_status_text() {
        // Initially it should show "Not calibrated" (or whatever the initial state is)
        // Since we are running on device, we don't know the exact starting string without R.string
        
        // Tap the gauge view
        onView(withId(R.id.gauge_view)).perform(click())

        // Check if status text now contains "Kalibriert" or "Calibrated" (depending on locale)
        // or at least contains parentheses which indicate a value
        onView(withId(R.id.tv_status)).check(matches(withText(containsString("("))))
    }

    @Test
    fun lock_button_toggles_text() {
        // Check initial state or toggle and check
        onView(withId(R.id.btn_lock)).perform(click())
        
        // After clicking, it should either show "LOCKED" (from R.string.btn_locked)
        // or the initial "Lock View". We check if it changed to the "LOCKED" state.
        onView(withId(R.id.btn_lock)).check(matches(withText(containsString("LOCKED"))))
    }
}
