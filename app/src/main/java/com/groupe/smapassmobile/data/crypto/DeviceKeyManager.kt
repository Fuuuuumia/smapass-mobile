package com.groupe.smapassmobile.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 端末の署名鍵を Android Keystore（TEE / StrongBox）で生成・利用する。
 *
 * 秘密鍵はハードウェアから取り出せない（＝引き継ぎで「コピー」はできない）。
 * 引き継ぎはこの鍵を破棄し、新しい端末で新しい鍵を生成して再登録する方式を取る。
 */
class DeviceKeyManager {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = keyStore.containsAlias(ALIAS)

    fun deleteKey() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    /**
     * サーバーの attestation challenge を埋め込んだ新しい P-256 鍵を生成する。
     * 既存の鍵があれば作り直す（毎回新しい challenge を埋める必要があるため）。
     */
    fun generateAttestedKey(challenge: ByteArray) {
        deleteKey()
        try {
            generate(challenge, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (_: Exception) {
            // StrongBox 非対応端末では TEE にフォールバックする。
            deleteKey()
            generate(challenge, strongBox = false)
        }
    }

    /** signing_payload の生バイト列を SHA256withECDSA で署名し、ASN.1 DER を返す。 */
    fun sign(payload: ByteArray): ByteArray {
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(entry.privateKey)
            update(payload)
            sign()
        }
    }

    /** 証明書チェーン（リーフ→ルート）の DER を標準 Base64 にして返す。 */
    fun certificateChainBase64(): List<String> =
        keyStore.getCertificateChain(ALIAS).map {
            Base64.encodeToString(it.encoded, Base64.NO_WRAP)
        }

    /** 公開鍵を X.509 SubjectPublicKeyInfo → PEM にして返す。 */
    fun publicKeyPem(): String {
        val der = keyStore.getCertificate(ALIAS).publicKey.encoded
        val body = Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    private fun generate(challenge: ByteArray, strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "smapass_device_key"
        const val EC_CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
