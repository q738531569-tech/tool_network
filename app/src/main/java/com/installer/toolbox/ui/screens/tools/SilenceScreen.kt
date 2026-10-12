package com.installer.toolbox.ui.screens.tools

import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.discovery.DeviceScanner
import com.installer.toolbox.core.network.discovery.HostInfo
import com.installer.toolbox.core.network.scan.LanSilencer
import com.installer.toolbox.core.network.wifi.WifiAnalyzer
import com.installer.toolbox.ui.components.LogPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 静默模式页（Wi-Fi 杀手原理，需 root + 内置 arpspoof/deauth）：
 * - 单台/全网**静默**：ARP 欺骗把目标流量引到手机后不转发 → 目标"断外网但连着 Wi-Fi"（有线/无线均有效，
 *   局域网内互通不受影响）；静默持续生效，点『取消静默』手动恢复（立即发送正确网关 ARP 应答）。
 * - 单台**踢下线**：发送 802.11 deauth 帧让目标认为已断开而自动断联（仅 Wi-Fi 无线设备有效）。
 *
 * 免责声明强确认：进入页面必须先勾选同意（责任由使用者承担），操作结果也携带声明。
 * 设备列表用 LazyColumn（key=ip），点按钮只重组对应项，不会整列表刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SilenceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 免责声明：未同意前不展示任何操作
    var agreed by remember { mutableStateOf(false) }
    val rootOk = remember { RootShell.isAvailable() }

    // 网络信息
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    fun dhcpIp(v: Int): String? =
        if (v == 0) null else {
            val n = Integer.reverseBytes(v)
            "${(n ushr 24) and 0xFF}.${(n ushr 16) and 0xFF}.${(n ushr 8) and 0xFF}.${n and 0xFF}"
        }
    val selfIp = remember { dhcpIp(wifi?.dhcpInfo?.ipAddress ?: 0) }
    val gateway = remember { dhcpIp(wifi?.dhcpInfo?.gateway ?: 0) }
    val connected = remember { WifiAnalyzer.currentConnection(context) }

    // 操作状态
    var devices by remember { mutableStateOf<List<HostInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    // 当前进行中的操作类型（""=空闲）：silence 静默 / stop 恢复 / kick 踢下线 / scan 扫描设备
    // 用于给按钮与进度提示提供明确的“进行中”文案
    var workingAction by remember { mutableStateOf("") }
    var silencingIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    // 静默强度模式：standard（标准）/ flood（洪泛）/ jam（信道阻塞）
    var mode by remember { mutableStateOf("standard") }
    // jam 模式副作用确认弹窗（确认后才真正执行静默）
    var jamConfirm by remember { mutableStateOf(false) }
    var jamTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    // 运行日志：扫描/静默/恢复/踢下线期间收集 root 命令执行过程（有日志自动展开面板，可手动开合）
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    // 焦点自动定位（动作进行时焦点跟随，每动作一次，之后可自由滑动）：
    // 本页用 LazyColumn，日志面板在页面底部：开始执行（日志出现第一条）自动滚到日志面板
    val listState = rememberLazyListState()
    var logItemIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(logs.isNotEmpty()) {
        if (logs.isNotEmpty() && logItemIndex >= 0) listState.animateScrollToItem(logItemIndex)
    }

    fun scanDevices() {
        scanning = true
        message = null
        scope.launch {
            // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
            logs = emptyList()
            showLogs = true
            RootShell.logListener = { line -> logs = logs + line }
            try {
                val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
                devices = try {
                    DeviceScanner(context).scan(
                        subnet = subnet,
                        rootRun = if (rootOk) RootShell::run else null,
                        enableMdns = false
                    )
                } catch (e: Exception) {
                    emptyList()
                }
                // 恢复进程内静默状态（页面重建后同步）
                silencingIps = LanSilencer.silencedTargets().filter { it in devices.map { d -> d.ip } }
            } finally {
                RootShell.logListener = null
            }
            scanning = false
        }
    }

    fun silence(targets: List<String>) {
        working = true
        workingAction = "silence"
        message = null
        scope.launch {
            // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
            logs = emptyList()
            showLogs = true
            RootShell.logListener = { line -> logs = logs + line }
            try {
                // 记录目标主机名（mDNS 名跨重连不变）——目标重连换 IP/MAC 后靠它找回并自动续静默
                val hostnames = devices.associate { it.ip to (it.hostname ?: "") }
                    .filterValues { it.isNotBlank() }
                val r = LanSilencer.start(context, RootShell::run, targets, gateway, mode, hostnames)
                message = r.message
                if (r.ok) silencingIps = LanSilencer.silencedTargets()
            } finally {
                RootShell.logListener = null
            }
            working = false
            workingAction = ""
        }
    }

    // 重连侦测：静默期间每 5 秒核对目标 MAC/IP——隐私 MAC 设备重连 Wi-Fi 会换 MAC/换 IP
    // 逃出静默，这里检测到后自动按新身份重新静默（页面打开期间持续生效）
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            val activeTargets = LanSilencer.silencedTargets()
            if (activeTargets.isEmpty() || working) continue
            val r = LanSilencer.refresh(context, RootShell::run, activeTargets, mode) { host ->
                // 按主机名全网扫描找回重连后的新 IP（mDNS 名跨重连不变）
                val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
                val list = try {
                    DeviceScanner(context).scan(
                        subnet = subnet,
                        rootRun = if (rootOk) RootShell::run else null,
                        enableMdns = true
                    )
                } catch (e: Exception) {
                    emptyList()
                }
                list.firstOrNull { h ->
                    val name = h.hostname ?: ""
                    name.contains(host, ignoreCase = true) || host.contains(name, ignoreCase = true)
                }?.ip
            }
            if (r.reSilenced.isNotEmpty()) {
                silencingIps = LanSilencer.silencedTargets()
                message = "🔄 检测到目标重连，已自动重新静默：${r.reSilenced.joinToString("、")}"
            } else if (r.lost.isNotEmpty()) {
                message = "⚠ 静默目标已离线/更换 IP：${r.lost.joinToString("、")}（重新连上后会自动尝试恢复静默）"
            }
        }
    }

    // 静默入口统一走本方法：jam（信道阻塞）模式先弹副作用确认，其余直接执行
    fun requestSilence(targets: List<String>) {
        if (mode == "jam") {
            jamTargets = targets
            jamConfirm = true
        } else {
            silence(targets)
        }
    }

    fun confirmJamSilence() {
        jamConfirm = false
        silence(jamTargets)
    }

    fun stopSilence(targets: List<String>) {
        working = true
        workingAction = "stop"
        scope.launch {
            // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
            logs = emptyList()
            showLogs = true
            RootShell.logListener = { line -> logs = logs + line }
            try {
                message = LanSilencer.stop(context, RootShell::run, targets)
                silencingIps = LanSilencer.silencedTargets()
            } finally {
                RootShell.logListener = null
            }
            working = false
            workingAction = ""
        }
    }

    fun kick(host: HostInfo) {
        working = true
        workingAction = "kick"
        message = null
        scope.launch {
            // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
            logs = emptyList()
            showLogs = true
            RootShell.logListener = { line -> logs = logs + line }
            try {
                val mac = host.mac
                val bssid = connected?.bssid
                message = when {
                    mac == null || mac.isBlank() -> "未获取到设备 MAC，无法踢下线（请先扫描设备）"
                    bssid == null || bssid == "未知" -> "未获取到当前路由器的 MAC 地址，无法踢下线（请确认已连接 Wi-Fi）"
                    else -> LanSilencer.kick(context, RootShell::run, mac, bssid)
                }
            } finally {
                RootShell.logListener = null
            }
            working = false
            workingAction = ""
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("静默模式") }) }
    ) { innerPadding ->
        if (!agreed) {
            // ============ 免责声明强确认 ============
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "⚠ 免责声明（务必阅读）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${LanSilencer.LEGAL_NOTE}\n\n" +
                                "● 静默（ARP 欺骗）：**双向欺骗**（同时骗目标与网关）——目标启用 ARP 防火墙也断外网（网关侧兜底）；有线/无线设备均有效，断目标**外网**（局域网内互通不受影响）；\n" +
                                "● 踢下线（802.11）：仅对 Wi-Fi 无线设备有效；\n" +
                                "● 仅当**路由器侧**也有静态绑定/ARP 防火墙（锁定 IP-MAC），或 AP 隔离阻断手机与目标通信时静默才无效；\n" +
                                "● 静默持续生效，点『取消静默』手动恢复（目标约 1 分钟内自动恢复）。\n\n" +
                                "请确认您对目标网络/设备拥有所有权或已获明确授权，继续使用即表示同意承担全部法律责任。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { agreed = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("我已知晓并同意，仅限授权设备使用") }
                    }
                }
            }
            return@Scaffold
        }

        // jam（信道阻塞）模式副作用强确认弹窗
        if (jamConfirm) {
            AlertDialog(
                onDismissRequest = { jamConfirm = false },
                title = { Text("信道阻塞模式：副作用确认") },
                text = {
                    Text(
                        "信道阻塞模式会让静默进程以忙循环占满空口（0ms 发包间隔），压制最狠——" +
                            "专门对付标准/洪泛都断不掉、断一下又恢复的顽固设备。" +
                            "副作用可能让同 Wi-Fi 的其他设备一起卡顿。" +
                            "仅限对已获授权的设备测试使用。确认继续？"
                    )
                },
                confirmButton = {
                    Button(onClick = ::confirmJamSilence) { Text("确认执行") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { jamConfirm = false }) { Text("取消") }
                }
            )
        }

        // ============ 已同意：操作区（LazyColumn 保证设备项独立重组） ============
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // root 状态提示（常驻）
            item {
                Text(
                    text = if (rootOk) {
                        "已启用 root：断外网（静默）与踢下线功能可用。"
                    } else {
                        "⚠ 未检测到 root 权限：静默/踢下线均不可用（需 root + 内置工具）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rootOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            // 相关功能互链
            item {
                Text(
                    text = "相关功能：建议先用『流量审计』或『ARP 欺骗检测』确认是哪台设备在偷跑，再静默处置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 静默强度模式选择（三档）
            item {
                Column {
                    Text(
                        text = "静默强度模式：",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == "standard",
                            onClick = { mode = "standard" },
                            label = { Text("标准") }
                        )
                        FilterChip(
                            selected = mode == "flood",
                            onClick = { mode = "flood" },
                            label = { Text("洪泛") }
                        )
                        FilterChip(
                            selected = mode == "jam",
                            onClick = { mode = "jam" },
                            label = { Text("信道阻塞") }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (mode) {
                            "flood" -> "洪泛：30ms 高频发包，压制更狠——标准档断不掉、或断一下又恢复的设备用这档（可能轻微影响同网段其他设备）"
                            "jam" -> "信道阻塞：忙循环占满空口、压制最狠，专门对付断一下又恢复的顽固设备；可能让同 Wi-Fi 其他设备一起卡，仅限授权测试！"
                            else -> "标准：同时断开目标与网关之间的连接（含 IPv6），多数设备够用；目标重连自动续静默"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 网络信息
            item {
                Text(
                    text = "本机：$selfIp　网关：$gateway　Wi-Fi 名称：${connected?.ssid ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 静默状态卡 + 全部停止
            if (silencingIps.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "⏸ 正在静默 ${silencingIps.size} 台设备（断其外网，仍连着 Wi-Fi）",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "🔍 重连监控中：目标若重连 Wi-Fi（隐私 MAC 轮换/换 IP），会自动按新身份重新静默",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { stopSilence(silencingIps) },
                                enabled = !working
                            ) { Text(if (workingAction == "stop") "恢复中…" else "全部恢复（取消静默）") }
                        }
                    }
                }
            }

            // 操作按钮行
            item {
                Row {
                    Button(
                        onClick = ::scanDevices,
                        enabled = !scanning && !working,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (scanning) "扫描中…" else "扫描在线设备") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // 全网静默：扫描全部在线设备，排除本机与网关
                            message = null
                            scope.launch {
                                working = true
                                workingAction = "scan"
                                // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
                                logs = emptyList()
                                showLogs = true
                                RootShell.logListener = { line -> logs = logs + line }
                                try {
                                    val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
                                    val list = try {
                                        DeviceScanner(context).scan(
                                            subnet = subnet,
                                            rootRun = if (rootOk) RootShell::run else null,
                                            enableMdns = false
                                        )
                                    } catch (e: Exception) {
                                        emptyList()
                                    }
                                    val targets = list.map { it.ip }
                                        .filter { it != selfIp && it != gateway }
                                    working = false
                                    workingAction = ""
                                    if (targets.isEmpty()) {
                                        message = "未扫描到可静默的设备（排除本机与网关后为空）"
                                    } else {
                                        requestSilence(targets)
                                    }
                                } finally {
                                    RootShell.logListener = null
                                }
                            }
                        },
                        enabled = !working,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (workingAction) {
                                "scan" -> "扫描设备中…"
                                "silence" -> "静默中…"
                                else -> "全网静默"
                            }
                        )
                    }
                }
            }

            // 进行中进度反馈（静默/恢复/踢下线等操作执行时显示）
            if (working) {
                item {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when (workingAction) {
                                "stop" -> "正在恢复设备网络…"
                                "kick" -> "正在将设备踢下线…"
                                "scan" -> "正在扫描设备…"
                                else -> "正在执行静默…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 结果提示
            message?.let { msg ->
                item {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 运行日志面板（扫描/静默/恢复/踢下线过程 root 命令执行全可见）
            item(key = "log_panel") {
                androidx.compose.foundation.layout.Box(
                    Modifier.onGloballyPositioned {
                        // 记录日志面板在列表中的 item 索引（懒加载时按 key 从 layoutInfo 取，供开始执行时滚动）；
                        // defer 写 state，避免 Compose 1.7 并发修改崩溃
                        val idx = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == "log_panel" }?.index ?: return@onGloballyPositioned
                        if (idx != logItemIndex) scope.launch { logItemIndex = idx }
                    }
                ) {
                    LogPanel(logs = logs, expanded = showLogs, onToggle = { showLogs = !showLogs })
                }
            }

            // 设备列表标题 / 空提示
            item {
                if (devices.isEmpty()) {
                    Text(
                        text = "点击「扫描在线设备」列出局域网设备，再对目标设备执行静默（断外网）或踢下线（仅无线设备有效）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        text = "在线设备（${devices.size} 台）：",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 设备列表（key=ip 独立重组，点按钮不刷新整列表）
            items(devices, key = { it.ip }) { host ->
                val silenced = host.ip in silencingIps
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = host.ip,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            when {
                                host.ip == selfIp -> Text("（本机）", style = MaterialTheme.typography.bodySmall)
                                host.ip == gateway -> Text("（网关）", style = MaterialTheme.typography.bodySmall)
                                silenced -> Text(
                                    "静默中",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Text(
                            text = "MAC：${host.mac ?: "未知"}　厂商：${host.vendor ?: "未知"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (host.ip != selfIp) {
                            Spacer(Modifier.height(4.dp))
                            Row {
                                if (silenced) {
                                    OutlinedButton(
                                        onClick = { stopSilence(listOf(host.ip)) },
                                        enabled = !working,
                                        modifier = Modifier.weight(1f)
                                    ) { Text(if (workingAction == "stop") "恢复中…" else "取消静默") }
                                } else {
                                    OutlinedButton(
                                        onClick = { requestSilence(listOf(host.ip)) },
                                        enabled = !working,
                                        modifier = Modifier.weight(1f)
                                    ) { Text(if (workingAction == "silence") "静默中…" else "静默（断外网）") }
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { kick(host) },
                                    enabled = !working,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (workingAction == "kick") "踢下线中…" else "踢下线（仅无线）") }
                            }
                        }
                    }
                }
            }

            // 底部说明
            item {
                Text(
                    text = "说明：静默 = 双向 ARP 欺骗（骗目标：外网流量引到本机丢弃；骗网关：下行流量引到本机丢弃）——目标启用 ARP 防火墙也断外网，仅路由器侧静态绑定/AP 隔离时可能无效；踢下线 = 发送断连指令让其自动断开 Wi-Fi（仅无线设备，多数 Android 芯片不支持注入）。${LanSilencer.ARP_GUARD_NOTE}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
