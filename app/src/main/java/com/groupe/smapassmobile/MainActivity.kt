package com.groupe.smapassmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.groupe.smapassmobile.ui.navigation.AppNavHost
import com.groupe.smapassmobile.ui.theme.SmapassmobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SmapassmobileTheme {
                AppNavHost(startDestination = "imei")
            }
        }
    }
}