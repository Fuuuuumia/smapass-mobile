package com.groupe.smapassmobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.groupe.smapassmobile.data.remote.TokenPair
import com.groupe.smapassmobile.ui.stat.DeviceSpec
import com.groupe.smapassmobile.ui.stat.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "device_store")

/** 応答待ちの計測ログ。通信結果が不明なときは同じ正本・同じ署名をそのまま再送する。 */
data class PendingLog(
    val sequence: Long,
    val canonicalPayload: String,
    val signatureDer: String,
)

class DeviceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    // --- 端末情報（ローカル表示用） --------------------------------------------

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

    // --- サーバー登録・認証情報 ------------------------------------------------

    /** サーバーが割り当てた端末 UUID。公開 URL・ログ送信・登録終了に使う。 */
    val deviceId: Flow<String?> = appContext.dataStore.data.map { it[KEY_DEVICE_ID] }

    /** 登録済みか（端末 UUID とリフレッシュトークンを保持しているか）。 */
    val isRegistered: Flow<Boolean> = appContext.dataStore.data.map {
        !it[KEY_DEVICE_ID].isNullOrBlank() && !it[KEY_REFRESH_TOKEN].isNullOrBlank()
    }

    suspend fun deviceIdOnce(): String? = appContext.dataStore.data.first()[KEY_DEVICE_ID]

    suspend fun accessTokenOnce(): String? = appContext.dataStore.data.first()[KEY_ACCESS_TOKEN]

    suspend fun accessExpiresAtOnce(): Long? = appContext.dataStore.data.first()[KEY_ACCESS_EXPIRES_AT]

    suspend fun refreshTokenOnce(): String? = appContext.dataStore.data.first()[KEY_REFRESH_TOKEN]

    /** 登録・更新応答のトークン一式を保存する（両方を必ず置き換える）。 */
    suspend fun saveTokens(tokens: TokenPair, now: Long = System.currentTimeMillis()) {
        appContext.dataStore.edit {
            it[KEY_DEVICE_ID] = tokens.deviceId
            it[KEY_ACCESS_TOKEN] = tokens.accessToken
            it[KEY_ACCESS_EXPIRES_AT] = now + tokens.expiresIn * 1000L
            it[KEY_REFRESH_TOKEN] = tokens.refreshToken
            it[KEY_REFRESH_EXPIRES_AT] = tokens.refreshExpiresAt
        }
    }

    /** 認証情報が失効したときにトークンだけ消す（端末 UUID は残す）。 */
    suspend fun clearTokens() {
        appContext.dataStore.edit {
            it.remove(KEY_ACCESS_TOKEN)
            it.remove(KEY_ACCESS_EXPIRES_AT)
            it.remove(KEY_REFRESH_TOKEN)
            it.remove(KEY_REFRESH_EXPIRES_AT)
        }
    }

    /** 登録終了（引き継ぎ）時に、登録・送信に関する状態をすべて消す。IMEI は残す。 */
    suspend fun clearRegistration() {
        appContext.dataStore.edit {
            it.remove(KEY_DEVICE_ID)
            it.remove(KEY_ACCESS_TOKEN)
            it.remove(KEY_ACCESS_EXPIRES_AT)
            it.remove(KEY_REFRESH_TOKEN)
            it.remove(KEY_REFRESH_EXPIRES_AT)
            it.remove(KEY_PENDING_SEQUENCE)
            it.remove(KEY_PENDING_CANONICAL)
            it.remove(KEY_PENDING_SIGNATURE)
            it.remove(KEY_LAST_SENT_SPEC)
            it.remove(KEY_LAST_STATUS_SENT_AT)
        }
    }

    // --- 計測ログ送信の状態 ---------------------------------------------------

    suspend fun pendingOnce(): PendingLog? {
        val prefs = appContext.dataStore.data.first()
        val sequence = prefs[KEY_PENDING_SEQUENCE] ?: return null
        val canonical = prefs[KEY_PENDING_CANONICAL] ?: return null
        val signature = prefs[KEY_PENDING_SIGNATURE] ?: return null
        return PendingLog(sequence, canonical, signature)
    }

    suspend fun savePending(log: PendingLog) {
        appContext.dataStore.edit {
            it[KEY_PENDING_SEQUENCE] = log.sequence
            it[KEY_PENDING_CANONICAL] = log.canonicalPayload
            it[KEY_PENDING_SIGNATURE] = log.signatureDer
        }
    }

    suspend fun clearPending() {
        appContext.dataStore.edit {
            it.remove(KEY_PENDING_SEQUENCE)
            it.remove(KEY_PENDING_CANONICAL)
            it.remove(KEY_PENDING_SIGNATURE)
        }
    }

    /** 最後に受理されたスペックの正規化文字列（変化したときだけ送るため）。 */
    suspend fun lastSentSpecOnce(): String? = appContext.dataStore.data.first()[KEY_LAST_SENT_SPEC]

    suspend fun saveLastSentSpec(canonicalSpec: String) {
        appContext.dataStore.edit { it[KEY_LAST_SENT_SPEC] = canonicalSpec }
    }

    suspend fun lastStatusSentAtOnce(): Long? = appContext.dataStore.data.first()[KEY_LAST_STATUS_SENT_AT]

    suspend fun saveLastStatusSentAt(epochMillis: Long) {
        appContext.dataStore.edit { it[KEY_LAST_STATUS_SENT_AT] = epochMillis }
    }

    /** 直近の送信結果（設定画面の表示用）。 */
    val lastUploadResult: Flow<String?> = appContext.dataStore.data.map { it[KEY_LAST_UPLOAD_RESULT] }

    suspend fun saveLastUploadResult(message: String) {
        appContext.dataStore.edit { it[KEY_LAST_UPLOAD_RESULT] = message }
    }

    private inline fun <reified T> decode(raw: String): T? =
        runCatching { json.decodeFromString<T>(raw) }.getOrNull()

    private companion object {
        val KEY_IMEI = stringPreferencesKey("imei")
        val KEY_SPEC = stringPreferencesKey("device_spec")
        val KEY_STATUS = stringPreferencesKey("device_status")

        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_ACCESS_EXPIRES_AT = longPreferencesKey("access_expires_at")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_REFRESH_EXPIRES_AT = stringPreferencesKey("refresh_expires_at")

        val KEY_PENDING_SEQUENCE = longPreferencesKey("pending_sequence")
        val KEY_PENDING_CANONICAL = stringPreferencesKey("pending_canonical")
        val KEY_PENDING_SIGNATURE = stringPreferencesKey("pending_signature")
        val KEY_LAST_SENT_SPEC = stringPreferencesKey("last_sent_spec")
        val KEY_LAST_STATUS_SENT_AT = longPreferencesKey("last_status_sent_at")
        val KEY_LAST_UPLOAD_RESULT = stringPreferencesKey("last_upload_result")
    }
}
