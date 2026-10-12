package com.installer.toolbox.ui.screens.diagnose

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.installer.toolbox.core.network.diagnose.DnsTool
import com.installer.toolbox.core.network.diagnose.PingTool
import com.installer.toolbox.core.network.diagnose.PortScanner
import com.installer.toolbox.core.network.diagnose.TracerouteTool
import com.installer.toolbox.ui.components.LogPanel
import com.installer.toolbox.util.ExportReportButton
import com.installer.toolbox.util.ShareHelper

/**
 * 网络诊断页：目标输入 + 四个 Tab（Ping / Traceroute / DNS / 端口扫描）。
 * 执行完成后自动保存报告到 Room。
 */
@Composable
fun DiagnoseScreen(viewModel: DiagnoseViewModel = viewModel()) {
    val target by viewModel.target.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Ping", "Traceroute", "DNS", "端口扫描")
    // 运行日志：诊断期间收集 root 命令执行过程（有日志自动展开面板，可手动开合）
    val logs by viewModel.logs.collectAsState()
    var showLogs by remember { mutableStateOf(false) }
    LaunchedEffect(logs) {
        if (logs.isNotEmpty()) showLogs = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 目标输入框
        OutlinedTextField(
            value = target,
            onValueChange = viewModel::setTarget,
            label = { Text("目标 IP / 域名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        // Tab 切换
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "相关功能：想持续测一段时间看稳定性，用『弱网检测』。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))

        // 运行日志面板（诊断过程 root 命令执行全可见；置于 Tab 内容上方，避免被结果列表挤占）
        LogPanel(logs = logs, expanded = showLogs, onToggle = { showLogs = !showLogs })
        Spacer(Modifier.height(8.dp))

        when (selectedTab) {
            0 -> PingTab(viewModel)
            1 -> TraceTab(viewModel)
            2 -> DnsTab(viewModel)
            else -> PortTab(viewModel)
        }
    }
}

/** Ping 结果展示 */
@Composable
private fun PingTab(viewModel: DiagnoseViewModel) {
    val context = LocalContext.current
    val state by viewModel.pingState.collectAsState()
    val pingPort by viewModel.pingPort.collectAsState()
    Column {
        // 端口选择：80（HTTP）/ 443（HTTPS）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = pingPort == 80,
                onClick = { viewModel.setPingPort(80) },
                label = { Text("80 (HTTP)") }
            )
            FilterChip(
                selected = pingPort == 443,
                onClick = { viewModel.setPingPort(443) },
                label = { Text("443 (HTTPS)") }
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.runPing() },
            enabled = state !is DiagUiState.Loading
        ) {
            Text(if (state is DiagUiState.Loading) "Ping 中…" else "开始 Ping（端口 $pingPort）")
        }
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            is DiagUiState.Success -> {
                val r = s.data
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("目标：${r.target}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text("发送/接收：${r.sent} / ${r.received}")
                        Text(
                            text = "丢包率：${r.lossPercent}%",
                            color = if (r.lossPercent > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Text("平均：${r.avgMs ?: "-"} ms")
                        Text("最小：${r.minMs ?: "-"} ms")
                        Text("最大：${r.maxMs ?: "-"} ms")
                        // 分享/导出：共用 ViewModel 的 formatPing（同一份报告文本）
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    ShareHelper.shareText(context, "Ping 诊断结果", viewModel.formatPing(r))
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("分享结果")
                            }
                            ExportReportButton(
                                fileName = "Ping诊断报告",
                                text = viewModel.formatPing(r),
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    }
                }
            }

            is DiagUiState.Error -> Text(
                text = "Ping 失败：${s.message}",
                color = MaterialTheme.colorScheme.error
            )

            is DiagUiState.Loading -> Text("正在 Ping，请稍候…")

            DiagUiState.Idle -> Text(
                text = "对目标做 TCP connect 计时（可选 80/443 端口），统计丢包与耗时",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** Traceroute 结果展示 */
@Composable
private fun TraceTab(viewModel: DiagnoseViewModel) {
    val state by viewModel.traceState.collectAsState()
    Column {
        Button(
            onClick = { viewModel.runTrace() },
            enabled = state !is DiagUiState.Loading
        ) {
            Text(if (state is DiagUiState.Loading) "追踪中…" else "开始 Traceroute")
        }
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            is DiagUiState.Success -> {
                val target by viewModel.target.collectAsState()
                ExportReportButton(
                    fileName = "Traceroute诊断报告",
                    text = buildString {
                        appendLine("装维通 · Traceroute 诊断报告")
                        appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                        appendLine("目标：$target")
                        append(viewModel.formatTrace(s.data))
                    },
                    modifier = Modifier.height(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(s.data, key = { it.hop }) { hop ->
                        val ip = hop.ip.ifBlank { "未知" }
                        Text(
                            text = "第 ${hop.hop} 跳：$ip  ${hop.elapsedMs}ms  " +
                                if (hop.reached) "✓ 到达" else "… 超时",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hop.reached) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                }
            }

            is DiagUiState.Error -> Text(
                text = "Traceroute 失败：${s.message}",
                color = MaterialTheme.colorScheme.error
            )

            is DiagUiState.Loading -> Text("正在逐跳探测，请稍候…")

            DiagUiState.Idle -> Text(
                text = "逐跳 TCP connect 变通实现（无 root 无法获取中间路由 IP，仅确认可达性与耗时）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** DNS 结果展示 */
@Composable
private fun DnsTab(viewModel: DiagnoseViewModel) {
    val state by viewModel.dnsState.collectAsState()
    Column {
        Button(
            onClick = { viewModel.runDns() },
            enabled = state !is DiagUiState.Loading
        ) {
            Text(if (state is DiagUiState.Loading) "查询中…" else "开始 DNS 查询")
        }
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            is DiagUiState.Success -> {
                val r: DnsTool.DnsResult = s.data
                val target by viewModel.target.collectAsState()
                ExportReportButton(
                    fileName = "DNS诊断报告",
                    text = buildString {
                        appendLine("装维通 · DNS 诊断报告")
                        appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                        appendLine("目标：$target")
                        append(viewModel.formatDns(r))
                    },
                    modifier = Modifier.height(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "状态：${r.status} · 耗时 ${r.elapsedMs}ms · ${r.records.size} 条记录",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                if (r.records.isEmpty()) {
                    Text("无记录")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(r.records, key = { "${it.type}-${it.name}-${it.rdata}" }) { rec ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(
                                        text = "${rec.type}  ${rec.name}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = rec.rdata,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is DiagUiState.Error -> Text(
                text = "DNS 查询失败：${s.message}",
                color = MaterialTheme.colorScheme.error
            )

            is DiagUiState.Loading -> Text("正在查询 A/AAAA/MX/NS/TXT，请稍候…")

            DiagUiState.Idle -> Text(
                text = "通过 dnsjava 查询 A / AAAA / MX / NS / TXT 记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 端口扫描结果展示 */
@Composable
private fun PortTab(viewModel: DiagnoseViewModel) {
    val state by viewModel.portState.collectAsState()
    Column {
        Button(
            onClick = { viewModel.runPortScan() },
            enabled = state !is DiagUiState.Loading
        ) {
            Text(if (state is DiagUiState.Loading) "扫描中…" else "开始端口扫描")
        }
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            is DiagUiState.Success -> {
                val r: PortScanner.PortScanResult = s.data
                val target by viewModel.target.collectAsState()
                ExportReportButton(
                    fileName = "端口扫描报告",
                    text = buildString {
                        appendLine("装维通 · 端口扫描报告")
                        appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                        appendLine("目标：$target")
                        append(viewModel.formatPortScan(r))
                    },
                    modifier = Modifier.height(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "扫描 ${r.scannedCount} 个端口 · 耗时 ${r.elapsedMs}ms · 开放 ${r.openPorts.size} 个",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                if (r.openPorts.isEmpty()) {
                    Text("未发现开放端口")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(r.openPorts, key = { it.port }) { p ->
                            Row {
                                Text(
                                    text = "端口 ${p.port}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = p.service ?: "未知服务",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            is DiagUiState.Error -> Text(
                text = "端口扫描失败：${s.message}",
                color = MaterialTheme.colorScheme.error
            )

            is DiagUiState.Loading -> Text("正在并发扫描 1-1024 端口，请稍候…")

            DiagUiState.Idle -> Text(
                text = "TCP connect 并发扫描 1-1024 常用端口（含 8080 等高位端口）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
