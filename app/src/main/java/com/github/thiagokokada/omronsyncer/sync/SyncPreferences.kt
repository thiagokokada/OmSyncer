package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.thiagokokada.omronsyncer.TrendRange
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceRegistry

class SyncPreferences(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun selectedModel(): OmronDeviceDefinition {
        return OmronDeviceRegistry.findById(
            preferences.getString(PREF_SELECTED_MODEL_ID, OmronDeviceRegistry.defaultModel().id),
        )
    }

    fun setSelectedModelId(modelId: String) {
        preferences.edit {
            putString(PREF_SELECTED_MODEL_ID, modelId)
        }
    }

    fun selectedDeviceAddress(): String? {
        return preferences.getString(PREF_SELECTED_DEVICE_ADDRESS, null)
    }

    fun setSelectedDeviceAddress(address: String) {
        preferences.edit {
            putString(PREF_SELECTED_DEVICE_ADDRESS, address)
        }
    }

    fun initialBluetoothPermissionPromptShown(): Boolean {
        return preferences.getBoolean(PREF_INITIAL_BLUETOOTH_PERMISSION_PROMPT_SHOWN, false)
    }

    fun setInitialBluetoothPermissionPromptShown(shown: Boolean) {
        preferences.edit {
            putBoolean(PREF_INITIAL_BLUETOOTH_PERMISSION_PROMPT_SHOWN, shown)
        }
    }

    fun healthConnectAutoExportEnabled(): Boolean {
        return preferences.getBoolean(PREF_HEALTH_CONNECT_AUTO_EXPORT, true)
    }

    fun setHealthConnectAutoExportEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(PREF_HEALTH_CONNECT_AUTO_EXPORT, enabled)
        }
    }

    fun nearbySyncEnabled(): Boolean {
        return preferences.getBoolean(PREF_NEARBY_SYNC_ENABLED, false)
    }

    fun setNearbySyncEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(PREF_NEARBY_SYNC_ENABLED, enabled)
        }
    }

    fun nearbySyncCooldownMinutes(): Int {
        return preferences.getInt(PREF_NEARBY_SYNC_COOLDOWN_MINUTES, DEFAULT_NEARBY_SYNC_COOLDOWN_MINUTES)
    }

    fun setNearbySyncCooldownMinutes(minutes: Int) {
        preferences.edit {
            putInt(PREF_NEARBY_SYNC_COOLDOWN_MINUTES, minutes)
        }
    }

    fun selectedMeasurementUser(): Int? {
        val value = preferences.getInt(PREF_SELECTED_MEASUREMENT_USER, MEASUREMENT_USER_ALL)
        return if (value == MEASUREMENT_USER_ALL) null else value
    }

    fun setSelectedMeasurementUser(user: Int?) {
        preferences.edit {
            putInt(PREF_SELECTED_MEASUREMENT_USER, user ?: MEASUREMENT_USER_ALL)
        }
    }

    fun selectedTrendRange(): TrendRange {
        val storedValue = preferences.getString(PREF_SELECTED_TREND_RANGE, TrendRange.THIRTY_DAYS.name)
        return TrendRange.entries.firstOrNull { it.name == storedValue } ?: TrendRange.THIRTY_DAYS
    }

    fun setSelectedTrendRange(range: TrendRange) {
        preferences.edit {
            putString(PREF_SELECTED_TREND_RANGE, range.name)
        }
    }

    fun nearbySyncCooldownMillis(): Long {
        return nearbySyncCooldownMinutes() * 60_000L
    }

    fun lastNearbySyncSummary(): String? {
        return preferences.getString(PREF_LAST_NEARBY_SYNC_SUMMARY, null)
    }

    fun persistLastNearbySyncStatus(timestampMillis: Long, summary: String) {
        preferences.edit(commit = true) {
            putLong(PREF_LAST_NEARBY_SYNC_AT_MILLIS, timestampMillis)
            putString(PREF_LAST_NEARBY_SYNC_SUMMARY, summary)
        }
    }

    fun lastNearbySyncAtMillis(): Long? {
        val value = preferences.getLong(PREF_LAST_NEARBY_SYNC_AT_MILLIS, -1L)
        return if (value > 0L) value else null
    }

    fun lastNearbySyncTriggerAtMillis(): Long? {
        val value = preferences.getLong(PREF_LAST_NEARBY_SYNC_TRIGGER_AT_MILLIS, -1L)
        return if (value > 0L) value else null
    }

    fun setLastNearbySyncTriggerAtMillis(timestampMillis: Long) {
        preferences.edit(commit = true) {
            putLong(PREF_LAST_NEARBY_SYNC_TRIGGER_AT_MILLIS, timestampMillis)
        }
    }

    fun clearLastNearbySyncTriggerAtMillis() {
        preferences.edit(commit = true) {
            remove(PREF_LAST_NEARBY_SYNC_TRIGGER_AT_MILLIS)
        }
    }

    companion object {
        const val PREFERENCES_NAME = "om_syncer_prefs"
        const val PREF_SELECTED_MODEL_ID = "selected_model_id"
        const val PREF_SELECTED_DEVICE_ADDRESS = "selected_device_address"
        const val PREF_INITIAL_BLUETOOTH_PERMISSION_PROMPT_SHOWN =
            "initial_bluetooth_permission_prompt_shown"
        const val PREF_HEALTH_CONNECT_AUTO_EXPORT = "health_connect_auto_export"
        const val PREF_NEARBY_SYNC_ENABLED = "nearby_sync_enabled"
        const val PREF_NEARBY_SYNC_COOLDOWN_MINUTES = "nearby_sync_cooldown_minutes"
        const val PREF_SELECTED_MEASUREMENT_USER = "selected_measurement_user"
        const val PREF_SELECTED_TREND_RANGE = "selected_trend_range"
        const val PREF_LAST_NEARBY_SYNC_SUMMARY = "last_nearby_sync_summary"
        const val PREF_LAST_NEARBY_SYNC_AT_MILLIS = "last_nearby_sync_at_millis"
        const val PREF_LAST_NEARBY_SYNC_TRIGGER_AT_MILLIS = "last_nearby_sync_trigger_at_millis"
        const val DEFAULT_NEARBY_SYNC_COOLDOWN_MINUTES = 5
        const val MEASUREMENT_USER_ALL = -1
    }
}
