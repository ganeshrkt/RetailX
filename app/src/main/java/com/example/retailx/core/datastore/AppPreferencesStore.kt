package com.example.retailx.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.retailx.core.model.RuntimeMode
import com.example.retailx.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.retailXDataStore by preferencesDataStore(name = "retailx_prefs")

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val runtimeMode: RuntimeMode = RuntimeMode.STANDALONE,
    val lanHostPort: Int = 8443,
    val lanServerBaseUrl: String = "",
    val lanDeviceId: String = "",
    val lanDeviceToken: String = "",
    val lanClientDeviceName: String = "RetailX Tablet",
    val sessionTimeoutMinutes: Int = 30,
    val autoBackupEnabled: Boolean = false,
    val backupRetentionCount: Int = 5
)

class AppPreferencesStore(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val RUNTIME_MODE = stringPreferencesKey("runtime_mode")
        val LAN_HOST_PORT = intPreferencesKey("lan_host_port")
        val LAN_SERVER_BASE_URL = stringPreferencesKey("lan_server_base_url")
        val LAN_DEVICE_ID = stringPreferencesKey("lan_device_id")
        val LAN_DEVICE_TOKEN = stringPreferencesKey("lan_device_token")
        val LAN_CLIENT_DEVICE_NAME = stringPreferencesKey("lan_client_device_name")
        val SESSION_TIMEOUT_MIN = intPreferencesKey("session_timeout_minutes")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val BACKUP_RETENTION_COUNT = intPreferencesKey("backup_retention_count")
    }

    val preferences: Flow<AppPreferences> = context.retailXDataStore.data.map { prefs ->
        AppPreferences(
            themeMode = prefs[Keys.THEME_MODE]?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: ThemeMode.SYSTEM,
            runtimeMode = prefs[Keys.RUNTIME_MODE]?.let { name ->
                RuntimeMode.entries.firstOrNull { it.name == name }
            } ?: RuntimeMode.STANDALONE,
            lanHostPort = prefs[Keys.LAN_HOST_PORT] ?: 8443,
            lanServerBaseUrl = prefs[Keys.LAN_SERVER_BASE_URL] ?: "",
            lanDeviceId = prefs[Keys.LAN_DEVICE_ID] ?: "",
            lanDeviceToken = prefs[Keys.LAN_DEVICE_TOKEN] ?: "",
            lanClientDeviceName = prefs[Keys.LAN_CLIENT_DEVICE_NAME] ?: "RetailX Tablet",
            sessionTimeoutMinutes = prefs[Keys.SESSION_TIMEOUT_MIN] ?: 30,
            autoBackupEnabled = prefs[Keys.AUTO_BACKUP_ENABLED] ?: false,
            backupRetentionCount = prefs[Keys.BACKUP_RETENTION_COUNT] ?: 5
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.retailXDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setRuntimeMode(mode: RuntimeMode) {
        context.retailXDataStore.edit { it[Keys.RUNTIME_MODE] = mode.name }
    }

    suspend fun setLanHostPort(port: Int) {
        context.retailXDataStore.edit { it[Keys.LAN_HOST_PORT] = port }
    }

    suspend fun setLanClientConfig(baseUrl: String, deviceId: String, deviceToken: String, deviceName: String) {
        context.retailXDataStore.edit {
            it[Keys.LAN_SERVER_BASE_URL] = baseUrl
            it[Keys.LAN_DEVICE_ID] = deviceId
            it[Keys.LAN_DEVICE_TOKEN] = deviceToken
            it[Keys.LAN_CLIENT_DEVICE_NAME] = deviceName
            it[Keys.RUNTIME_MODE] = RuntimeMode.LAN_CLIENT.name
        }
    }

    suspend fun clearLanClientPairing() {
        context.retailXDataStore.edit {
            it[Keys.LAN_SERVER_BASE_URL] = ""
            it[Keys.LAN_DEVICE_ID] = ""
            it[Keys.LAN_DEVICE_TOKEN] = ""
            it[Keys.RUNTIME_MODE] = RuntimeMode.STANDALONE.name
        }
    }

    suspend fun setBackupSchedule(enabled: Boolean, retentionCount: Int) {
        context.retailXDataStore.edit {
            it[Keys.AUTO_BACKUP_ENABLED] = enabled
            it[Keys.BACKUP_RETENTION_COUNT] = retentionCount.coerceIn(1, 30)
        }
    }

    suspend fun setSessionTimeoutMinutes(minutes: Int) {
        context.retailXDataStore.edit { it[Keys.SESSION_TIMEOUT_MIN] = minutes.coerceIn(5, 480) }
    }
}
