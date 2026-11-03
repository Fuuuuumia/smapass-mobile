package com.groupe.smapassmobile.ui.imei

import android.R.attr.text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun ImeiScreen (onSubmit: (String) -> Unit) {
    var imei by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("IMEI番号を入力してください。(登録後に変更することはできません。)")
        OutlinedTextField(
            value = imei,
            label = {Text("IMEI")},
            onValueChange = { input ->
                if (input.length <= 15 && input.all { it.isDigit() }) imei = input
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = {onSubmit(imei)}, enabled = isImei(imei) ){Text("OK")}
    }
}
/*LuhnアルゴリズムによるIMEIチェック*/
fun isImei(imei: String):Boolean {
    if (imei.length != 15 || !imei.all { it.isDigit()}){
        return false
    }

    var luhnSum = 0
    for(i in 0 until 15){
        var luhnSeg = imei[i].digitToInt()
        if(i % 2 == 1){
            luhnSeg *= 2
            if (luhnSeg >= 10) luhnSeg -= 9
        }
        luhnSum += luhnSeg
    }
    return luhnSum % 10 == 0
}

