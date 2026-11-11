package com.groupe.smapassmobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import com.groupe.smapassmobile.BuildConfig

/**
 * smapass-back への HTTP クライアント（docs/api.md の「アプリ用の認証・書き込み」）。
 *
 * 接続先 [BASE_URL] はルートの gradle.properties の `smapass.apiBaseUrl` で変更する。
 */
class SmapassApi(
    baseUrl: String = BASE_URL,
) {
    private val client = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(jsonFormat)
        }
        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
        }
    }

    // --- 登録・認証 -----------------------------------------------------------

    /** 初回登録用チャレンジ（5分有効）。 */
    suspend fun createChallenge(imei: String): KeyChallenge =
        client.post("/api/auth/challenges") {
            setBody(EnrollmentChallengeRequest(imei))
        }.body()

    /** IMEI・公開鍵・attestation・所有確認署名を送り、端末 UUID と認証情報を得る。 */
    suspend fun enrollDevice(request: EnrollDeviceRequest): TokenPair =
        client.post("/api/auth/devices") {
            setBody(request)
        }.body()

    /** トークン更新。成功時は両方のトークンを置き換える（旧トークンは使えなくなる）。 */
    suspend fun refresh(refreshToken: String): TokenPair =
        client.post("/api/auth/refresh") {
            setBody(RefreshRequest(refreshToken))
        }.body()

    // --- 端末（Bearer 必須） ---------------------------------------------------

    /** 自分の端末の連番・前ハッシュ・鍵 ID・公開状態を取得する。 */
    suspend fun lookup(deviceId: String, accessToken: String): OwnedDevice =
        client.post("/api/devices/lookup") {
            bearerAuth(accessToken)
            setBody(LookupRequest(deviceId))
        }.body()

    /** 署名付き計測ログを1件送る。新規201・完全一致の再送200。 */
    suspend fun submitLog(
        deviceId: String,
        measurement: SubmitMeasurement,
        accessToken: String,
    ): MeasurementReceipt =
        client.post("/api/devices/$deviceId/logs") {
            bearerAuth(accessToken)
            setBody(measurement)
        }.body()

    /** 公開・非公開を変更する。 */
    suspend fun updateVisibility(
        deviceId: String,
        visibility: String,
        accessToken: String,
    ): DeviceVisibility =
        client.patch("/api/devices/$deviceId/visibility") {
            bearerAuth(accessToken)
            setBody(UpdateVisibilityRequest(visibility))
        }.body()

    /** 登録・鍵・認証情報を終了する（引き継ぎ）。成功204。履歴は残る。 */
    suspend fun revokeDevice(deviceId: String, accessToken: String) {
        client.post("/api/devices/$deviceId/revoke") {
            bearerAuth(accessToken)
        }
    }

    companion object {
        /** gradle.properties の `smapass.apiBaseUrl`。 */
        const val BASE_URL = BuildConfig.API_BASE_URL

        private val jsonFormat = Json { ignoreUnknownKeys = true }

        /** エラー応答を「HTTP 409 conflict: …」のような表示用文字列にする。 */
        suspend fun describe(error: Throwable): String = when (error) {
            is ResponseException -> {
                val status = error.response.status.value
                val detail = runCatching {
                    jsonFormat.decodeFromString<ApiErrorBody>(error.response.bodyAsText()).error
                }.getOrNull()
                if (detail != null) "HTTP $status ${detail.code}: ${detail.message}" else "HTTP $status"
            }
            else -> error.message ?: error::class.simpleName ?: "不明なエラー"
        }
    }
}
