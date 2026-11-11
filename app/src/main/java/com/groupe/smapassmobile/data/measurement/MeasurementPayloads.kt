package com.groupe.smapassmobile.data.measurement

import com.groupe.smapassmobile.ui.stat.DeviceSpec
import com.groupe.smapassmobile.ui.stat.DeviceStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * 計測ログ（schema_version 1）の署名対象 envelope を組み立てる。
 *
 * サーバーの検証（docs/measurement-ingestion.md）に合わせ、不明な値は 0 / false で補わず null にする。
 * IMEI・端末名（個人が付けた名前）・所有者情報は含めない。
 * Android API に依存しないため JVM の単体テストで検証できる。
 */
object MeasurementPayloads {
    const val SCHEMA_VERSION = 1
    const val TYPE_SPEC = "spec"
    const val TYPE_STATUS = "status"

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val UNKNOWN = "unknown" // android.os.Build.UNKNOWN

    fun envelope(
        deviceId: String,
        keyId: String,
        sequence: Long,
        type: String,
        collectedAtMillis: Long,
        previousHash: String?,
        spec: Map<String, Any?>?,
        status: Map<String, Any?>?,
    ): Map<String, Any?> {
        require(type == TYPE_SPEC || type == TYPE_STATUS)
        require((type == TYPE_SPEC) == (spec != null) && (type == TYPE_STATUS) == (status != null)) {
            "spec と status は type に対応する片方だけを指定する"
        }
        return mapOf(
            "schema_version" to SCHEMA_VERSION,
            "device_id" to deviceId,
            "key_id" to keyId,
            "sequence" to sequence,
            "type" to type,
            "collected_at" to rfc3339Utc(collectedAtMillis),
            "previous_hash" to previousHash,
            "spec" to spec,
            "status" to status,
        )
    }

    fun specMeasurement(spec: DeviceSpec): Map<String, Any?> = mapOf(
        "manufacturer" to requiredText(spec.manufacturer, 128, "manufacturer"),
        "model" to requiredText(spec.model, 128, "model"),
        "soc" to optionalText(spec.soc, 128),
        "cpu_cores" to spec.cpuCores.takeIf { it in 1..32767 },
        "ram_bytes" to spec.totalRamBytes.takeIf { it in 1..MAX_SAFE_INTEGER },
        "storage_bytes" to spec.totalStorageBytes.takeIf { it in 1..MAX_SAFE_INTEGER },
        "screen_size_inch" to roundTo(spec.screenSizeInch.toDouble(), 2).takeIf { it in 0.01..99.99 },
        "screen_density_dpi" to spec.densityDpi.takeIf { it in 1..32767 },
    )

    fun statusMeasurement(status: DeviceStatus): Map<String, Any?> = mapOf(
        "os_version" to requiredText(status.osVersion, 64, "os_version"),
        "sdk_int" to status.sdk.also { require(it in 1..32767) { "sdk_int out of range" } },
        "battery_temp_c" to status.batteryTemperature
            ?.let { roundTo(it.toDouble(), 1) }
            ?.takeIf { it in -9999.9..9999.9 },
        "battery_cycle_count" to status.batteryCycleCount?.takeIf { it >= 0 },
        "battery_charge_mah" to status.remainingChargeMah?.takeIf { it >= 0 },
        "battery_full_charge_mah" to status.estimatedFullChargeMah?.takeIf { it >= 1 },
        "boot_count" to status.bootCount?.takeIf { it >= 0 },
        "had_abnormal_exit" to status.hasAbnormalExit,
        // Float をそのまま Double にすると 0.8999999761… のような誤差が出るため、
        // Float の最短表記を経由して Double にする。
        "thermal_headroom" to status.thermalHeadroom
            ?.takeIf { it.isFinite() }
            ?.toString()
            ?.toDouble(),
    )

    /**
     * Go の time.RFC3339Nano（UTC）と同じ表記。小数秒は末尾の0を省き、0なら小数部ごと省く。
     * サーバーは型付きで再シリアライズした結果とも一致を確認するため、この形式に厳密に合わせる。
     */
    fun rfc3339Utc(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val seconds = format.format(Date(epochMillis))
        val millis = Math.floorMod(epochMillis, 1000L).toInt()
        val fraction = if (millis == 0) "" else "." + millis.toString().padStart(3, '0').trimEnd('0')
        return "$seconds${fraction}Z"
    }

    private fun roundTo(value: Double, digits: Int): Double {
        val scale = when (digits) {
            1 -> 10.0
            2 -> 100.0
            else -> throw IllegalArgumentException("unsupported digits: $digits")
        }
        return (value * scale).roundToLong() / scale
    }

    /** 前後の空白・制御文字を除去する。サーバーはこれらを含む文字列を拒否する。 */
    private fun clean(value: String): String =
        value.filterNot { Character.isISOControl(it) }.trim()

    private fun requiredText(value: String, maxBytes: Int, name: String): String {
        val cleaned = clean(value)
        require(cleaned.isNotEmpty() && cleaned.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            "$name is empty or too long"
        }
        return cleaned
    }

    private fun optionalText(value: String?, maxBytes: Int): String? =
        value?.let(::clean)
            ?.takeIf { it.isNotEmpty() && !it.equals(UNKNOWN, ignoreCase = true) }
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= maxBytes }
}
