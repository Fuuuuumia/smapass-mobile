package com.groupe.smapassmobile.data

import com.groupe.smapassmobile.data.jcs.Jcs
import com.groupe.smapassmobile.data.measurement.MeasurementPayloads
import com.groupe.smapassmobile.ui.stat.DeviceSpec
import com.groupe.smapassmobile.ui.stat.DeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * smapass-back の共通テストデータ（docs/examples/jcs-vectors.json・measurement-vector.json）と
 * 同じ結果になることを確認する。サーバーは正規化結果がバイト単位で一致しないと 400 を返す。
 */
class MeasurementCanonicalTest {

    // --- jcs-vectors.json -----------------------------------------------------

    @Test
    fun jcsVector1_numbersAndKeyOrder() {
        val input = mapOf(
            "z" to -0.0,
            "a" to 1.0,
            "n" to 1e-7,
            "large" to 1e21,
            "é" to "é",
        )
        assertEquals("""{"a":1,"large":1e+21,"n":1e-7,"z":0,"é":"é"}""", Jcs.canonicalize(input))
    }

    @Test
    fun jcsVector2_utf16KeyOrderAndEscapes() {
        val input = mapOf(
            "" to "private-use",
            "😀" to "emoji",
            "a" to "line\n",
        )
        assertEquals(
            "{\"a\":\"line\\n\",\"😀\":\"emoji\",\"\":\"private-use\"}",
            Jcs.canonicalize(input),
        )
    }

    @Test
    fun jcsVector3_shortestNumbers() {
        val input = mapOf("values" to listOf(4.50, 2e-3, 333333333.33333329))
        assertEquals("""{"values":[4.5,0.002,333333333.3333333]}""", Jcs.canonicalize(input))
    }

    @Test
    fun ecmaScriptNumberFormatting() {
        assertEquals("100", Jcs.formatNumber(100.0))
        assertEquals("0.30000000000000004", Jcs.formatNumber(0.1 + 0.2))
        assertEquals("1e+21", Jcs.formatNumber(1e21))
        assertEquals("100000000000000000000", Jcs.formatNumber(1e20))
        assertEquals("0.000001", Jcs.formatNumber(1e-6))
        assertEquals("1e-7", Jcs.formatNumber(1e-7))
        assertEquals("-32.3", Jcs.formatNumber(-32.3))
        assertEquals("5e-324", Jcs.formatNumber(Double.MIN_VALUE))
        assertEquals("1.7976931348623157e+308", Jcs.formatNumber(Double.MAX_VALUE))
    }

    @Test
    fun controlCharactersUseLowercaseUnicodeEscape() {
        assertEquals("\"\\u001f\\t\\\"\\\\\"", Jcs.canonicalize("\u001F\t\"\\"))
    }

    // --- measurement-vector.json ------------------------------------------------

    private val vectorCanonical =
        """{"collected_at":"2026-01-01T00:00:00Z","device_id":"22222222-2222-4222-8222-222222222222",""" +
            """"key_id":"11111111-1111-4111-8111-111111111111","previous_hash":null,"schema_version":1,""" +
            """"sequence":1,"spec":null,"status":{"battery_charge_mah":3200,"battery_cycle_count":162,""" +
            """"battery_full_charge_mah":4400,"battery_temp_c":32.3,"boot_count":46,"had_abnormal_exit":false,""" +
            """"os_version":"16","sdk_int":36,"thermal_headroom":0.3123456789012345},"type":"status"}"""

    private val vectorPublicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEGoM0JZ2eelxADepPPMLPtzW30DH3" +
            "4KgtTH8WPdchfj5ON4neW6zyonW2uzjK3SLVSTyjnrrws0WBrdgDAH7Oug=="

    private val vectorSignature =
        "MEUCIQCka7wGigf5uCqoBcDEK5/2S0aQ5+hTAB1pOQ+jWDzmxQIge3tPnPP/jICU8T98h2+s5VjfXMjIPYw6/5rmwLWb6Bs="

