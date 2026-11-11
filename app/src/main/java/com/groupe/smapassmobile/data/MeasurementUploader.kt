package com.groupe.smapassmobile.data

import android.content.Context
import android.util.Base64
import com.groupe.smapassmobile.data.crypto.DeviceKeyManager
import com.groupe.smapassmobile.data.jcs.Jcs
import com.groupe.smapassmobile.data.measurement.MeasurementPayloads
import com.groupe.smapassmobile.data.remote.MeasurementReceipt
import com.groupe.smapassmobile.data.remote.SmapassApi
import com.groupe.smapassmobile.data.remote.SubmitMeasurement
import com.groupe.smapassmobile.ui.stat.DeviceSpec
import com.groupe.smapassmobile.ui.stat.DeviceStatus
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 収集した spec / status を署名付き計測ログとして POST /api/devices/{id}/logs に送る。
 *
 * 手順（docs/measurement-ingestion.md）:
 * 1. lookup で現在の key_id・next_sequence・previous_hash を取得
 * 2. envelope を RFC 8785 JCS で正規化し、UTF-8 バイト列を Keystore の鍵で SHA256withECDSA 署名
 * 3. 応答が来るまで同じ正本・署名を保持し、通信結果が不明なら次回そのまま再送
 *
 * 連番は端末全体で1本なので、送信はプロセス全体で直列化する。
 */
