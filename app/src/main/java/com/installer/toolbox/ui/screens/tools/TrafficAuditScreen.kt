package com.installer.toolbox.ui.screens.tools

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.network.util.IpUtil
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.discovery.DeviceScanner
import com.installer.toolbox.core.network.scan.TrafficAudit
import com.installer.toolbox.core.network.scan.TrafficAudit.AuditResult
import com.installer.toolbox.ui.components.LogPanel
import com.installer.toolbox.util.ExportReportButton
import com.installer.toolbox.util.ShareHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 流量审计页（高级版）：ARP 中间人把目标设备流量引到手机，tcpdump 统计上下行
 * 识别疑似 PCDN 上行占用。需要 root（内置 arpspoof/tcpdump，无需 Termux），
 * 且必须取得设备所有者授权（页面顶部有强提示卡，结果 note 也强制携带授权提示）。
 */
@Composable
fun TrafficAuditScreen(
    initialIp: String? = null,
    initialPassthrough: Boolean = false
) {
    var targetIp by remember { mutableStateOf(initialIp.orEmpty()) }
    // 默认 60 秒长时审计（对齐运营商单日口径，15 秒短窗口占比噪声大）
    var durationSecText by remember { mutableStateOf("60") }
    var auditing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AuditResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 运行日志：审计期间收集 root 命令执行过程（有日志自动展开面板，可手动开合）
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 焦点自动定位（动作进行时焦点跟随，每动作一次，之后可自由滑动）：
    // 审计开始（日志出现）→ 自动滚到运行日志面板；审计完成（结果出现）→ 自动滚到结果区
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
            text = "流量审计",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "把目标设备的网络数据临时引到本机统计上传/下载，判定是否疑似 PCDN 上行占用。需要 root（内置工具，无需额外安装）。目标设备启用 ARP 防护/静态绑定、防火墙拦截或网络开启 AP/客户端隔离时审计可能抓不到流量。\n相关功能：确认设备在偷跑流量后，可用『静默模式』断其外网（需 root+授权）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))

        // 授权强提示卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = "⚠ 授权提示：流量审计会把对方设备的数据临时引到本机统计（ARP 中间人技术），请确认已取得设备/网络所有者授权；仅限装维排查使用。",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Spacer(Modifier.height(10.dp))
        // root 状态提示：无 root 时审计不可用，提示可降级的功能
        val rootOk = remember { RootShell.isAvailable() }
        if (!rootOk) {
            Text(
                text = "⚠ 未检测到 root 权限：流量审计（ARP 中间人抓包）不可用。可降级改用『PCDN 全网检测』做特征层扫描（可疑端口/设备名/多 IP 等线索）定位可疑设备，或『ARP 欺骗检测』做 ARP 表对比（精度较低）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
        }
        // 检测原理（紧凑版，与实现一致：综合评分制，检出从严）
        Text(
            text = "简单说：把可疑设备的流量引过来，看上传是不是异常地多、目标是不是都在机房服务器上。检测原理（综合评分·检出从严，漏报优先于误报）：总分≥4 且至少 1 个特征命中、且上行量达标（≥256KB 或命中硬特征）才判疑似（结果仅供参考需人工复核）——上行占比>70% 且上行≥10KB(+3) / 上行≥1MB 且>3×下行(+4) / 非 DNS UDP>85%(+1) / 上行目的 IP≥15(+2) / 上行 IDC≥3(+3) / 并发连接≥500(+1) / STUN≥10(+2) / UPnP 映射≥2(+3) / DNS 域名含 PCDN 平台关键词(+3，域名自报家门) / NAT 借道还原(+2，目标是二级路由时按源端口还原其下挂设备)；阈值随时长等比放大（15s 基准），流量过少（<20 包）不判定。对『一台设备占用多个 IP』的疑似二级路由审计=借道审计，PCDN 藏在 NAT 后面也能从其 WAN 流量还原。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))

        // 输入区
        // 审计模式：单台 / 借道（二级路由）——引擎对任何目标都内置 NAT 借道还原（双向 ARP+IPv6 欺骗 + 源端口聚类），
        // 这里只是界面引导：借道模式让装维一眼知道"私接二级路由的流量该在这里查"
        var passthrough by remember { mutableStateOf(initialPassthrough) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !passthrough,
                onClick = { passthrough = false },
                label = { Text("单台审计") }
            )
            FilterChip(
                selected = passthrough,
                onClick = { passthrough = true },
                label = { Text("借道审计（二级路由）") }
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = targetIp,
            onValueChange = { targetIp = it },
            label = { Text(if (passthrough) "二级路由 IP（借道审计）" else "目标设备 IP") },
            placeholder = { Text(if (passthrough) "如 192.168.1.1（路由器）" else "如 192.168.1.100") },
            supportingText = if (passthrough) {
                {
                    Text("输入客户私接的二级路由 IP：从它的上网流量按源端口还原藏在 NAT 后面的下挂设备——PCDN 盒子藏在里面也能查出来")
                }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = durationSecText,
                onValueChange = { durationSecText = it },
                label = { Text("审计时长（秒）") },
                placeholder = { Text("默认 60") },
                supportingText = { Text("范围 1-120，默认 60（长时更接近运营商口径）") },
                singleLine = true,
                modifier = Modifier.width(200.dp)
            )
        }
        Spacer(Modifier.height(16.dp))

        // 审计中实时剩余秒数（倒计时反馈）
        var remainSec by remember { mutableStateOf(0) }
        val currentAuditing by rememberUpdatedState(auditing)
        LaunchedEffect(auditing) {
            if (auditing) {
                remainSec = (durationSecText.toLongOrNull() ?: 60).coerceIn(1, 120).toInt()
                while (currentAuditing) {
                    delay(1000)
                    remainSec = (remainSec - 1).coerceAtLeast(0)
                }
            }
        }

        Button(
            onClick = {
                val ip = targetIp.trim()
                if (ip.isEmpty()) {
                    error = "请先输入目标设备 IP"
                    return@Button
                }
                if (!IpUtil.isValidIp(ip)) {
                    error = "IP 格式不正确（需为点分十进制，如 192.168.1.100）"
                    return@Button
                }
                // 网段软提示：非本网段 IP 允许（多网段经路由器互连可达），但明确提醒
                val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
                if (!IpUtil.inSubnet(ip, subnet)) {
                    error = "⚠ 该 IP 不在当前 Wi-Fi 网段（$subnet）：若目标设备在同局域网另一网段（经路由器互连）可继续；否则审计将抓不到流量。确认后重新点击开始审计"
                    return@Button
                }
                val sec = durationSecText.toLongOrNull()?.coerceIn(1, 120) ?: 60
                scope.launch {
                    auditing = true
                    error = null
                    // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
                    logs = emptyList()
                    showLogs = true
                    RootShell.logListener = { line -> logs = logs + line }
                    try {
                        result = try {
                            TrafficAudit.audit(
                                context = context,
                                targetIp = ip,
                                durationMs = sec * 1000L,
                                rootRun = RootShell::run
                            )
                        } catch (e: Exception) {
                            error = "审计失败：${e.message ?: e.javaClass.simpleName}"
                            null
                        }
                    } finally {
                        RootShell.logListener = null
                    }
                    auditing = false
                }
            },
            enabled = !auditing
        ) {
            Text(if (auditing) "审计中…剩余 $remainSec 秒" else "开始审计")
        }
        Spacer(Modifier.height(12.dp))

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        result?.let { r ->
            // 判定结果卡
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
                        text = "目标：${r.targetIp}　网关：${r.gatewayIp ?: "未知"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (r.pcdnLikely) "判定：疑似 PCDN 上行占用！" else "判定：暂未发现 PCDN 上行特征",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (r.pcdnLikely) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    // 分享/导出审计结果（装维留档），共用一份报告文本
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ShareHelper.shareText(context, "流量审计报告", buildAuditReportText(r))
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("分享结果")
                        }
                        ExportReportButton(
                            fileName = "流量审计报告",
                            text = buildAuditReportText(r),
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }

            // 流量统计卡
            r.stats?.let { s ->
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = "流量统计（启发式，仅供参考）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        StatRow("上行包 / 字节", "${s.sentPackets} / ${formatBytes(s.sentBytes)}")
                        StatRow("下行包 / 字节", "${s.recvPackets} / ${formatBytes(s.recvBytes)}")
                        StatRow("上行占比", "%.1f%%".format(s.upRatio * 100))
                        val tcpUdpTotal = s.tcpPackets + s.udpPackets
                        StatRow(
                            "TCP / UDP 包",
                            "${s.tcpPackets} / ${s.udpPackets}" +
                                if (tcpUdpTotal > 0) "（UDP ${"%.1f%%".format(s.udpPackets * 100.0 / tcpUdpTotal)}）" else ""
                        )
                        StatRow("并发连接数", s.connections.toString())
                        StatRow("DNS 请求", "${s.dnsRequests} 次")
                        StatRow("STUN/NAT 打洞", "${s.stunPackets} 包")
                        StatRow("SSDP/UPnP 宣告", "${s.ssdpPackets} 包")
                        StatRow("UPnP 映射请求", "${s.upnpControlPackets} 包")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
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

        // 运行日志面板：审计过程 root 命令执行全可见（有日志自动展开，可手动开合）
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

/** 流量审计报告文本（分享/导出共用一份） */
private fun buildAuditReportText(r: AuditResult): String = buildString {
    appendLine("装维通 · 流量审计报告")
    appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
    appendLine("目标：${r.targetIp}　网关：${r.gatewayIp ?: "未知"}")
    appendLine("判定：${if (r.pcdnLikely) "疑似 PCDN 上行占用！" else "暂未发现 PCDN 上行特征"}")
    r.stats?.let { s ->
        appendLine("上行：${s.sentPackets} 包 / ${formatBytes(s.sentBytes)}")
        appendLine("下行：${s.recvPackets} 包 / ${formatBytes(s.recvBytes)}")
        appendLine("上行占比：${"%.1f".format(s.upRatio * 100)}%")
        appendLine("连接数：${s.connections}　DNS：${s.dnsRequests}　STUN：${s.stunPackets}　SSDP：${s.ssdpPackets}　UPnP：${s.upnpControlPackets}")
    }
    r.note?.let { appendLine("说明：$it") }
}

/** 统计行 */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 字节数格式化：B / KB / MB */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
