package com.github.thiagokokada.omronsyncer

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSpinnerText
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val bluetoothPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun launch_showsResultsScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.sync_button)).check(matches(isDisplayed()))
            onView(allOf(withId(R.id.screen_title), withText(R.string.results_title)))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun settingsScreen_showsHealthConnectControls() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_settings)).perform(click())

            onView(withId(R.id.health_connect_action_button)).perform(scrollTo())
            onView(withText(R.string.settings_health_connect_title)).perform(scrollTo())
            onView(withText(R.string.settings_health_connect_title)).check(matches(isDisplayed()))
            onView(withId(R.id.health_connect_action_button)).check(matches(isDisplayed()))
            onView(withId(R.id.health_connect_export_button)).perform(scrollTo())
            onView(withId(R.id.health_connect_export_button))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            onView(withId(R.id.health_connect_auto_export_switch)).perform(scrollTo())
            onView(withId(R.id.health_connect_auto_export_switch)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun healthConnectAutoExportSwitch_persistsAcrossActivityRestart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_settings)).perform(click())
            onView(withId(R.id.health_connect_auto_export_switch)).perform(scrollTo())
            onView(withId(R.id.health_connect_auto_export_switch)).check(matches(isChecked()))
            onView(withId(R.id.health_connect_auto_export_switch)).perform(click())
            onView(withId(R.id.health_connect_auto_export_switch)).check(matches(isNotChecked()))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_settings)).perform(click())
            onView(withId(R.id.health_connect_auto_export_switch)).perform(scrollTo())
            onView(withId(R.id.health_connect_auto_export_switch)).check(matches(isNotChecked()))
        }
    }

    @Test
    fun syncLogButton_opensLogScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_settings)).perform(click())
            onView(withId(R.id.sync_log_button)).perform(scrollTo())
            onView(withId(R.id.sync_log_button)).perform(click())

            onView(withId(R.id.export_log_button)).check(matches(isDisplayed()))
            onView(withText(R.string.sync_log_title)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun backgroundSyncSwitch_persistsAcrossActivityRestart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_settings)).perform(click())
            onView(withId(R.id.background_sync_switch)).perform(scrollTo())
            onView(withId(R.id.background_sync_switch)).check(matches(isNotChecked()))
            onView(withId(R.id.background_sync_switch)).perform(click())
            onView(withId(R.id.background_sync_switch)).check(matches(isChecked()))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_settings)).perform(click())
            onView(withId(R.id.background_sync_switch)).perform(scrollTo())
            onView(withId(R.id.background_sync_switch)).check(matches(isChecked()))
        }
    }

    @Test
    fun backgroundSyncInterval_persistsAcrossActivityRestart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_settings)).perform(click())
            onView(withId(R.id.background_sync_interval_spinner)).perform(scrollTo(), click())
            onView(withText("Every 6 hours")).perform(click())
            onView(withId(R.id.background_sync_interval_spinner))
                .check(matches(withSpinnerText("Every 6 hours")))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_settings)).perform(click())
            onView(withId(R.id.background_sync_interval_spinner)).perform(scrollTo())
            onView(withId(R.id.background_sync_interval_spinner))
                .check(matches(withSpinnerText("Every 6 hours")))
        }
    }

    private fun clearPreferences() {
        context.getSharedPreferences("om_syncer_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