class MeasurementUploader(
    context: Context,
    private val repository: DeviceRepository = DeviceRepository(context),
    private val api: SmapassApi = SmapassApi(),
    private val keys: DeviceKeyManager = DeviceKeyManager(),
) {
    private val auth = AuthSession(repository, api)

    sealed interface Outcome {
        data class Sent(val receipts: List<MeasurementReceipt>) : Outcome
        data class Skipped(val reason: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** スペックが前回の送信から変わっていれば送る。別の送信中なら今回は見送る。 */
    suspend fun uploadSpecIfChanged(spec: DeviceSpec): Outcome = withContext(Dispatchers.IO) {
        if (!repository.isRegistered.first()) return@withContext Outcome.Skipped("未登録")
        val measurement = runCatching { MeasurementPayloads.specMeasurement(spec) }
            .getOrElse { return@withContext record(Outcome.Failed("スペックを送信できません: ${it.message}")) }
        if (Jcs.canonicalize(measurement) == repository.lastSentSpecOnce()) {
            return@withContext Outcome.Skipped("スペックに変化なし")
        }
        exclusive(wait = false) { listOf(sendSpec(spec, measurement)) }
    }

    /** 前回の状態送信から [STATUS_UPLOAD_INTERVAL_MS] 以上経っていれば送る。 */
    suspend fun uploadStatusIfDue(
        status: DeviceStatus,
        now: Long = System.currentTimeMillis(),
    ): Outcome = withContext(Dispatchers.IO) {
        if (!repository.isRegistered.first()) return@withContext Outcome.Skipped("未登録")
        val last = repository.lastStatusSentAtOnce()
        if (last != null && now - last < STATUS_UPLOAD_INTERVAL_MS) {
            return@withContext Outcome.Skipped("送信間隔内")
        }
        exclusive(wait = false) { listOf(sendStatus(status)) }
    }

    /** 設定画面の「今すぐ送信」。スペックは変化時のみ、状態は必ず送る。 */
    suspend fun uploadNow(spec: DeviceSpec?, status: DeviceStatus?): Outcome = withContext(Dispatchers.IO) {
        if (!repository.isRegistered.first()) return@withContext Outcome.Failed("この端末はサーバーに登録されていません")
        exclusive(wait = true) {
            buildList {
                if (spec != null) {
                    val measurement = MeasurementPayloads.specMeasurement(spec)
                    if (Jcs.canonicalize(measurement) != repository.lastSentSpecOnce()) {
                        add(sendSpec(spec, measurement))
                    }
                }
                if (status != null) add(sendStatus(status))
            }
        }
    }

    // --- 内部処理（uploadMutex を保持した状態で呼ぶ） -------------------------

    private suspend fun sendSpec(spec: DeviceSpec, measurement: Map<String, Any?>): MeasurementReceipt {
        val receipt = send(
            type = MeasurementPayloads.TYPE_SPEC,
            collectedAt = spec.updatedAt,
            spec = measurement,
            status = null,
        )
        repository.saveLastSentSpec(Jcs.canonicalize(measurement))
        return receipt
    }

    private suspend fun sendStatus(status: DeviceStatus): MeasurementReceipt {
        val receipt = send(
            type = MeasurementPayloads.TYPE_STATUS,
            collectedAt = status.collectedAt,
            spec = null,
            status = MeasurementPayloads.statusMeasurement(status),
        )
        repository.saveLastStatusSentAt(System.currentTimeMillis())
        return receipt
    }

    private suspend fun send(
        type: String,
        collectedAt: Long,
        spec: Map<String, Any?>?,
        status: Map<String, Any?>?,
    ): MeasurementReceipt {
        val deviceId = repository.deviceIdOnce() ?: throw NotRegisteredException()
        check(keys.hasKey()) { "端末の鍵が見つかりません。再登録が必要です" }

        // 前回応答を受け取れなかったログがあれば、まず同じ内容のまま再送する。
        flushPending(deviceId)

        val owned = auth.withAccessToken { api.lookup(deviceId, it) }
        val canonical = Jcs.canonicalize(
            MeasurementPayloads.envelope(
                deviceId = owned.deviceId,
                keyId = owned.keyId,
                sequence = owned.nextSequence,
                type = type,
                collectedAtMillis = collectedAt,
                previousHash = owned.previousHash,
                spec = spec,
                status = status,
            ),
        )
        // ハッシュではなく正本の UTF-8 バイト列そのものを署名する（二重 SHA-256 にしない）。
        val signature = keys.sign(canonical.toByteArray(Charsets.UTF_8))
        val pending = PendingLog(
            sequence = owned.nextSequence,
            canonicalPayload = canonical,
            signatureDer = Base64.encodeToString(signature, Base64.NO_WRAP),
        )
        repository.savePending(pending)
        return submit(deviceId, pending)
    }

    private suspend fun flushPending(deviceId: String) {
        val pending = repository.pendingOnce() ?: return
        try {
            submit(deviceId, pending)
        } catch (e: ResponseException) {
            // そのままでは受理されない内容は submit 内で破棄済み。新しいログの送信へ進む。
            if (e.response.status.value !in DISCARD_STATUSES) throw e
        }
    }

    private suspend fun submit(deviceId: String, pending: PendingLog): MeasurementReceipt {
        try {
            val receipt = auth.withAccessToken {
                api.submitLog(
                    deviceId = deviceId,
                    measurement = SubmitMeasurement(pending.canonicalPayload, pending.signatureDer),
                    accessToken = it,
                )
            }
            repository.clearPending()
            return receipt
        } catch (e: ResponseException) {
            // 400 形式不正・403 鍵/署名不一致・409 連番/前ハッシュ競合・413 上限超過は、
            // 同じ内容を再送しても受理されないので破棄する。それ以外（5xx・429）と
            // 通信エラーは結果が不明なので保持し、次回同じ内容で再送する。
            if (e.response.status.value in DISCARD_STATUSES) repository.clearPending()
            throw e
        }
    }

    private suspend fun exclusive(
        wait: Boolean,
        block: suspend () -> List<MeasurementReceipt>,
    ): Outcome {
        if (wait) uploadMutex.lock() else if (!uploadMutex.tryLock()) return Outcome.Skipped("送信中")
        return try {
            val receipts = block()
            record(Outcome.Sent(receipts))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(Outcome.Failed("送信に失敗しました: ${SmapassApi.describe(e)}"))
        } finally {
            uploadMutex.unlock()
        }
    }

    /** 送信結果を設定画面に表示できるよう保存する。 */
    private suspend fun record(outcome: Outcome): Outcome {
        val time = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
        val message = when (outcome) {
            is Outcome.Sent -> if (outcome.receipts.isEmpty()) {
                "$time 送信するデータはありませんでした"
            } else {
                "$time 送信しました（" +
                    outcome.receipts.joinToString { "#${it.sequence}" + if (it.duplicate) " 再送" else "" } +
                    "）"
            }
            is Outcome.Failed -> "$time ${outcome.message}"
            is Outcome.Skipped -> return outcome
        }
        repository.saveLastUploadResult(message)
        return outcome
    }

    companion object {
        /** 状態ログを送る最小間隔。画面表示中の5秒ポーリングごとには送らない。 */
        const val STATUS_UPLOAD_INTERVAL_MS = 60 * 60 * 1000L

        private val DISCARD_STATUSES = setOf(400, 403, 409, 413)

        /** 連番を1本に保つため、どのインスタンスから呼ばれても送信は1件ずつ行う。 */
        private val uploadMutex = Mutex()
    }
}
