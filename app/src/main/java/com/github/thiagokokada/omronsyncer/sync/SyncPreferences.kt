package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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

    fun healthConnectAutoExportEnabled(): Boolean {
        return preferences.getBoolean(PREF_HEALTH_CONNECT_AUTO_EXPORT, true)
    }

    fun setHealthConnectAutoExportEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(PREF_HEALTH_CONNECT_AUTO_EXPORT, enabled)
        }
    }

    fun healthConnectExportUserKey(): String {
        return preferences.getString(
            PREF_HEALTH_CONNECT_EXPORT_USER,
            HEALTH_CONNECT_EXPORT_USER_ALL,
        ) ?: HEALTH_CONNECT_EXPORT_USER_ALL
    }

    fun setHealthConnectExportUserKey(key: String) {
        preferences.edit {
            putString(PREF_HEALTH_CONNECT_EXPORT_USER, key)
        }
    }

    fun backgroundSyncEnabled(): Boolean {
        return preferences.getBoolean(PREF_BACKGROUND_SYNC_ENABLED, false)
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(PREF_BACKGROUND_SYNC_ENABLED, enabled)
        }
    }

    fun backgroundSyncIntervalHours(): Int {
        val storedValue = preferences.getInt(
            PREF_BACKGROUND_SYNC_INTERVAL_HOURS,
            DEFAULT_BACKGROUND_SYNC_INTERVAL_HOURS,
        )
        return if (storedValue in BACKGROUND_SYNC_INTERVAL_OPTIONS_HOURS) {
            storedValue
        } else {
            DEFAULT_BACKGROUND_SYNC_INTERVAL_HOURS
        }
    }

    fun setBackgroundSyncIntervalHours(hours: Int) {
        val normalizedHours = if (hours in BACKGROUND_SYNC_INTERVAL_OPTIONS_HOURS) {
            hours
        } else {
            DEFAULT_BACKGROUND_SYNC_INTERVAL_HOURS
        }
        preferences.edit {
            putInt(PREF_BACKGROUND_SYNC_INTERVAL_HOURS, normalizedHours)
        }
    }

    fun lastBackgroundSyncSummary(): String? {
        return preferences.getString(PREF_LAST_BACKGROUND_SYNC_SUMMARY, null)
    }

    fun setLastBackgroundSyncSummary(summary: String) {
        preferences.edit {
            putString(PREF_LAST_BACKGROUND_SYNC_SUMMARY, summary)
        }
    }

    companion object {
        const val PREFERENCES_NAME = "om_syncer_prefs"
        const val PREF_SELECTED_MODEL_ID = "selected_model_id"
        const val PREF_SELECTED_DEVICE_ADDRESS = "selected_device_address"
        const val PREF_HEALTH_CONNECT_AUTO_EXPORT = "health_connect_auto_export"
        const val PREF_HEALTH_CONNECT_EXPORT_USER = "health_connect_export_user"
        const val PREF_BACKGROUND_SYNC_ENABLED = "background_sync_enabled"
        const val PREF_BACKGROUND_SYNC_INTERVAL_HOURS = "background_sync_interval_hours"
        const val PREF_LAST_BACKGROUND_SYNC_SUMMARY = "last_background_sync_summary"
        const val HEALTH_CONNECT_EXPORT_USER_ALL = "all"
        val BACKGROUND_SYNC_INTERVAL_OPTIONS_HOURS = listOf(1, 3, 6, 12, 24)
        const val DEFAULT_BACKGROUND_SYNC_INTERVAL_HOURS = 12
    }
}
