package com.groupe.smapassmobile.data

import android.content.Context
import android.util.Base64
import com.groupe.smapassmobile.data.crypto.DeviceKeyManager
import com.groupe.smapassmobile.data.remote.EnrollDeviceRequest
import com.groupe.smapassmobile.data.remote.KeyProof
import com.groupe.smapassmobile.data.remote.OwnedDevice
import com.groupe.smapassmobile.data.remote.SmapassApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 端末登録（Keystore 鍵 attestation ＋ 所有確認署名）、公開設定、登録終了（引き継ぎ）をまとめる。
 *
 * - [enroll] : チャレンジ取得 → Keystore で P-256 鍵生成＋attestation → 署名 → 登録 → 端末 UUID とトークンを保存。
 * - [lookup] / [setVisibility] : 自分の端末の登録情報・公開設定。
 * - [unregister] : サーバーの登録を終了 → Keystore の鍵を破棄 → 登録状態を消去（＝引き継ぎ）。
 */
class DeviceEnrollmentRepository(
    context: Context,
    private val repository: DeviceRepository = DeviceRepository(context),
    private val api: SmapassApi = SmapassApi(),
    private val keys: DeviceKeyManager = DeviceKeyManager(),
) {
    private val auth = AuthSession(repository, api)

    suspend fun enroll(imei: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val challenge = api.createChallenge(imei)
            keys.generateAttestedKey(Base64.decode(challenge.attestationChallenge, Base64.DEFAULT))

            // signing_payload は復号したバイト列をそのまま署名する（再構築・再エンコードしない）。
            val signature = keys.sign(Base64.decode(challenge.signingPayload, Base64.DEFAULT))
            val proof = KeyProof(
                challengeId = challenge.id,
                certificateChain = keys.certificateChainBase64(),
                signatureDer = Base64.encodeToString(signature, Base64.NO_WRAP),
            )

            val tokens = api.enrollDevice(
                EnrollDeviceRequest(
                    imei = imei,
                    publicKey = keys.publicKeyPem(),
                    proof = proof,
                ),
            )
            // 新しい登録期間なので、前の登録の送信状態は引き継がない。
            repository.clearRegistration()
            repository.saveTokens(tokens)
        }
    }

    suspend fun lookup(): Result<OwnedDevice> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = repository.deviceIdOnce() ?: throw NotRegisteredException()
            auth.withAccessToken { api.lookup(deviceId, it) }
        }
    }

    suspend fun setVisibility(visibility: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = repository.deviceIdOnce() ?: throw NotRegisteredException()
            auth.withAccessToken { api.updateVisibility(deviceId, visibility, it) }.visibility
        }
    }

    suspend fun unregister(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = repository.deviceIdOnce() ?: throw NotRegisteredException()
            auth.withAccessToken { api.revokeDevice(deviceId, it) }
            keys.deleteKey()
            repository.clearRegistration()
        }
    }
}
