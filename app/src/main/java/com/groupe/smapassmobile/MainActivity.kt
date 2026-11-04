package com.groupe.smapassmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.groupe.smapassmobile.data.DeviceRepository
import com.groupe.smapassmobile.ui.navigation.AppNavHost
import com.groupe.smapassmobile.ui.theme.SmapassmobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = DeviceRepository(applicationContext)

        setContent {
            SmapassmobileTheme {
                // 保存済み IMEI の有無で初回起動かどうかを一度だけ判定する。
                // 未入力なら IMEI 入力画面、入力済みなら Stat 画面から開始。
                val startDestination by produceState<String?>(initialValue = null) {
                    value = if (repository.imeiOnce().isNullOrBlank()) "imei" else "stat"
                }

                startDestination?.let { AppNavHost(startDestination = it) }
            }
        }
    }
}
