package com.github.thiagokokada.omronsyncer

import android.Manifest
import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.PerformException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSpinnerText
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.util.TreeIterables
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.recyclerview.widget.RecyclerView
import com.github.thiagokokada.omronsyncer.data.MeasurementStore
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.sync.SyncPreferences
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val bluetoothPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        grantRuntimePermissions()
        clearPreferences()
        clearMeasurements()
        suppressInitialPermissionPrompt()
    }

    @After
    fun tearDown() {
        clearPreferences()
        clearMeasurements()
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
    fun resultsRangeSelection_filtersMeasurements() {
        val now = LocalDateTime.now().withNano(0)
        seedMeasurements(
            listOf(
                measurementAt(user = 1, recordedAt = now.minusDays(3)),
                measurementAt(user = 1, recordedAt = now.minusDays(15)),
                measurementAt(user = 1, recordedAt = now.minusDays(40)),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForMeasurementCount("3 measurements")

            onView(withId(R.id.results_range_thirty_days)).perform(click())
            waitForMeasurementCount("2 measurements")

            onView(withId(R.id.results_range_seven_days)).perform(click())
            waitForMeasurementCount("1 measurement")

            onView(withId(R.id.results_range_all)).perform(click())
            waitForMeasurementCount("3 measurements")
        }
    }

    @Test
    fun rangeSelection_isSharedBetweenResultsAndTrends() {
        val now = LocalDateTime.now().withNano(0)
        seedMeasurements(
            listOf(
                measurementAt(user = 1, recordedAt = now.minusDays(3)),
                measurementAt(user = 1, recordedAt = now.minusDays(15)),
                measurementAt(user = 1, recordedAt = now.minusDays(40)),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.results_range_seven_days)).perform(click())
            waitForMeasurementCount("1 measurement")

            openTrendsScreen(scenario)
            onView(withId(R.id.range_seven_days)).check(matches(isChecked()))

            onView(withId(R.id.range_thirty_days)).perform(click())
            onView(withId(R.id.range_thirty_days)).check(matches(isChecked()))

            openResultsScreen(scenario)
            onView(withId(R.id.results_range_thirty_days)).check(matches(isChecked()))
            waitForMeasurementCount("2 measurements")
        }
    }

    @Test
    fun resultsDeleteHint_isShownOnlyOnce() {
        seedMeasurements(listOf(measurement(user = 1, day = 8)))

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(isRoot()).perform(
                waitForMatchingView(withText(R.string.results_delete_hint), 5_000),
            )
            onView(withText(R.string.results_delete_hint)).check(matches(isDisplayed()))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(R.string.results_delete_hint)).check(doesNotExist())
        }
    }

    @Test
    fun settingsScreen_showsHealthConnectControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)

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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.health_connect_auto_export_switch)).perform(scrollTo())
            onView(withId(R.id.health_connect_auto_export_switch)).check(matches(isChecked()))
            onView(withId(R.id.health_connect_auto_export_switch)).perform(click())
            onView(withId(R.id.health_connect_auto_export_switch)).check(matches(isNotChecked()))
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.health_connect_auto_export_switch)).perform(scrollTo())
            onView(withId(R.id.health_connect_auto_export_switch)).check(matches(isNotChecked()))
        }
    }

    @Test
    fun syncLogButton_opensLogScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.sync_log_button)).perform(scrollTo())
            onView(withId(R.id.sync_log_button)).perform(click())

            onView(withId(R.id.export_log_button)).check(matches(isDisplayed()))
            onView(withText(R.string.sync_log_title)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun pdfReportButton_opensPreviewScreen() {
        seedMeasurements(listOf(measurement(user = 1, day = 8)))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.pdf_report_button)).perform(scrollTo(), click())

            onView(withId(R.id.export_pdf_button)).check(matches(isDisplayed()))
            onView(withText(R.string.pdf_report_title)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun pdfReportRangeSelection_isSharedWithResults() {
        val now = LocalDateTime.now().withNano(0)
        seedMeasurements(
            listOf(
                measurementAt(user = 1, recordedAt = now.minusDays(3)),
                measurementAt(user = 1, recordedAt = now.minusDays(15)),
                measurementAt(user = 1, recordedAt = now.minusDays(40)),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.pdf_report_button)).perform(scrollTo(), click())

            onView(withId(R.id.pdf_measurement_count)).check(matches(withText("3 measurements")))
            onView(withId(R.id.pdf_range_seven_days)).perform(click())
            onView(withId(R.id.pdf_range_seven_days)).check(matches(isChecked()))
            onView(withId(R.id.pdf_measurement_count)).check(matches(withText("1 measurement")))

            pressBack()

            openResultsScreen(scenario)
            onView(withId(R.id.results_range_seven_days)).check(matches(isChecked()))
            waitForMeasurementCount("1 measurement")
        }
    }

    @Test
    fun backFromSyncLog_returnsToSettingsScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.sync_log_button)).perform(scrollTo(), click())

            onView(withId(R.id.export_log_button)).check(matches(isDisplayed()))
            pressBack()

            onView(allOf(withId(R.id.screen_title), withText(R.string.settings_title)))
                .check(matches(isDisplayed()))
            onView(withId(R.id.status_value)).check(matches(isDisplayed()))
            onView(withId(R.id.bottom_navigation)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun nearbySyncSwitch_persistsAcrossActivityRestart() {
        setSelectedDeviceAddress("00:11:22:33:44:55")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.nearby_sync_switch)).perform(scrollTo())
            onView(withId(R.id.nearby_sync_switch)).check(matches(isNotChecked()))
            onView(withId(R.id.nearby_sync_switch)).perform(click())
            onView(withId(R.id.nearby_sync_switch)).check(matches(isChecked()))
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.nearby_sync_switch)).perform(scrollTo())
            onView(withId(R.id.nearby_sync_switch)).check(matches(isChecked()))
        }
    }

    @Test
    fun trendsScreen_doesNotPreselectReadingUntilChartIsTapped() {
        seedMeasurements(
            listOf(
                measurement(user = 1, day = 7),
                measurement(user = 1, day = 8),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTrendsScreen(scenario)
            onView(isRoot()).perform(
                waitForMatchingView(
                    allOf(withId(R.id.chart_card), isDisplayed()),
                    5_000,
                ),
            )

            onView(withId(R.id.selected_reading_card))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(withId(R.id.chart_view)).perform(scrollTo(), click())

            onView(withId(R.id.selected_reading_card))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        }
    }

    @Test
    fun trendsRangeSelection_persistsAcrossActivityRestart() {
        seedMeasurements(
            listOf(
                measurement(user = 1, day = 7),
                measurement(user = 1, day = 8),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTrendsScreen(scenario)
            onView(withId(R.id.range_all)).perform(click())
            onView(withId(R.id.range_all)).check(matches(isChecked()))
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTrendsScreen(scenario)
            onView(withId(R.id.range_all)).check(matches(isChecked()))
        }
    }

    @Test
    fun measurementUserSelection_isSharedBetweenResultsAndTrendsAndPersists() {
        seedMeasurements(
            listOf(
                measurement(user = 1, day = 7),
                measurement(user = 2, day = 8),
            ),
        )
        val userTwoLabel = context.getString(R.string.measurement_user_single, 2)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.measurement_count)).check(matches(withText("2 measurements")))

            openSettingsScreen(scenario)
            onView(withId(R.id.measurement_user_spinner)).perform(scrollTo(), click())
            onData(`is`(userTwoLabel)).perform(click())
            onView(withId(R.id.measurement_user_spinner)).check(matches(withSpinnerText(userTwoLabel)))

            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))

            openSettingsScreen(scenario)
            onView(withId(R.id.measurement_user_spinner)).perform(scrollTo(), click())
            onData(`is`(allUsersLabel())).perform(click())
            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("2 measurements")))

            openSettingsScreen(scenario)
            onView(withId(R.id.measurement_user_spinner)).perform(scrollTo(), click())
            onData(`is`(userTwoLabel)).perform(click())
            openTrendsScreen(scenario)
            onView(withId(R.id.chart_card)).check(matches(isDisplayed()))
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            onView(withId(R.id.measurement_user_spinner)).perform(scrollTo())
            onView(withId(R.id.measurement_user_spinner)).check(matches(withSpinnerText(userTwoLabel)))
        }
    }

    @Test
    fun seedSampleMeasurements_populatesResultsInDebugBuild() {
        assumeTrue(BuildConfig.DEBUG)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.measurement_count)).check(matches(withText("0 measurements")))

            openSettingsScreen(scenario)
            onView(withId(R.id.seed_measurements_button)).perform(scrollTo(), click())

            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("100 measurements")))

            openSettingsScreen(scenario)
            onView(withId(R.id.seed_measurements_button)).perform(scrollTo())
            onView(withId(R.id.seed_measurements_button)).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun deleteAndRestoreMeasurement_updatesResultsAndSettings() {
        seedMeasurements(listOf(measurement(user = 1, day = 8)))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))
            onView(withId(R.id.measurements_list)).perform(swipeRecyclerItemLeftAtPosition(0))
            onView(withText(R.string.delete_measurement_confirm)).perform(click())
            onView(withId(R.id.measurement_count)).check(matches(withText("0 measurements")))

            openSettingsScreen(scenario)
            onView(withId(R.id.restore_measurements_button)).perform(scrollTo(), click())
            onView(withId(R.id.restore_measurement_button)).perform(click())
            pressBack()

            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))
        }
    }

    @Test
    fun deleteAndRestoreTruReadMeasurement_inMergeModeTreatsSessionAsSingleItem() {
        seedMeasurements(truReadTriplet())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            selectTruReadDisplayMode(mergeTruReadLabel())

            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))
            onView(withId(R.id.measurements_list)).perform(swipeRecyclerItemLeftAtPosition(0))
            onView(withText(R.string.delete_measurement_confirm)).perform(click())
            onView(withId(R.id.measurement_count)).check(matches(withText("0 measurements")))

            openSettingsScreen(scenario)
            onView(withId(R.id.restore_measurements_button)).perform(scrollTo(), click())
            assertRecyclerItemCount(R.id.deleted_measurements_list, 1)
            onView(withId(R.id.restore_measurement_button)).perform(click())
            pressBack()

            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))
        }
    }

    @Test
    fun switchingToSeparateModeAfterMergedDeleteShowsIndividualDeletedMeasurements() {
        seedMeasurements(truReadTriplet())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            selectTruReadDisplayMode(mergeTruReadLabel())

            openResultsScreen(scenario)
            onView(withId(R.id.measurements_list)).perform(swipeRecyclerItemLeftAtPosition(0))
            onView(withText(R.string.delete_measurement_confirm)).perform(click())

            openSettingsScreen(scenario)
            selectTruReadDisplayMode(separateTruReadLabel())
            onView(withId(R.id.restore_measurements_button)).perform(scrollTo(), click())

            assertRecyclerItemCount(R.id.deleted_measurements_list, 3)
            onView(withId(R.id.deleted_measurements_list))
                .perform(clickRecyclerChildAtPosition(0, R.id.restore_measurement_button))
            pressBack()

            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))

            openSettingsScreen(scenario)
            selectTruReadDisplayMode(mergeTruReadLabel())
            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))
        }
    }

    @Test
    fun switchingToMergeModeAfterSeparateDeleteDoesNotMergeIncompleteTruReadSession() {
        seedMeasurements(truReadTriplet())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettingsScreen(scenario)
            selectTruReadDisplayMode(separateTruReadLabel())

            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("3 measurements")))
            onView(withId(R.id.measurements_list)).perform(swipeRecyclerItemLeftAtPosition(0))
            onView(withText(R.string.delete_measurement_confirm)).perform(click())
            onView(withId(R.id.measurement_count)).check(matches(withText("2 measurements")))

            openSettingsScreen(scenario)
            selectTruReadDisplayMode(mergeTruReadLabel())
            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("2 measurements")))

            openSettingsScreen(scenario)
            onView(withId(R.id.restore_measurements_button)).perform(scrollTo(), click())
            assertRecyclerItemCount(R.id.deleted_measurements_list, 1)
            onView(withId(R.id.restore_measurement_button)).perform(click())
            pressBack()

            openResultsScreen(scenario)
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))
        }
    }

    private fun openResultsScreen(scenario: ActivityScenario<MainActivity>) {
        selectBottomNavItem(scenario, R.id.navigation_results)
        onView(isRoot()).perform(waitForMatchingView(withId(R.id.measurement_count), 5_000))
    }

    private fun openSettingsScreen(scenario: ActivityScenario<MainActivity>) {
        selectBottomNavItem(scenario, R.id.navigation_settings)
        onView(isRoot()).perform(waitForMatchingView(withId(R.id.status_value), 5_000))
    }

    private fun openTrendsScreen(scenario: ActivityScenario<MainActivity>) {
        selectBottomNavItem(scenario, R.id.navigation_trends)
        onView(isRoot()).perform(waitForMatchingView(withId(R.id.range_toggle), 5_000))
    }

    private fun selectBottomNavItem(
        scenario: ActivityScenario<MainActivity>,
        itemId: Int,
    ) {
        scenario.onActivity { activity ->
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = itemId
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun swipeRightAlsoOpensDeleteFlow() {
        seedMeasurements(listOf(measurement(user = 1, day = 8)))

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.measurement_count)).check(matches(withText("1 measurement")))
            onView(withId(R.id.measurements_list)).perform(swipeRecyclerItemRightAtPosition(0))
            onView(withText(R.string.delete_measurement_title)).check(matches(isDisplayed()))
            onView(withText(R.string.delete_measurement_confirm)).perform(click())
            onView(withId(R.id.measurement_count)).check(matches(withText("0 measurements")))
        }
    }

    private fun clearPreferences() {
        context.getSharedPreferences("om_syncer_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun clearMeasurements() {
        context.deleteDatabase("measurements.db")
    }

    private fun seedMeasurements(measurements: List<Measurement>) {
        MeasurementStore(context).saveAll(measurements)
    }

    private fun assertRecyclerItemCount(recyclerViewId: Int, expectedCount: Int) {
        onView(withId(recyclerViewId)).check { view, noViewFoundException ->
            if (noViewFoundException != null) throw noViewFoundException
            val recyclerView = view as RecyclerView
            assertEquals(expectedCount, recyclerView.adapter?.itemCount)
        }
    }

    private fun waitForMeasurementCount(text: String) {
        onView(isRoot()).perform(waitForMatchingView(withText(text), 5_000))
        onView(withId(R.id.measurement_count)).check(matches(withText(text)))
    }

    private fun grantRuntimePermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        val packageName = instrumentation.targetContext.packageName

        uiAutomation.adoptShellPermissionIdentity()
        try {
            TEST_PERMISSIONS.forEach { permission ->
                uiAutomation.grantRuntimePermission(packageName, permission)
            }
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }

        instrumentation.waitForIdleSync()
    }

    private fun suppressInitialPermissionPrompt() {
        SyncPreferences(context).setInitialBluetoothPermissionPromptShown(true)
    }

    private fun setSelectedDeviceAddress(address: String) {
        SyncPreferences(context).setSelectedDeviceAddress(address)
    }

    private fun selectTruReadDisplayMode(label: String) {
        onView(withId(R.id.tru_read_display_mode_spinner)).perform(scrollTo(), click())
        onData(`is`(label)).perform(click())
        onView(withId(R.id.tru_read_display_mode_spinner)).check(matches(withSpinnerText(label)))
    }

    private fun measurement(
        user: Int,
        day: Int,
        hour: Int = 9,
        minute: Int = 30,
        truReadStage: Int? = null,
    ): Measurement {
        return Measurement(
            user = user,
            recordedAt = LocalDateTime.of(2026, 3, day, hour, minute),
            systolic = 120 + user,
            diastolic = 80 + user,
            pulse = 64 + user,
            irregularHeartbeat = false,
            movement = false,
            truReadStage = truReadStage,
        )
    }

    private fun measurementAt(user: Int, recordedAt: LocalDateTime): Measurement {
        return Measurement(
            user = user,
            recordedAt = recordedAt,
            systolic = 120 + user,
            diastolic = 80 + user,
            pulse = 64 + user,
            irregularHeartbeat = false,
            movement = false,
        )
    }

    private fun truReadTriplet(): List<Measurement> {
        return listOf(
            measurement(user = 1, day = 10, hour = 12, minute = 37, truReadStage = 1),
            measurement(user = 1, day = 10, hour = 12, minute = 38, truReadStage = 2),
            measurement(user = 1, day = 10, hour = 12, minute = 39, truReadStage = 3),
        )
    }

    private fun allUsersLabel(): String {
        return context.getString(R.string.measurement_user_all)
    }

    private fun separateTruReadLabel(): String {
        return context.getString(R.string.tru_read_display_mode_separate)
    }

    private fun mergeTruReadLabel(): String {
        return context.getString(R.string.tru_read_display_mode_merge)
    }

    private fun swipeRecyclerItemLeftAtPosition(position: Int): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> = withId(R.id.measurements_list)

            override fun getDescription(): String {
                return "swipe left on recycler item at position $position"
            }

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                    ?: error("No ViewHolder at position $position")
                swipeLeft().perform(uiController, viewHolder.itemView)
            }
        }
    }

    private fun swipeRecyclerItemRightAtPosition(position: Int): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> = withId(R.id.measurements_list)

            override fun getDescription(): String {
                return "swipe right on recycler item at position $position"
            }

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                    ?: error("No ViewHolder at position $position")
                swipeRight().perform(uiController, viewHolder.itemView)
            }
        }
    }

    private fun clickRecyclerChildAtPosition(position: Int, childViewId: Int): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> = allOf(isDisplayed(), withId(R.id.deleted_measurements_list))

            override fun getDescription(): String {
                return "click child view $childViewId at recycler position $position"
            }

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                    ?: error("No ViewHolder at position $position")
                val child = viewHolder.itemView.findViewById<View>(childViewId)
                    ?: error("No child view found with id $childViewId")
                click().perform(uiController, child)
            }
        }
    }

    private fun waitForMatchingView(
        viewMatcher: Matcher<View>,
        timeoutMillis: Long,
    ): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()

            override fun getDescription(): String {
                return "wait up to $timeoutMillis milliseconds for a matching view"
            }

            override fun perform(uiController: UiController, view: View) {
                val endTime = System.currentTimeMillis() + timeoutMillis
                do {
                    TreeIterables.breadthFirstViewTraversal(view).forEach { child ->
                        if (viewMatcher.matches(child)) {
                            return
                        }
                    }
                    uiController.loopMainThreadForAtLeast(50)
                } while (System.currentTimeMillis() < endTime)

                throw PerformException.Builder()
                    .withActionDescription(description)
                    .withViewDescription(view.toString())
                    .build()
            }
        }
    }

    companion object {
        private val TEST_PERMISSIONS =
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS,
            )
    }
}
