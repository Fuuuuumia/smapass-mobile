package com.groupe.smapassmobile.ui.stat

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.sqrt

class DeviceInfoCollector(
    private val context: Context,
) {
    private val resolver get() = context.contentResolver

    private val activityManager: ActivityManager
        get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val batteryManager: BatteryManager
        get() = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val powerManager: PowerManager
        get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val windowManager: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun collectSpec(): DeviceSpec {
        val (screenSizeInch, densityDpi) = collectScreen()
        return DeviceSpec(
            // 通常アプリは Android 10 以降 IMEI を取得できない（権限も未宣言）ため null。
            imei = null,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            deviceName = resolveDeviceName(),
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                null
            },
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = collectTotalRam(),
            totalStorageBytes = collectTotalStorage(),
            screenSizeInch = screenSizeInch,
            densityDpi = densityDpi,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun collectStatus(lastCollectedAt: Long): DeviceStatus {
        val chargeCounterMah = batteryManager.intPropertyOrNull(
            BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
        )?.let { microAmpHours -> microAmpHours / 1000 }

        return DeviceStatus(
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            sdk = Build.VERSION.SDK_INT,
            batteryTemperature = collectBatteryTemperature(),
            batteryCycleCount = collectBatteryCycleCount(),
            remainingChargeMah = chargeCounterMah,
            estimatedFullChargeMah = estimateFullChargeMah(chargeCounterMah),
            thermalHeadroom = collectThermalHeadroom(),
            bootCount = collectBootCount(),
            hasAbnormalExit = hasAbnormalExitSince(lastCollectedAt),
            collectedAt = System.currentTimeMillis(),
        )
    }

    private fun resolveDeviceName(): String? =
        runCatching { Settings.Global.getString(resolver, "device_name") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.DEVICE

    private fun collectTotalRam(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem
    }

    private fun collectTotalStorage(): Long =
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.blockCountLong * stat.blockSizeLong
        }.getOrDefault(0L)

    private fun collectScreen(): Pair<Float, Int> {
        val metrics = context.resources.displayMetrics
        val widthPx: Int
        val heightPx: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            widthPx = bounds.width()
            heightPx = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val real = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            widthPx = real.widthPixels
            heightPx = real.heightPixels
        }
        val xdpi = metrics.xdpi.takeIf { it > 0f } ?: metrics.densityDpi.toFloat()
        val ydpi = metrics.ydpi.takeIf { it > 0f } ?: metrics.densityDpi.toFloat()
        val xInch = widthPx / xdpi
        val yInch = heightPx / ydpi
        val diagonal = sqrt(xInch * xInch + yInch * yInch)
        return diagonal to metrics.densityDpi
    }

    private fun collectBatteryTemperature(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenthsCelsius = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return tenthsCelsius / 10f
    }

    private fun collectBatteryCycleCount(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent
            ?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            ?.takeIf { it >= 0 }
    }

    private fun estimateFullChargeMah(remainingChargeMah: Int?): Int? {
        if (remainingChargeMah == null || remainingChargeMah <= 0) return null
        val capacityPercent = batteryManager
            .intPropertyOrNull(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 1..100 }
            ?: return null
        return remainingChargeMah * 100 / capacityPercent
    }

    private fun collectThermalHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val headroom = runCatching { powerManager.getThermalHeadroom(0) }.getOrDefault(Float.NaN)
        return headroom.takeIf { !it.isNaN() }
    }

    private fun collectBootCount(): Int? =
        runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    private fun hasAbnormalExitSince(since: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val reasons = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        }.getOrNull().orEmpty()
        return reasons.any { info ->
            info.timestamp > since && info.reason in ABNORMAL_EXIT_REASONS
        }
    }

    private fun BatteryManager.intPropertyOrNull(propertyId: Int): Int? {
        val value = getIntProperty(propertyId)
        return if (value == Int.MIN_VALUE) null else value
    }

    private companion object {
        private val ABNORMAL_EXIT_REASONS = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
        )
    }
}
