package com.installer.toolbox.ui.screens.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.scan.ArpSpoofDetector
import com.installer.toolbox.core.network.scan.ArpSpoofDetector.ArpDetectResult
import com.installer.toolbox.ui.components.LogPanel
import com.installer.toolbox.util.ExportReportButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ARP 欺骗检测页：抓取局域网 ARP 包，统计同一 IP 的多个 MAC（IP-MAC 冲突），
 * 并展示网关 MAC 供核对。需要 root 权限（通过 RootShell 执行 tcpdump）。
 */
@Composable
fun ArpDetectScreen() {
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ArpDetectResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var durationSecText by remember { mutableStateOf("8") }
    // 运行日志：抓包期间收集 root 命令执行过程（有日志自动展开面板，可手动开合）
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // root 状态提示（常驻：有 root 说明抓包能力，无 root 说明降级影响）
    val rootOk = remember { RootShell.isAvailable() }
    // 焦点自动定位（动作进行时焦点跟随，每动作一次，之后可自由滑动）：
    // 检测开始（日志出现）→ 自动滚到运行日志面板；检测完成（结果出现）→ 自动滚到结果区
    val scrollState = rememberScrollState()
    var logAreaY by remember { mutableStateOf(0f) }
    var resultAreaY by remember { mutableStateOf(0f) }
    LaunchedEffect(logs.isNotEmpty()) {
        if (logs.isNotEmpty() && logAreaY > 0f) scrollState.animateScrollTo(logAreaY.toInt())
    }
    LaunchedEffect(result != null) {
        if (result != null && resultAreaY > 0f) scrollState.animateScrollTo(resultAreaY.toInt())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "ARP 欺骗检测",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "抓取局域网 ARP 包，统计同一 IP 是否出现多个 MAC（IP-MAC 冲突），并对比网关 MAC。需要 root 权限；无 root 时降级为 ARP 表对比（精度较低）。目标设备启用 ARP 防护/静态绑定或路由器防火墙过滤时可能检测不到（结果会提示）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        // root 状态提示（常驻）
        Text(
            text = if (rootOk) {
                "已启用 root：可抓包查出同一 IP 被多个设备冒用的异常（IP-MAC 冲突）和网关 MAC 突变。"
            } else {
                "⚠ 未检测到 root 权限：无法抓 ARP 包，只能降级为 ARP 表两次对比（精度低、容易漏报）。可改用『网关/IP 冲突检测』查“一 MAC 多 IP”作互补排查。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (rootOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        // 弱信号警告（现场反馈：RSSI<-75 时扫描全超时无提示易误判设备下线）
        val rssi = remember { runCatching { (context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.connectionInfo?.rssi ?: 0 }.getOrDefault(0) }
        if (rssi < -75) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "⚠ 当前 Wi-Fi 信号弱（RSSI ${rssi}dBm）：抓包/扫描可能超时或漏包，结果仅供初步参考。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "相关功能：确认被劫持后，可用『静默模式』做处置（同类技术）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))

        // 抓包时长输入（默认 8 秒，可自定义）
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = durationSecText,
                onValueChange = { durationSecText = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("抓包时长（秒）") },
                supportingText = { Text("范围 3-30，默认 8") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !scanning,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            // 抓包中实时剩余秒数（倒计时反馈）
            var remainSec by remember { mutableStateOf(0) }
            val currentScanning by rememberUpdatedState(scanning)
            LaunchedEffect(scanning) {
                if (scanning) {
                    remainSec = (durationSecText.toIntOrNull()?.coerceIn(3, 30) ?: 8)
                    while (currentScanning) {
                        delay(1000)
                        remainSec = (remainSec - 1).coerceAtLeast(0)
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        scanning = true
                        error = null
                        // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
                        logs = emptyList()
                        showLogs = true
                        RootShell.logListener = { line -> logs = logs + line }
                        try {
                            val sec = (durationSecText.toIntOrNull()?.coerceIn(3, 30) ?: 8) * 1000L
                            result = try {
                                ArpSpoofDetector.detect(
                                    context.applicationContext,
                                    durationMs = sec,
                                    rootRun = RootShell::run
                                )
                            } catch (e: Exception) {
                                error = "检测失败：${e.message ?: e.javaClass.simpleName}"
                                null
                            }
                        } finally {
                            RootShell.logListener = null
                        }
                        scanning = false
                    }
                },
                enabled = !scanning,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(if (scanning) "检测中…剩余 $remainSec 秒" else "开始检测")
            }
        }
        Spacer(Modifier.height(12.dp))

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        result?.let { r ->
            // 网关 MAC
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .onGloballyPositioned { c ->
                        val y = c.positionInParent().y
                        if (y != resultAreaY) scope.launch { resultAreaY = y } // defer 写 state，避免 Compose 1.7 并发修改崩溃
                    }
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = "网关 MAC",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = r.gatewayMac ?: "未知（未获取到网关 IP/MAC）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "IP-MAC 冲突（${r.conflicts.size}）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            if (r.conflicts.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        text = "未发现 IP-MAC 冲突",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                r.conflicts.forEach { c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = "IP：${c.ip}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "MAC：${c.macs.joinToString("  /  ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    text = r.note ?: "",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 导出报告（装维留档）
            Spacer(Modifier.height(10.dp))
            ExportReportButton(
                fileName = "ARP欺骗检测报告",
                text = buildArpReportText(r),
                modifier = Modifier.height(48.dp)
            )
        }

        // 运行日志面板：抓包过程 root 命令执行全可见（有日志自动展开，可手动开合）
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.Box(
            Modifier.onGloballyPositioned { c ->
                val y = c.positionInParent().y
                if (y != logAreaY) scope.launch { logAreaY = y } // defer 写 state，避免 Compose 1.7 并发修改崩溃
            }
        ) {
            LogPanel(logs = logs, expanded = showLogs, onToggle = { showLogs = !showLogs })
        }
    }
}

/** ARP 欺骗检测报告文本（导出用）：标题 + 网关 MAC + 冲突列表 + 结论 */
private fun buildArpReportText(r: ArpDetectResult): String = buildString {
    appendLine("装维通 · ARP 欺骗检测报告")
    appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
    appendLine("网关 MAC：${r.gatewayMac ?: "未知（未获取到网关 IP/MAC）"}")
    appendLine("IP-MAC 冲突：${r.conflicts.size} 个")
    if (r.conflicts.isEmpty()) {
        appendLine("未发现 IP-MAC 冲突")
    } else {
        r.conflicts.forEach { c ->
            appendLine("- IP：${c.ip}　MAC：${c.macs.joinToString("  /  ")}")
        }
    }
    r.note?.let { appendLine("说明：$it") }
}
