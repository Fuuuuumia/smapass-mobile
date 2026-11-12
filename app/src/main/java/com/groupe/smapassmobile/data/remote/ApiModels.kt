package com.groupe.smapassmobile.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// smapass-back の docs/openapi.json に合わせた通信用の型。

/** POST /api/auth/challenges の本文。 */
@Serializable
data class EnrollmentChallengeRequest(
    val imei: String,
)

/** 登録用チャレンジの応答（KeyChallenge）。 */
@Serializable
data class KeyChallenge(
    val id: String,
    @SerialName("attestation_challenge") val attestationChallenge: String,
    @SerialName("signing_payload") val signingPayload: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** 所有確認・attestation の証跡。 */
@Serializable
data class KeyProof(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("certificate_chain") val certificateChain: List<String>,
    @SerialName("signature_der") val signatureDer: String,
)

/** POST /api/auth/devices の本文。 */
@Serializable
data class EnrollDeviceRequest(
    val imei: String,
    @SerialName("public_key") val publicKey: String,
    val proof: KeyProof,
)

/** 認証情報一式（TokenPair）。対象端末は IMEI ではなく端末 UUID（device_id）。 */
@Serializable
data class TokenPair(
    @SerialName("device_id") val deviceId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String,
)

/** POST /api/auth/refresh・/api/auth/revoke の本文。 */
@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

/** POST /api/devices/lookup の本文。 */
@Serializable
data class LookupRequest(
    @SerialName("device_id") val deviceId: String,
)

/** 認証付き lookup の応答（Device）。次に送る連番・前ハッシュ・鍵 ID を含む。 */
@Serializable
data class OwnedDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("key_id") val keyId: String,
    @SerialName("next_sequence") val nextSequence: Long,
    @SerialName("previous_hash") val previousHash: String? = null,
    val visibility: String,
    @SerialName("registered_at") val registeredAt: String,
    @SerialName("last_verified_log_at") val lastVerifiedLogAt: String? = null,
    @SerialName("reclaimable_at") val reclaimableAt: String,
    @SerialName("key_attested") val keyAttested: Boolean,
    @SerialName("imei_attested") val imeiAttested: Boolean,
)

/** POST /api/devices/{id}/logs の本文。 */
@Serializable
data class SubmitMeasurement(
    @SerialName("canonical_payload") val canonicalPayload: String,
    @SerialName("signature_der") val signatureDer: String,
)

/** 計測ログの受理結果（新規201・同一再送200）。 */
@Serializable
data class MeasurementReceipt(
    @SerialName("device_id") val deviceId: String,
    val sequence: Long,
    @SerialName("payload_hash") val payloadHash: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("signature_verified") val signatureVerified: Boolean,
    val duplicate: Boolean,
)

/** PATCH /api/devices/{id}/visibility の本文。 */
@Serializable
data class UpdateVisibilityRequest(
    val visibility: String,
)

/** 公開設定の変更結果。 */
@Serializable
data class DeviceVisibility(
    @SerialName("device_id") val deviceId: String,
    val visibility: String,
)

/** 共通エラー `{"error":{"code":"...","message":"..."}}`。 */
@Serializable
data class ApiErrorBody(
    val error: ApiErrorDetail,
)

@Serializable
data class ApiErrorDetail(
    val code: String,
    val message: String,
)

object Visibility {
    const val PUBLIC = "public"
    const val PRIVATE = "private"
}
