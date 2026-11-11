package com.groupe.smapassmobile.data.jcs

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * RFC 8785 (JSON Canonicalization Scheme) のシリアライザ。
 *
 * サーバーは受け取った canonical_payload を自前で JCS 正規化し、バイト単位で一致しない場合は
 * 400 を返す。そのため、キー順（UTF-16 コード単位順）・文字列エスケープ・数値表現
 * （ECMAScript の Number.prototype.toString）を仕様どおりに再現する。
 *
 * 扱える値: null / Boolean / String / Int / Long / Double / Map<String, *> / List<*>。
 * Float は誤差の混入を防ぐため受け付けない（呼び出し側で Double に正規化すること）。
 */
object Jcs {
    /** JSON の安全な整数の上限（2^53 - 1）。 */
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun canonicalize(value: Any?): String = StringBuilder().apply { writeValue(value) }.toString()

    private fun StringBuilder.writeValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(if (value) "true" else "false")
            is String -> writeString(value)
            is Int -> append(value.toString())
            is Long -> {
                require(value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "integer out of JSON safe range" }
                append(value.toString())
            }
            is Double -> append(formatNumber(value))
            is Map<*, *> -> writeObject(value)
            is List<*> -> writeArray(value)
            else -> throw IllegalArgumentException("unsupported JSON value: ${value::class}")
        }
    }

    private fun StringBuilder.writeObject(map: Map<*, *>) {
        val entries = map.entries.map { (key, value) ->
            require(key is String) { "JSON object keys must be strings" }
            key to value
        }
        // String.compareTo は UTF-16 コード単位で比較するため、RFC 8785 のキー順と一致する。
        val sorted = entries.sortedWith { a, b -> a.first.compareTo(b.first) }
        append('{')
        sorted.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            writeString(key)
            append(':')
            writeValue(value)
        }
        append('}')
    }

    private fun StringBuilder.writeArray(list: List<*>) {
        append('[')
        list.forEachIndexed { index, value ->
            if (index > 0) append(',')
            writeValue(value)
        }
        append(']')
    }

    private fun StringBuilder.writeString(value: String) {
        append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                Character.isHighSurrogate(c) -> {
                    require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                        "lone surrogate is not allowed in JCS"
                    }
                    append(c).append(value[i + 1])
                    i++
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("lone surrogate is not allowed in JCS")
                else -> append(c)
            }
            i++
        }
        append('"')
    }

    /** ECMAScript の Number.prototype.toString と同じ表記（RFC 8785 §3.2.2.3）。 */
    fun formatNumber(value: Double): String {
        require(value.isFinite()) { "NaN / Infinity are not valid JSON numbers" }
        if (value == 0.0) return "0" // -0 も "0"
        if (value < 0) return "-" + formatNumber(-value)

        // 往復変換で元の値に戻る最短の10進桁列を求める。
        val exact = BigDecimal(value)
        val shortest = (1..17).asSequence()
            .map { exact.round(MathContext(it, RoundingMode.HALF_EVEN)) }
            .first { it.toDouble() == value }
            .stripTrailingZeros()

        val digits = shortest.unscaledValue().toString()
        val k = digits.length
        val n = k - shortest.scale() // value = digits × 10^(n - k)

        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exponent = n - 1
                val sign = if (exponent >= 0) "+" else "-"
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                "${mantissa}e$sign${kotlin.math.abs(exponent)}"
            }
        }
    }
}
