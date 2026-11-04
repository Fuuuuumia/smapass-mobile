package com.groupe.smapassmobile.ui.imei

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ImeiScreen (
    onSubmitted: () -> Unit,
    viewModel: ImeiScreenViewModel = viewModel(factory = ImeiScreenViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ImeiContent(
        uiState = uiState,
        onImeiChange = viewModel::onImeiChange,
        onSubmit = { viewModel.save(onSubmitted) },
    )
}

@Composable
private fun ImeiContent(
    uiState: ImeiUiState,
    onImeiChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val isComplete = uiState.imei.length == IMEI_LENGTH
    val hasError = isComplete && !uiState.isValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Text(
            text = "IMEIの登録",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "この端末を識別する IMEI 番号を入力してください。登録後は変更できません。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = uiState.imei,
            onValueChange = onImeiChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IMEI") },
            singleLine = true,
            isError = hasError,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    text = when {
                        hasError -> "チェックディジットが一致しません"
                        uiState.isValid -> "有効な IMEI です"
                        else -> "${uiState.imei.length} / $IMEI_LENGTH 桁"
                    },
                )
            },
        )

        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onSubmit, enabled = uiState.isValid) {
                Text("OK")
            }
        }
    }
}

private const val IMEI_LENGTH = 15
