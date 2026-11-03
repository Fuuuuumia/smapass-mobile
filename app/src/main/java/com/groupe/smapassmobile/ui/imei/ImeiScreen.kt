package com.groupe.smapassmobile.ui.imei

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ImeiScreen (
    onSubmit: (String) -> Unit,
    viewModel: ImeiScreenViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ImeiContent(
        uiState = uiState,
        onImeiChange = viewModel::onImeiChange,
        onSubmit = { onSubmit(uiState.imei) },
    )
}

@Composable
private fun ImeiContent(
    uiState: ImeiUiState,
    onImeiChange: (String) -> Unit,
    onSubmit: () -> Unit,
){
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("IMEI番号を入力してください。(登録後に変更することはできません。)")
        OutlinedTextField(
            value = uiState.imei,
            label = {Text("IMEI")},
            onValueChange = onImeiChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSubmit, enabled = uiState.isValid ){Text("OK")}
    }
}



