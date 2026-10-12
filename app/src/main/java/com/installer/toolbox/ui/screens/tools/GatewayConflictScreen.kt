package com.installer.toolbox.ui.screens.tools

import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.discovery.DeviceScanner
import com.installer.toolbox.core.network.scan.GatewayConflictDetector
import com.installer.toolbox.ui.components.LogPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网关 / IP 冲突检测页：
 * - 检测"一 MAC 多 IP"（同设备多 IP / IP 冲突）
 * - 检测"多网关"（局域网多个设备开放路由器端口 = 可能双路由/乱接，导致网不好）
 * 与"ARP 欺骗检测"（一 IP 多 MAC）互补。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayConflictScreen() {
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<GatewayConflictDetector.ConflictResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 运行日志：检测期间收集 root 命令执行过程（有日志自动展开面板，可手动开合）
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // root 状态提示（常驻：root 走 ARP 全量扫描，无 root 降级 TCP 探测）
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

    fun startDetect() {
        scanning = true
        error = null
        result = null
        scope.launch {
            // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
            logs = emptyList()
            showLogs = true
            RootShell.logListener = { line -> logs = logs + line }
            try {
                val r = withContext(Dispatchers.IO) {
                    // 1) 设备扫描（root 走 ARP 全量，无 root 降级 TCP）
                    val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
                    val devices = DeviceScanner(context).scan(
                        subnet = subnet,
                        rootRun = if (RootShell.isAvailable()) RootShell::run else null,
                        enableMdns = false
                    )
                    // 2) 当前网关（DHCP）
                    val wifi = context.applicationContext
                        .getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
                    val gateway = runCatching {
                        val g = wifi.dhcpInfo?.gateway ?: 0
                        if (g == 0) null else "${(g and 0xFF)}.${(g ushr 8) and 0xFF}.${(g ushr 16) and 0xFF}.${(g ushr 24) and 0xFF}"
                    }.getOrNull()
                    // 3) 本机 IP
                    val selfIp = runCatching {
                        val ip = wifi.connectionInfo?.ipAddress ?: 0
                        if (ip == 0) null else "${(ip and 0xFF)}.${(ip ushr 8) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 24) and 0xFF}"
                    }.getOrNull()
                    GatewayConflictDetector.detect(devices, gateway, selfIp)
                }
                result = r
            } catch (e: Exception) {
                error = "检测失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                RootShell.logListener = null
            }
            scanning = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("网关/IP 冲突检测") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                text = "检测两类冲突：①同一设备（MAC）出现在多个 IP（IP 冲突/双网段）②局域网内多个" +
                    "疑似网关（开放 53/67 端口 = 可能双路由/乱接，导致 IP 冲突、上网不稳）。" +
                    "与「ARP 欺骗检测」（一 IP 多 MAC）互补。root 时用 ARP 全量扫描，无 root 降级 TCP 探测。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            // root 状态提示（常驻）
            Text(
                text = if (rootOk) {
                    "已启用 root：ARP 全量扫描，可扫出端口全关/禁 ping 的设备，冲突判断更全。"
                } else {
                    "⚠ 未检测到 root 权限：降级为 TCP 端口探测扫描，端口全关/禁 ping 的设备扫不到（冲突可能漏报）。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (rootOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            // 弱信号警告（现场反馈：RSSI<-75 时扫描全超时无提示易误判设备下线）
            val rssi = remember { runCatching { (context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.connectionInfo?.rssi ?: 0 }.getOrDefault(0) }
            if (rssi < -75) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "⚠ 当前 Wi-Fi 信号弱（RSSI ${rssi}dBm）：扫描/探测可能超时或漏设备，结果仅供初步参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = ::startDetect,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (scanning) "检测中…" else "开始检测")
            }
            Spacer(Modifier.height(12.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            result?.let { r ->
                // 已知网关
                Text(
                    text = "当前网关：${r.knownGateway ?: "未知"}",
                    modifier = Modifier.onGloballyPositioned { c ->
                        val y = c.positionInParent().y
                        if (y != resultAreaY) scope.launch { resultAreaY = y } // defer 写 state，避免 Compose 1.7 并发修改崩溃
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                // 网关冲突提示（红色卡）
                r.conflictNote?.let { note ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = note,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // 疑似网关列表
                Text(
                    text = "疑似网关（${r.gateways.size} 个，开放 53/67/1900 端口）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                if (r.gateways.isEmpty()) {
                    Text(
                        text = "未发现其他疑似网关（局域网内带路由器开放端口的设备仅网关自身）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    r.gateways.forEach { gw ->
                        val isKnown = gw.ip == r.knownGateway
                        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = gw.ip + if (isKnown) "（当前网关）" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (isKnown) "正常" else "⚠ 疑似额外网关",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isKnown) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                                Text(
                                    text = "MAC：${gw.mac ?: "未知"}　厂商：${gw.vendor ?: "未知"}　开放端口：${gw.gatewayPorts.joinToString()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // 一 MAC 多 IP
                Text(
                    text = "一 MAC 多 IP（${r.macMultiIps.size} 个）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                if (r.macMultiIps.isEmpty()) {
                    Text(
                        text = "未发现同一设备占用多个 IP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    r.macMultiIps.forEach { m ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = "MAC ${m.mac}${m.vendor?.let { "（$it）" } ?: ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "占用 IP：${m.ips.joinToString("、")}（同设备多 IP / 疑似 IP 冲突）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                r.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 运行日志面板：检测过程 root 命令执行全可见（有日志自动展开，可手动开合）
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
}
