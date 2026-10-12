package com.installer.toolbox.ui.screens.wifi

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.installer.toolbox.core.network.wifi.WifiAnalyzer

/**
 * Wi-Fi 分析页：当前连接信息卡片 + 周边 AP 列表。
 * 包含 Android 13+ 权限请求流程（定位权限 + 通知权限）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiAnalyzeScreen(viewModel: WifiAnalyzeViewModel = viewModel()) {
    val connected by viewModel.connected.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val context = LocalContext.current

    // 权限请求：Android 13+ 扫描周边 Wi-Fi 需要定位权限，通知权限用于后续扫描通知
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.refreshConnected()
        if (result.values.any { it }) {
            viewModel.startScan()
        }
    }

    fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wi-Fi 分析") }) }
    ) { innerPadding ->
        // 整页统一滚动：连接卡 / 按钮 / 建议 / AP 列表全部在同一滚动流，底部不再被挤成小区域
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 当前连接信息卡片
            item { ConnectedCard(connected) }

            // 扫描按钮 + 权限说明
            item {
                Column {
                    Button(
                        onClick = {
                            if (WifiAnalyzer.hasScanPermission(context)) {
                                viewModel.refreshConnected()
                                viewModel.startScan()
                            } else {
                                requestPermissions()
                            }
                        },
                        enabled = uiState !is WifiUiState.Scanning
                    ) {
                        Text(if (uiState is WifiUiState.Scanning) "扫描中…" else "扫描周边 Wi-Fi")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Android 9+ 扫描周边 Wi-Fi 需要定位权限，且系统定位服务需保持开启",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "相关功能：想直接找路由器方位，用『Wi-Fi 雷达』。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            when (val s = uiState) {
                is WifiUiState.Success -> {
                    // 优化建议（同一滚动流内，滚走即可露出 AP 列表）
                    if (suggestions.isNotEmpty()) {
                        item {
                            Text(
                                text = "优化建议（${suggestions.size} 条）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(suggestions, key = { it.text }) { sug ->
                            val container = when (sug.level) {
                                "重要" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                "建议" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = container)
                            ) {
                                Text(
                                    text = "【${sug.level}】${sug.text}",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            text = "发现 ${s.aps.size} 个周边 AP",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(s.aps, key = { it.bssid }) { ap ->
                        ApCard(ap)
                    }
                }
                is WifiUiState.Error -> item {
                    Text(
                        text = "扫描失败：${s.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                is WifiUiState.Scanning -> item { Text("正在扫描周边 AP，请稍候…") }

                WifiUiState.Idle -> item {
                    Text(
                        text = "点击上方按钮扫描周边 AP，将同时生成当前连接的优化建议",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/** 当前连接信息卡片 */
@Composable
private fun ConnectedCard(connected: WifiAnalyzer.ConnectedWifi?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("当前连接", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (connected == null) {
                Text(
                    text = "未连接 Wi-Fi 或信息不可用（连接后可查看）",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text("Wi-Fi 名称：${connected.ssid}")
                Text("路由器 MAC：${connected.bssid}")
                Text("信号：${connected.rssiDbm} dBm（${WifiAnalyzer.signalLevel(connected.rssiDbm)} 格）")
                Text("链路速率：${connected.linkSpeedMbps} Mbps")
                Text(
                    "频段/信道：${connected.band} / CH${connected.channel}（${connected.frequency} MHz）"
                )
            }
        }
    }
}

/** 周边 AP 单张卡片（由外层 LazyColumn 的 items 调用） */
@Composable
private fun ApCard(ap: WifiAnalyzer.ApInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ap.ssid,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${ap.levelDbm} dBm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "路由器 MAC：${ap.bssid}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "信道：CH${ap.channel}（${ap.frequency} MHz）· 加密：${ap.security}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
