package com.installer.toolbox.ui.screens.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.discovery.HostInfo
import com.installer.toolbox.core.network.discovery.PortServiceTable
import com.installer.toolbox.ui.components.LogPanel
import com.installer.toolbox.util.ExportReportButton
import com.installer.toolbox.util.ShareHelper
import kotlinx.coroutines.launch

/**
 * 设备发现页：开始扫描、进度条、结果列表、设备详情与保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiscoveryScreen(viewModel: DeviceDiscoveryViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val subnet by viewModel.subnet.collectAsState()
    val foundDevices by viewModel.foundDevices.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedHost by remember { mutableStateOf<HostInfo?>(null) }
    // 运行日志：扫描期间收集 root 命令执行过程（有日志自动展开面板，可手动开合）
    val logs by viewModel.logs.collectAsState()
    var showLogs by remember { mutableStateOf(false) }
    LaunchedEffect(logs) {
        if (logs.isNotEmpty()) showLogs = true
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设备发现") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // 网段信息 + 扫描按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "网段：$subnet",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { viewModel.startScan() },
                    enabled = uiState !is DiscoveryUiState.Scanning
                ) {
                    Text(if (uiState is DiscoveryUiState.Scanning) "扫描中…" else "开始扫描")
                }
            }
            Spacer(Modifier.height(8.dp))

            // root 模式说明（常驻显示：有 root 说明 ARP 能力，无 root 说明降级影响）
            val rootOk = remember { RootShell.isAvailable() }
            Text(
                text = if (rootOk) {
                    "已启用 root ARP 发现：可扫出端口全关/禁 ping 的设备（底层网络响应）。"
                } else {
                    "⚠ 未检测到 root 权限：无法用 ARP 方式发现设备，降级为 TCP 端口探测——端口不开放（全关/禁 ping）的设备扫不出来。建议在 root 环境扫描。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (rootOk) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "相关功能：发现摄像头可到『摄像头管理』直接配置；选中设备可到『流量审计』看流量；『局域网唤醒』可点选 MAC 远程开机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))

            // 进度条
            if (uiState is DiscoveryUiState.Scanning) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            // 运行日志面板（扫描过程 root 命令执行全可见）
            LogPanel(logs = logs, expanded = showLogs, onToggle = { showLogs = !showLogs })
            Spacer(Modifier.height(8.dp))

            when (val state = uiState) {
                is DiscoveryUiState.Error -> Text(
                    text = "扫描失败：${state.message}",
                    color = MaterialTheme.colorScheme.error
                )

                is DiscoveryUiState.Success -> {
                    Text(
                        text = "发现 ${state.devices.size} 台在线设备（点击查看详情）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    // 分享/导出设备清单（装维留档/给客户），共用一份报告文本
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ShareHelper.shareText(context, "设备发现报告", buildDeviceReportText(state.devices, subnet))
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("分享清单")
                        }
                        ExportReportButton(
                            fileName = "设备发现报告",
                            text = buildDeviceReportText(state.devices, subnet),
                            modifier = Modifier.height(48.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.devices, key = { it.ip }) { host ->
                            DeviceCard(host = host, onClick = { selectedHost = host })
                        }
                    }
                }

                is DiscoveryUiState.Scanning -> {
                    // 扫描中：实时展示已发现的设备（扫出一个显示一个，避免以为卡死）
                    Text("正在并发探测 1-254 主机…已发现 ${foundDevices.size} 台")
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(foundDevices, key = { it.ip }) { host ->
                            DeviceCard(host = host, onClick = {})
                        }
                    }
                }

                DiscoveryUiState.Idle -> {
                    Text(
                        text = "点击「开始扫描」探测局域网在线设备（TCP 探测常用端口，通过设备地址识别厂商）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    // 上次结果回看（弱网/离线）
                    if (viewModel.hasCachedResult()) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadCachedResult() }) {
                            Text("📋 查看上次扫描结果（${viewModel.cachedTimeText()}）")
                        }
                    }
                }
            }
        }
    }

    // 设备详情弹窗
    selectedHost?.let { host ->
        DeviceDetailDialog(
            host = host,
            onDismiss = { selectedHost = null },
            onSave = {
                viewModel.saveToFavorites(host)
                selectedHost = null
                scope.launch { snackbarHostState.showSnackbar("已保存到设备清单") }
            }
        )
    }
}

/** 单台设备卡片：IP / 厂商徽章 / MAC / 端口 */
@Composable
private fun DeviceCard(host: HostInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = host.ip,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                host.vendor?.let { vendor ->
                    // 厂商徽章
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = vendor,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "MAC：${host.mac ?: "未知"}",
                style = MaterialTheme.typography.bodySmall
            )
            if (host.randomMac) {
                Text(
                    text = "MAC 随机化（隐私保护，厂商无法识别）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (host.pcdn) {
                Text(
                    text = "⚠ 疑似 PCDN 设备（P2P 缓存/上行占用类，仅供参考）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            host.hostname?.let { name ->
                Text(text = "主机名：$name", style = MaterialTheme.typography.bodySmall)
            }
            if (host.openPorts.isNotEmpty()) {
                Text(
                    text = "在线端口：${host.openPorts.joinToString(", ") { port ->
                        PortServiceTable.nameOf(port)?.let { "$port($it)" } ?: port.toString()
                    }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 设备详情弹窗：展示完整信息 + 保存到清单 */
@Composable
private fun DeviceDetailDialog(
    host: HostInfo,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(host.ip) },
        text = {
            Column {
                Text("MAC：${host.mac ?: "未知"}")
                Text("厂商：${host.vendor ?: "未知"}")
                if (host.randomMac) {
                    Text("注：该设备开启了 MAC 随机化（隐私保护），厂商无法从 MAC 识别")
                }
                Text("主机名：${host.hostname ?: "未知"}")
                Text(
                    "在线端口：${host.openPorts.joinToString(", ") { port ->
                        PortServiceTable.nameOf(port)?.let { "$port($it)" } ?: port.toString()
                    }.ifBlank { "无" }}"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存到清单") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 设备发现报告文本（分享/导出共用一份） */
private fun buildDeviceReportText(devices: List<HostInfo>, subnet: String): String = buildString {
    appendLine("装维通 · 设备发现报告")
    appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
    appendLine("网段：$subnet")
    appendLine("设备数：${devices.size} 台")
    appendLine("----------------------")
    devices.forEachIndexed { i, h ->
        appendLine("${i + 1}. ${h.ip}  MAC:${h.mac ?: "未知"}  厂商:${h.vendor ?: "未知"}${h.hostname?.let { "  名称:$it" } ?: ""}${if (h.openPorts.isNotEmpty()) "  端口:${h.openPorts.joinToString(",")}" else ""}")
    }
}