    @Test
    fun measurementVector_envelopeCanonicalHashAndSignature() {
        val envelope = MeasurementPayloads.envelope(
            deviceId = "22222222-2222-4222-8222-222222222222",
            keyId = "11111111-1111-4111-8111-111111111111",
            sequence = 1,
            type = MeasurementPayloads.TYPE_STATUS,
            collectedAtMillis = 1_767_225_600_000L, // 2026-01-01T00:00:00Z
            previousHash = null,
            spec = null,
            status = mapOf(
                "os_version" to "16",
                "sdk_int" to 36,
                "battery_temp_c" to 32.3,
                "battery_cycle_count" to 162,
                "battery_charge_mah" to 3200,
                "battery_full_charge_mah" to 4400,
                "boot_count" to 46,
                "had_abnormal_exit" to false,
                "thermal_headroom" to 0.3123456789012345,
            ),
        )
        val canonical = Jcs.canonicalize(envelope)
        assertEquals(vectorCanonical, canonical)

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("20ca03c6868b61723655ea3fb20138d751080e55d8940645bff0431866db18c7", hash)

        // 正本の UTF-8 バイト列そのものへの SHA256withECDSA（DER・標準 Base64）であることを確認。
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(vectorPublicKey)))
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonical.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(vectorSignature))
        }
        assertTrue(verified)
    }

    // --- 収集値からの変換 ---------------------------------------------------------

    @Test
    fun statusMeasurement_normalizesFloatsAndKeepsUnknownAsNull() {
        val status = DeviceStatus(
            osVersion = "16",
            sdk = 36,
            batteryTemperature = 32.3f,
            batteryCycleCount = null,
            remainingChargeMah = -1, // 負の電荷は取得不能扱い
            estimatedFullChargeMah = 0, // 満充電容量は1以上
            thermalHeadroom = 0.9f,
            bootCount = 46,
            hasAbnormalExit = null,
            collectedAt = 0L,
        )
        val measurement = MeasurementPayloads.statusMeasurement(status)
        assertEquals(32.3, measurement["battery_temp_c"])
        assertEquals(0.9, measurement["thermal_headroom"])
        assertNull(measurement["battery_cycle_count"])
        assertNull(measurement["battery_charge_mah"])
        assertNull(measurement["battery_full_charge_mah"])
        assertNull(measurement["had_abnormal_exit"])
        assertTrue(measurement.containsKey("had_abnormal_exit")) // 省略せず null を入れる
        assertEquals(
            """{"battery_charge_mah":null,"battery_cycle_count":null,"battery_full_charge_mah":null,""" +
                """"battery_temp_c":32.3,"boot_count":46,"had_abnormal_exit":null,"os_version":"16",""" +
                """"sdk_int":36,"thermal_headroom":0.9}""",
            Jcs.canonicalize(measurement),
        )
    }

    @Test
    fun specMeasurement_excludesPersonalNameAndNullsUnknowns() {
        val spec = DeviceSpec(
            imei = "490154203237518",
            manufacturer = " Google ",
            model = "Pixel 8",
            deviceName = "ふみのスマホ",
            soc = "unknown",
            cpuCores = 9,
            totalRamBytes = 8_589_934_592L,
            totalStorageBytes = 0L,
            screenSizeInch = 6.2f,
            densityDpi = 428,
            updatedAt = 0L,
        )
        assertEquals(
            """{"cpu_cores":9,"manufacturer":"Google","model":"Pixel 8","ram_bytes":8589934592,""" +
                """"screen_density_dpi":428,"screen_size_inch":6.2,"soc":null,"storage_bytes":null}""",
            Jcs.canonicalize(MeasurementPayloads.specMeasurement(spec)),
        )
    }

    @Test
    fun collectedAtMatchesGoRfc3339Nano() {
        val base = 1_767_225_600_000L
        assertEquals("2026-01-01T00:00:00Z", MeasurementPayloads.rfc3339Utc(base))
        assertEquals("2026-01-01T00:00:00.123Z", MeasurementPayloads.rfc3339Utc(base + 123))
        assertEquals("2026-01-01T00:00:00.12Z", MeasurementPayloads.rfc3339Utc(base + 120))
        assertEquals("2026-01-01T00:00:00.1Z", MeasurementPayloads.rfc3339Utc(base + 100))
    }
}
