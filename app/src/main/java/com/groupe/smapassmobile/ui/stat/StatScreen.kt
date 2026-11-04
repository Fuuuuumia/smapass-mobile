package com.groupe.smapassmobile.ui.stat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatScreen(
    viewModel: StatScreenViewModel = viewModel(factory = StatScreenViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    StatContent(uiState = uiState, onRefresh = viewModel::refresh)
}

private val TABS = listOf("状態", "スペック")

@Composable
private fun StatContent(
    uiState: StatUiState,
    onRefresh: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var infoText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        DeviceHeader(
            spec = uiState.spec,
            status = uiState.status,
            onRefresh = onRefresh,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )

        TabRow(selectedTabIndex = selectedTab) {
            TABS.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (selectedTab) {
                0 -> StatusTab(
                        status = uiState.status,
                        isLoading = uiState.isLoading,
                        onInfo = { infoText = it },
                    )
                else -> SpecTab(
                            spec = uiState.spec,
                            onInfo = { infoText = it }
                    )
            }
            Spacer(Modifier.padding(4.dp))
        }
    }

    infoText?.let { text ->
        AlertDialog(
            onDismissRequest = { infoText = null },
            confirmButton = {
                TextButton(onClick = { infoText = null }) { Text("閉じる") }
            },
            text = { Text(text) },
        )
    }
}

@Composable
private fun SpecTab(
    spec: DeviceSpec?,
    onInfo: (String) -> Unit,
) {
    if (spec == null) {
        LoadingBlock()
        return
    }
    StatSection(title = "ハードウェア") {
        MetricGrid(
            listOf(
                Metric("SoC", spec.soc ?: "取得不可"),
                Metric("CPUコア数", "${spec.cpuCores} コア"),
                Metric("RAM", formatBytes(spec.totalRamBytes)),
                Metric("ストレージ", formatBytes(spec.totalStorageBytes)),
            ),
            onInfo = onInfo,
        )
    }
    StatSection(title = "ディスプレイ") {
        MetricGrid(
            listOf(
                Metric("画面サイズ", "%.1f インチ".format(spec.screenSizeInch)),
                Metric("画面密度", "${spec.densityDpi} dpi"),
            ),
            onInfo = onInfo,
        )
    }
    StatSection(title = "識別情報") {
        MetricGrid(
            listOf(
                Metric(
                    label = "IMEI",
                    value = spec.imei ?: "取得不可",
                ),
            ),
            onInfo = onInfo,
        )
    }
}

@Composable
private fun StatusTab(
    status: DeviceStatus?,
    isLoading: Boolean,
    onInfo: (String) -> Unit,
) {
    if (status == null) {
        if (isLoading) LoadingBlock()
        return
    }
    StatSection(title = "発熱") {
        MetricGrid(
            listOf(
                Metric("バッテリー温度", "%.1f℃".format(status.batteryTemperature)),
                Metric(
                    label = "熱的余裕",
                    value = status.thermalHeadroom?.let { "%.2f".format(it) } ?: "—",
                    info = "0〜1 の値で、1.0 に近いほど発熱で性能が制限されやすい状態です。",
                ),
            ),
            onInfo = onInfo,
        )
    }
    StatSection(title = "バッテリー") {
        MetricGrid(
            listOf(
                Metric("推定残量", status.remainingChargeMah?.let { "$it mAh" } ?: "取得不可"),
                Metric(
                    label = "推定満充電容量",
                    value = status.estimatedFullChargeMah?.let { "$it mAh" } ?: "取得不可",
                    info = "現在の電荷量と充電率から推定した、満充電時の容量です。",
                ),
                Metric(
                    label = "充電サイクル数",
                    value = status.batteryCycleCount?.let { "$it 回" } ?: "取得不可",
                    info = "Android 14 以降の端末でのみ取得できます。",
                ),
            ),
            onInfo = onInfo,
        )
    }
    StatSection(title = "安定性") {
        MetricGrid(
            listOf(
                Metric("累計起動回数", status.bootCount?.let { "$it 回" } ?: "取得不可"),
                Metric(
                    label = "異常終了",
                    value = if (status.hasAbnormalExit) "あり" else "なし",
                    info = "クラッシュ・ANR・ネイティブクラッシュ・メモリ不足による終了の記録有無です。",
                ),
            ),
            onInfo = onInfo,
        )
    }
    StatSection(title = "OS") {
        MetricGrid(
            listOf(
                Metric("Androidバージョン", status.osVersion),
                Metric("APIレベル", "${status.sdk}"),
            ),
            onInfo = onInfo,
        )
    }
}

@Composable
private fun DeviceHeader(
    spec: DeviceSpec?,
    status: DeviceStatus?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = spec?.let { "${it.manufacturer} ${it.model}" } ?: "端末情報を取得中…",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        spec?.deviceName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status?.let { "最終更新 ${formatTime(it.collectedAt)}" } ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRefresh) { Text("更新") }
        }
    }
}

@Composable
private fun StatSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

private data class Metric(
    val label: String,
    val value: String,
    val info: String? = null,
)

@Composable
private fun MetricGrid(
    items: List<Metric>,
    onInfo: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { metric ->
                    MetricTile(
                        metric = metric,
                        onInfo = onInfo,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(
    metric: Metric,
    onInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            metric.info?.let { info ->
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onInfo(info) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "i",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = metric.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "取得不可"
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
