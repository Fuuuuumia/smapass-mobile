package com.groupe.smapassmobile.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.groupe.smapassmobile.BuildConfig
import com.groupe.smapassmobile.data.remote.Visibility

// Web の証明書ページ（/certificates/{端末UUID}）。接続先は gradle.properties の `smapass.webBaseUrl`。
private fun certificateUrl(deviceId: String): String = "${BuildConfig.WEB_BASE_URL}/certificates/$deviceId"

/**
 * Stat 画面の「設定」タブの中身。登録状態・公開設定・計測ログ送信・引き継ぎ画面への導線・
 * Web ページへのリンクを提供する。
 */
@Composable
fun SettingsPane(
    onOpenMigration: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isPublic = uiState.visibility == Visibility.PUBLIC

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "設定",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        RegistrationCard(uiState)

        if (!uiState.isRegistered) {
            Button(
                onClick = viewModel::register,
                enabled = !uiState.isBusy && uiState.imei != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("この端末をサーバーに登録")
            }
        } else {
            VisibilityCard(
                isPublic = isPublic,
                enabled = !uiState.isBusy && uiState.visibility != null,
                onChange = viewModel::setPublic,
            )
            UploadCard(
                lastUploadResult = uiState.lastUploadResult,
                enabled = !uiState.isBusy,
                onSendNow = viewModel::sendNow,
            )
        }

        // 引き継ぎは独立した画面に分ける（ここはその入口ボタンのみ）。
        OutlinedButton(
            onClick = onOpenMigration,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("引き継ぎ（端末の移行）")
        }

        OutlinedButton(
            onClick = {
                uiState.deviceId?.let { id ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(certificateUrl(id))))
                    }
                }
            },
            // 非公開の端末は Web から参照できない（公開 API が 404 を返す）。
            enabled = uiState.deviceId != null && isPublic,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("証明書を Web で見る")
        }
        if (uiState.isRegistered && !isPublic) {
            HintText("Web で表示するには「公開」に切り替えてください。")
        }

        BusyAndMessage(isBusy = uiState.isBusy, message = uiState.message)
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 引き継ぎ（端末の移行）画面。別画面として分離。
 *
 * 秘密鍵はハードウェアから取り出せないため「鍵のコピー」はできない。
 * 代わりに、この端末の登録を終了してから、新しい端末で登録し直す方式を案内する。
 */
@Composable
fun MigrationScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 戻る") }
        }
        Text(
            text = "引き継ぎ",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        InfoCard(
            title = "秘密鍵はコピーできません",
            body = "この端末の鍵はセキュリティハードウェア（Android Keystore）内にあり、" +
                "取り出すことはできません。引き継ぎは次の手順で行います。\n\n" +
                "1. この端末の登録を解除する（下のボタン）\n" +
                "2. 新しい端末でアプリを入れ、同じ IMEI で登録し直す\n\n" +
                "解除すると、この端末からの送信はできなくなります。計測履歴は端末に結び付いたまま残り、" +
                "新しい登録で続きから記録されます。新しい登録の公開設定は非公開から始まります。",
        )

        RegistrationCard(uiState)

        Button(
            onClick = { showConfirm = true },
            enabled = uiState.isRegistered && !uiState.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("この端末の登録を解除して引き継ぐ")
        }

        BusyAndMessage(isBusy = uiState.isBusy, message = uiState.message)
        Spacer(Modifier.height(4.dp))
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("登録を解除しますか？") },
            text = {
                Text("この端末の登録・鍵・認証情報を終了します。元に戻せません。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        viewModel.unregister()
                    },
                ) { Text("解除する") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun RegistrationCard(uiState: SettingsUiState) {
    CardColumn {
        StatusRow(
            label = "登録状態",
            value = if (uiState.isRegistered) "登録済み" else "未登録",
            emphasize = uiState.isRegistered,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusRow(label = "IMEI", value = uiState.imei ?: "未設定")
        if (uiState.isRegistered && uiState.deviceId != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "端末ID",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = uiState.deviceId,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun VisibilityCard(
    isPublic: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    CardColumn {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "公開する",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (isPublic) "Web で状態と履歴を閲覧できます" else "非公開（自分だけ）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = isPublic, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

@Composable
private fun UploadCard(
    lastUploadResult: String?,
    enabled: Boolean,
    onSendNow: () -> Unit,
) {
    CardColumn {
        Text(
            text = "計測データの送信",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "アプリ表示中、状態は1時間ごと、スペックは変化したときに自動で送信します。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "前回: ${lastUploadResult ?: "まだ送信していません"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = onSendNow,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("今すぐ送信")
        }
    }
}

@Composable
private fun CardColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (emphasize) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
) {
    CardColumn {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BusyAndMessage(isBusy: Boolean, message: String?) {
    if (isBusy) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
    }
    message?.let {
        CardColumn {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
