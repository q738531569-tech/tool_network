package com.installer.toolbox.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.scan.PcdnNetworkScanner
import com.installer.toolbox.core.network.scan.PcdnNetworkScanner.NetworkScanResult
import com.installer.toolbox.core.network.scan.TrafficAudit
import com.installer.toolbox.ui.components.LogPanel
import com.installer.toolbox.util.ExportReportButton
import com.installer.toolbox.util.ResultCache
import com.installer.toolbox.util.ShareHelper
import kotlinx.coroutines.launch

/**
 * PCDN 全网检测页（两阶段，检出从严）：
 * 阶段1 特征层宽进列出可疑设备（特征端口/主机名/多 IP/DHT 协议/UPnP 映射，摄像头排除）；
 * 阶段2 对可疑设备做 ARP 中间人流量审计综合评分确认（需 root + 内置 arpspoof + 授权），
 * 点击设备卡片可单独审计确认。
 */
@Composable
fun PcdnDetectScreen(
    /** 跳转流量审计页：参数为目标 IP 与是否借道模式（二级路由卡片传 true） */
    onNavigateToAudit: (String, Boolean) -> Unit = { _, _ -> }
) {
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NetworkScanResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var auditSecText by remember { mutableStateOf("15") }
    // 手动单台审计状态：非 null 表示正在审计该 IP（点击卡片触发）
    var auditingIp by remember { mutableStateOf<String?>(null) }
    // 运行日志：扫描/审计期间收集 root 命令执行过程（有日志自动展开面板，可手动开合）
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 上次检测结果回看状态
    var cachedPcdnResult by remember { mutableStateOf<String?>(null) }
    var cachedPcdnTime by remember { mutableStateOf("") }
    // root 状态提示（常驻：阶段2 流量审计依赖 root，无 root 自动跳过）
    val rootOk = remember { RootShell.isAvailable() }
    // 焦点自动定位（用户要求：软件动作在哪焦点到哪，每动作一次，之后可自由滑动）：
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
            text = "PCDN 全网检测",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        // 两阶段原理说明
        Text(
            text = "先按可疑端口、设备名、多 IP 等线索列出可疑设备，再对可疑设备单独测流量综合评分确认。摄像头会先识别排除路由误判（多镜头摄像头不再误标成路由器），但摄像头同样可能内置/被植入 PCDN——有协议级证据照样列可疑。点击设备卡片可单独审计。\n相关功能：可疑设备可用『流量审计』单台复核；藏在二级路由后面的设备用借道审计还原（AI 助手可代跑）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(6.dp))
        // 检测原理（紧凑版，与实现一致：检出从严，先宽进列可疑再逐步排除误报）
        Text(
            text = "检测原理（两阶段·检出从严，漏报优先于误报）：①特征层先宽进列出可疑——可疑端口（10881/10999/27015-19/8800/22222/6881-9 等）/主机名关键词/一台设备占用多个 IP/BT DHT 协议探测（P2P 节点确认）/主路由 UPnP 映射（≥5 条或描述含 PCDN 词）→ 置信度（高=协议级证据，中/低=待确认）；摄像头先做识别排除'多 IP=路由'等误判，但摄像头同样可能跑 PCDN，硬证据照算；②流量审计（需 root+授权）综合评分：总分≥4 且至少 1 个特征命中、且上行量达标才判疑似——上行占比>70% 且≥10KB(+3) / 上行≥1MB 且>3×下行(+4) / 非DNS UDP>85%(+1) / 上行目的 IP≥15(+2) / 上行 IDC≥3(+3) / 并发连接≥500(+1) / STUN≥10(+2) / UPnP≥2(+3) / DNS 域名含 PCDN 平台词(+3)；点击设备卡片可单独审计确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(10.dp))
        // 授权提示卡（阶段2 为主动中间人操作）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            )
        ) {
            Text(
                text = "⚠ ${TrafficAudit.AUTH_NOTE}",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Spacer(Modifier.height(8.dp))
        // root 状态提示（常驻）
        Text(
            text = if (rootOk) {
                "已启用 root：阶段2 将用 ARP 中间人做流量审计确认（需设备所有者授权）。"
            } else {
                "⚠ 未检测到 root 权限：阶段2 流量审计自动跳过，仅完成特征层扫描（可疑端口/设备名/多 IP 等线索，置信度较低，建议有 root 时点击卡片审计确认）。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (rootOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))

        // 阶段2 单台审计时长输入（默认 15 秒，可自定义；长时间审计去流量审计页）
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = auditSecText,
                onValueChange = { auditSecText = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("单台审计时长（秒）") },
                supportingText = { Text("阶段2 用，范围 1-120，默认 15") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !scanning,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
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
                            val sec = auditSecText.toIntOrNull()?.coerceIn(1, 120) ?: 15
                            result = try {
                                PcdnNetworkScanner.scanNetwork(
                                    context = context.applicationContext,
                                    // 有 root 注入 RootShell 通道做阶段2 流量审计；无 root 自动降级为仅特征扫描
                                    rootRun = if (RootShell.isAvailable()) RootShell::run else null,
                                    perDeviceSec = sec
                                )
                            } catch (e: Exception) {
                                error = "检测失败：${e.message ?: e.javaClass.simpleName}"
                                null
                            }
                            // 结果缓存（弱网现场/离线回看）
                            result?.let { r ->
                                val sb = StringBuilder()
                                sb.appendLine("扫描 ${r.scanned} 台 · 可疑 ${r.devices.count { it.suspicious }} · 审计 ${r.audited} · 疑似 ${r.likelyCount}")
                                r.devices.filter { it.suspicious }.forEach { d ->
                                    sb.appendLine("- ${d.ip} ${d.confidence} ${d.portReasons.joinToString("；")}")
                                }
                                r.note?.let { sb.appendLine(it) }
                                ResultCache.save(context.applicationContext, ResultCache.KEY_PCDN, sb.toString())
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
                Text(if (scanning) "检测中…" else "开始全网检测")
            }
        }
        Spacer(Modifier.height(12.dp))

        // 上次检测结果回看（弱网/离线）
        if (ResultCache.has(context.applicationContext, ResultCache.KEY_PCDN)) {
            TextButton(onClick = {
                cachedPcdnResult = ResultCache.load(context.applicationContext, ResultCache.KEY_PCDN)
                cachedPcdnTime = ResultCache.timeText(context.applicationContext, ResultCache.KEY_PCDN)
            }) {
                Text("📋 查看上次检测结果（${ResultCache.timeText(context.applicationContext, ResultCache.KEY_PCDN)}）")
            }
            cachedPcdnResult?.let { txt ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        text = "上次结果（$cachedPcdnTime）：\n$txt",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        result?.let { r ->
            val suspiciousCount = r.devices.count { it.suspicious }
            val normalCount = r.devices.size - suspiciousCount
            // 手动单台审计（点击设备卡片触发）：复用页面审计时长，root 通道与整网检测一致
            fun auditSingleDevice(ip: String) {
                if (auditingIp != null || scanning) return
                auditingIp = ip
                scope.launch {
                    // 运行日志：单台审计同样清空上一轮并收集 root 命令过程（便于反馈改进）
                    logs = emptyList()
                    showLogs = true
                    RootShell.logListener = { line -> logs = logs + line }
                    try {
                        val sec = auditSecText.toIntOrNull()?.coerceIn(1, 120) ?: 15
                        val a = try {
                            PcdnNetworkScanner.auditSingle(
                                context = context.applicationContext,
                                targetIp = ip,
                                perDeviceSec = sec,
                                rootRun = if (RootShell.isAvailable()) RootShell::run else null
                            )
                        } catch (e: Exception) {
                            null
                        }
                        result = result?.let { cur ->
                            val newDevices = cur.devices.map {
                                if (it.ip == ip) it.copy(audit = a) else it
                            }
                            cur.copy(
                                devices = newDevices,
                                audited = newDevices.count { it.audit != null },
                                likelyCount = newDevices.count { it.confidence == "高" || it.audit?.pcdnLikely == true }
                            )
                        }
                    } finally {
                        RootShell.logListener = null
                    }
                    auditingIp = null
                }
            }

            // 统计卡：扫描设备数 / 可疑数 / 正常数 / 审计数 / 疑似数（检测完成自动滚到这里）
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .onGloballyPositioned { c ->
                        val y = c.positionInParent().y
                        if (y != resultAreaY) scope.launch { resultAreaY = y } // defer 写 state，避免 Compose 1.7 并发修改崩溃
                    }
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = "全网扫描 ${r.scanned} 台 · 可疑 ${suspiciousCount} 台 · 正常 ${normalCount} 台 · 流量审计 ${r.audited} 台 · 疑似 ${r.likelyCount} 台",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "（检出从严，漏报优先于误报：可疑=特征命中待确认；疑似=协议级强证据或流量审计确认；点击设备卡片可单独审计。无 root 时审计自动跳过）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 分享/导出报告（装维留档），共用一份报告文本
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ShareHelper.shareText(context, "PCDN 检测报告", buildPcdnReportText(r))
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("分享报告")
                        }
                        ExportReportButton(
                            fileName = "PCDN检测报告",
                            text = buildPcdnReportText(r),
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            if (r.devices.isEmpty()) {
                // 空状态（0 台扫描）
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        text = "未扫描到在线设备（请确认已连接 Wi-Fi 后重试）",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                // 全部在线设备明细（可疑在前，正常设备完整列出参数；点击卡片可单独审计）
                r.devices.forEach { d ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(
                                enabled = !scanning && auditingIp == null,
                                onClick = { auditSingleDevice(d.ip) }
                            )
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            // 首行：IP + 置信度徽章
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = d.ip,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                ConfidenceBadge(d.confidence)
                            }
                            // 审计中状态（点击卡片触发的手动审计）
                            if (auditingIp == d.ip) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "⏳ 流量审计中（约 ${auditSecText} 秒，ARP/IPv6 中间人抓包统计）…",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // 可疑设备：判定文案（结合阶段1 置信度与阶段2 流量审计）
                            if (d.suspicious) {
                                Text(
                                    text = "判定：${PcdnNetworkScanner.verdict(d)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            // 设备信息
                            Text(
                                text = buildString {
                                    append("MAC：${d.mac ?: "未知"}")
                                    append("　厂商：${d.vendor ?: "未知"}")
                                    append("　主机名：${d.hostname ?: "未知"}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 在线端口（详细参数）
                            if (d.openPorts.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "在线端口：${d.openPorts.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // UPnP 映射条目（主路由端口映射表，PCDN 特征维度）
                            if (d.upnpMappingCount > 0) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "UPnP 映射：${d.upnpMappingCount} 条（主路由端口映射表）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            // 阶段1 特征判定依据：**完整显示不截断**（用户要求依据全文可见，
                            // 摄像头排除/超时未确认等说明必须看全，便于反馈改进）
                            if (d.portReasons.isNotEmpty()) {
                                val reasons = d.portReasons.joinToString("；")
                                Text(
                                    text = "特征依据（${d.portReasons.size} 条）：$reasons",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            // 阶段2 流量审计摘要（若有）
                            d.audit?.let { a ->
                                Spacer(Modifier.height(4.dp))
                                AuditSummary(a)
                            }
                            // 借道/长时审计跳转入口：可疑设备 或 疑似二级路由（端口全关的纯 NAT 路由
                            // 无 PCDN 特征也显示）——带 IP 免输入；二级路由自动切借道模式
                            if ((d.suspicious || d.routerLike) && auditingIp != d.ip) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = when {
                                            d.suspicious && d.audit == null -> "👆 点击本卡片快速审计（默认 ${auditSecText}s）"
                                            d.routerLike && !d.suspicious -> "疑似二级路由：下挂设备在其内网侧，建议借道审计还原"
                                            d.routerLike -> "同网段审计抓不到二级路由内网侧流量？"
                                            else -> "需要更长时间审计？"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (d.suspicious && d.audit == null) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (d.suspicious && d.audit == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // 借道审计（二级路由综合判定命中）/ 长时审计（普通设备）：跳转审计页自动带 IP（借道再自动切模式）
                                    TextButton(onClick = { onNavigateToAudit(d.ip, d.routerLike) }) {
                                        Text(
                                            text = if (d.routerLike) "借道审计 ↗" else "长时审计 ↗",
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            r.note?.let {
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 运行日志面板：扫描/审计过程 root 命令执行全可见（有日志自动展开，可手动开合）
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

/** 流量审计结果摘要：默认一行结论，点开折叠详情（占比/IDC/评分/note）——设备列表不堆长 */
@Composable
private fun AuditSummary(a: TrafficAudit.AuditResult) {
    var expanded by remember(a) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            // 结论行（点击展开/收起详情；内部 clickable 消费点击，不触发设备卡片的外层审计）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "流量审计：${if (a.pcdnLikely) "疑似 PCDN 上行占用 ✓" else "未确认 ✗"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (a.pcdnLikely) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起详情" else "查看详情",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    // 消费点击：展开的详情区点任何文字都不冒泡到外层卡片（防误触发 15 秒审计）；
                    // 要审计请收起后点卡片其他区域
                    modifier = Modifier.clickable(enabled = true, onClick = {})
                ) {
                    a.stats?.let { s ->
                        val totalBytes = s.sentBytes + s.recvBytes
                        if (totalBytes < MIN_SAMPLE_BYTES) {
                            // 样本不足：不显示占比/IDC 碎片统计（1KB 级别的占比无意义，防误导）
                            Text(
                                text = "窗口内流量过少（上行 ${formatBytes(s.sentBytes)} / 下行 ${formatBytes(s.recvBytes)}），样本不足未判定——目标空闲、不在本网段或引流未生效",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = buildString {
                                    append("上行占比 ${(s.upRatio * 100).toInt()}%（上行 ${formatBytes(s.sentBytes)} / 下行 ${formatBytes(s.recvBytes)}）")
                                    append("　连接 ${s.connections} 个")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // IDC 命中数从审计 note 中提取（TrafficAudit 未单列字段）
                            val idcHits = IDC_HITS_RE.find(a.note.orEmpty())?.groupValues?.get(1)
                            if (idcHits != null) {
                                Text(
                                    text = "目的 IP 指向 IDC/数据中心 $idcHits 个（PCDN 节点上行典型特征）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // 综合得分（从 note 提取，独立于碎片统计展示）
                        val score = SCORE_RE.find(a.note.orEmpty())
                        if (score != null) {
                            Text(
                                text = "综合评分：${score.groupValues[1]} 分（命中 ${score.groupValues[2]} 维）——总分≥4 且至少 1 维才判疑似",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 审计说明（授权提示 + 统计明细），过长截断
                    a.note?.let { note ->
                        Text(
                            text = note.take(220) + if (note.length > 220) "……" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

/** 审计 note 中的 IDC 命中数（格式：命中 IDC/数据中心 N 个） */
private val IDC_HITS_RE = Regex("""命中 IDC/数据中心 (\d+) 个""")

/** 审计 note 中的综合得分（格式：本次得分 N（命中 M 维）） */
private val SCORE_RE = Regex("""本次得分 (\d+)（命中 (\d+) 维）""")

/** 样本门槛：窗口内总流量低于此值不展示占比/IDC 碎片统计（与审计 minBytes 同量级） */
private const val MIN_SAMPLE_BYTES = 10L * 1024L

/** PCDN 全网检测报告文本（分享/导出共用一份） */
private fun buildPcdnReportText(r: NetworkScanResult): String = buildString {
    val suspiciousCount = r.devices.count { it.suspicious }
    val normalCount = r.devices.size - suspiciousCount
    appendLine("装维通 · PCDN 全网检测报告")
    appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
    appendLine("扫描 ${r.scanned} 台 · 可疑 ${suspiciousCount} · 正常 ${normalCount} · 审计 ${r.audited} · 疑似 ${r.likelyCount}")
    r.devices.filter { it.suspicious }.forEach { d ->
        appendLine("- ${d.ip}  置信度:${d.confidence}  判定:${PcdnNetworkScanner.verdict(d)}  UPnP映射:${d.upnpMappingCount}  ${d.portReasons.joinToString("；")}")
        // 点击卡片单台审计的结果一并导出：结论 + 上下行/占比 + 得分 + 关键说明
        d.audit?.let { a ->
            a.stats?.let { s ->
                appendLine(
                    "   流量审计：${if (a.pcdnLikely) "疑似 PCDN 上行占用" else "未确认"}，" +
                        "上行 ${formatBytes(s.sentBytes)} / 下行 ${formatBytes(s.recvBytes)}，" +
                        "上行占比 ${(s.upRatio * 100).toInt()}%，连接 ${s.connections} 个"
                )
            }
            SCORE_RE.find(a.note.orEmpty())?.let { m ->
                appendLine("   综合评分：${m.groupValues[1]} 分（命中 ${m.groupValues[2]} 维）")
            }
            a.note?.let { n ->
                // 去重：note 已含统计口径与授权提示，摘取关键判定片段（最多 2 段）避免报告过长
                val key = n.split("；").filter { it.contains("得分") || it.contains("IDC") || it.contains("域名") || it.contains("借道") }
                if (key.isNotEmpty()) appendLine("   明细：${key.joinToString("；").take(300)}")
            }
        }
    }
    r.note?.let { appendLine("说明：$it") }
}

/** 字节数人性化显示（KB/MB） */
private fun formatBytes(v: Long): String = when {
    v >= 1024L * 1024L -> String.format("%.1f MB", v / 1024.0 / 1024.0)
    v >= 1024L -> String.format("%.0f KB", v / 1024.0)
    else -> "$v B"
}

/** 置信度徽章：正常=绿，异常（高/中/低，即疑似 PCDN）=红 */
@Composable
private fun ConfidenceBadge(confidence: String) {
    val color = if (confidence == "正常") {
        Color(0xFF2E7D32) // 绿
    } else {
        Color(0xFFD32F2F) // 红（可疑设备）
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = confidence,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}
