package com.groupe.smapassmobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.groupe.smapassmobile.ui.stat.DeviceSpec
import com.groupe.smapassmobile.ui.stat.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "device_store")

class DeviceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    val imei: Flow<String?> = appContext.dataStore.data.map { it[KEY_IMEI] }

    val spec: Flow<DeviceSpec?> = appContext.dataStore.data.map { prefs ->
        prefs[KEY_SPEC]?.let { decode<DeviceSpec>(it) }
    }

    val status: Flow<DeviceStatus?> = appContext.dataStore.data.map { prefs ->
        prefs[KEY_STATUS]?.let { decode<DeviceStatus>(it) }
    }

    suspend fun imeiOnce(): String? = appContext.dataStore.data.first()[KEY_IMEI]

    suspend fun saveImei(value: String) {
        appContext.dataStore.edit { it[KEY_IMEI] = value }
    }

    suspend fun saveSpec(value: DeviceSpec) {
        appContext.dataStore.edit { it[KEY_SPEC] = json.encodeToString(value) }
    }

    suspend fun saveStatus(value: DeviceStatus) {
        appContext.dataStore.edit { it[KEY_STATUS] = json.encodeToString(value) }
    }

    private inline fun <reified T> decode(raw: String): T? =
        runCatching { json.decodeFromString<T>(raw) }.getOrNull()

    private companion object {
        val KEY_IMEI = stringPreferencesKey("imei")
        val KEY_SPEC = stringPreferencesKey("device_spec")
        val KEY_STATUS = stringPreferencesKey("device_status")
    }
}
