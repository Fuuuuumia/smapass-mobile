package com.groupe.smapassmobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.groupe.smapassmobile.ui.imei.ImeiScreen
import com.groupe.smapassmobile.ui.stat.StatScreen
@Composable
fun AppNavHost(startDestination: String){
    val navController = rememberNavController()
    NavHost(navController, startDestination = startDestination){
        composable("imei"){
            ImeiScreen(onSubmitted = {
                navController.navigate("stat"){
                    popUpTo("imei"){
                        inclusive = true
                    }
                }
            })
        }
        composable("stat"){
            StatScreen()
        }
    }
}