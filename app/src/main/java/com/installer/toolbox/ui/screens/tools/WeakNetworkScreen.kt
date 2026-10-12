package com.installer.toolbox.ui.screens.tools

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.diagnose.PLATFORMS
import com.installer.toolbox.core.network.diagnose.PlatformTarget
import com.installer.toolbox.core.network.diagnose.WeakNetworkDetector
import com.installer.toolbox.core.network.diagnose.WeakNetworkDetector.WeakNetResult
import com.installer.toolbox.util.ExportReportButton
import com.installer.toolbox.util.ShareHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 弱网检测页：持续探测目标（TCP connect 计时，无需 root），统计丢包率/时延（min/avg/max）/
 * 抖动 jitter，判定网络是否稳定。目标留空时自动探测当前网关（取不到网关默认 192.168.1.1）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeakNetworkScreen() {
    var detecting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WeakNetResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var targetText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("10") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 主流平台：单点快测（platformTesting=正在测的平台名）与一键批量（batchResults 对比列表）
    var platformTesting by remember { mutableStateOf<String?>(null) }
    var batchResults by remember { mutableStateOf<List<Pair<PlatformTarget, WeakNetResult>>?>(null) }
    var batchIndex by remember { mutableStateOf(0) }
    var batchRunning by remember { mutableStateOf(false) }

    fun start() {
        if (detecting || batchRunning || platformTesting != null) return
        val sec = durationText.toIntOrNull()?.coerceIn(3, 60) ?: 10
        // 目标为空 → 自动取当前网关；取不到网关兜底 192.168.1.1（提示可改）
        val target = targetText.trim().ifEmpty { currentGateway(context) ?: "192.168.1.1" }
        // 有 root 走真 ICMP ping，无 root 回退 TCP 探测
        val hasRoot = RootShell.isAvailable()
        detecting = true
        error = null
        result = null
        scope.launch {
            try {
                result = WeakNetworkDetector.detect(
                    target = target,
                    durationSec = sec,
                    rootRun = if (hasRoot) RootShell::run else null
                )
            } catch (e: Exception) {
                error = "检测失败：${e.message ?: e.javaClass.simpleName}"
            }
            detecting = false
        }
    }

    /** 单点快测某平台：固定 6 秒，结果复用下方 result 卡片展示 */
    fun testPlatform(p: PlatformTarget) {
        if (detecting || batchRunning || platformTesting != null) return
        val hasRoot = RootShell.isAvailable()
        platformTesting = p.name
        error = null
        result = null
        scope.launch {
            try {
                result = WeakNetworkDetector.detect(
                    target = p.host,
                    durationSec = 6,
                    rootRun = if (hasRoot) RootShell::run else null
                )
            } catch (e: Exception) {
                error = "检测失败：${e.message ?: e.javaClass.simpleName}"
            }
            platformTesting = null
        }
    }

    /** 一键批量测全部平台：顺序测（每平台 6 秒，间隔 300ms），完成后生成对比列表 */
    fun startBatch() {
        if (detecting || batchRunning) return
        val hasRoot = RootShell.isAvailable()
        batchRunning = true
        batchIndex = 0
        batchResults = null
        error = null
        scope.launch {
            val list = mutableListOf<Pair<PlatformTarget, WeakNetResult>>()
            PLATFORMS.forEachIndexed { i, p ->
                batchIndex = i
                val r = try {
                    WeakNetworkDetector.detect(
                        target = p.host,
                        durationSec = 6,
                        rootRun = if (hasRoot) RootShell::run else null
                    )
                } catch (e: Exception) {
                    // 单平台失败兜底：按全丢包弱网络处理（DNS 失败/异常不中断整批）
                    WeakNetResult(
                        target = p.host,
                        durationSec = 6,
                        intervalMs = 0L,
                        sent = 0,
                        received = 0,
                        lossPercent = 100.0,
                        avgMs = null,
                        minMs = null,
                        maxMs = null,
                        jitterMs = null,
                        samples = emptyList(),
                        verdict = "弱",
                        summary = "检测失败：${e.message ?: e.javaClass.simpleName}",
                        probeMode = "-"
                    )
                }
                list.add(p to r)
                // 每测完一个平台立即更新结果列表 → 边测边出（不用等全部完成）
                batchResults = list.toList()
                if (i < PLATFORMS.lastIndex) delay(300)
            }
            batchRunning = false
        }
    }

    // 探测中实时剩余秒数（倒计时反馈）
    var remainSec by remember { mutableStateOf(0) }
    val currentDetecting by rememberUpdatedState(detecting)
    LaunchedEffect(detecting) {
        if (detecting) {
            remainSec = durationText.toIntOrNull()?.coerceIn(3, 60) ?: 10
            while (currentDetecting) {
                delay(1000)
                remainSec = (remainSec - 1).coerceAtLeast(0)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("弱网检测") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "持续探测目标一段时间，统计丢包率、时延（最小/平均/最大）与网络抖动，判断网络稳不稳（断流、卡顿、时延波动都看得出来）。无需 root；目标留空时自动探测当前网关。\n相关功能：测出网络不稳，用『网络诊断』定位具体断点（ping/路由追踪/端口）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            // 目标输入（留空 = 自动探测网关）
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = it },
                label = { Text("目标 IP / 域名") },
                placeholder = { Text("默认探测当前网关") },
                supportingText = { Text("有 root 用真正的连通性测试；无 root 探测 80/443 端口") },
                singleLine = true,
                enabled = !detecting && !batchRunning,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            // 时长输入 + 开始按钮
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("探测时长（秒）") },
                    supportingText = { Text("范围 3-60，默认 10") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !detecting && !batchRunning,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = ::start,
                    enabled = !detecting && !batchRunning,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(if (detecting) "探测中…剩余 $remainSec 秒" else "开始探测")
                }
            }
            Spacer(Modifier.height(12.dp))

            // ===== 主流平台快测（单点 6 秒 / 一键批量对比） =====
            Text(
                text = "主流平台快测",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "点击平台立即测（平台快测固定 6 秒）；或一键测全部 10 个平台对比网络质量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PLATFORMS.forEach { p ->
                    val testing = platformTesting == p.name
                    FilterChip(
                        selected = false,
                        onClick = { testPlatform(p) },
                        enabled = !detecting && !batchRunning && platformTesting == null,
                        label = { Text(if (testing) "${p.name} 测中…" else p.name) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = ::startBatch,
                enabled = !detecting && !batchRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (batchRunning) "正在批量检测全部平台…" else "测全部平台（10 个 × 6 秒）")
            }
            if (batchRunning) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "正在测 ${batchIndex + 1}/${PLATFORMS.size}：${PLATFORMS[batchIndex].name}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            result?.let { r ->
                if (r.verdict == "参数错误") {
                    // 参数错误：只展示可读错误文本
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            text = r.summary,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    // 结论卡片（良好绿 / 一般橙 / 弱红）
                    val (cardColor, textColor, label) = when (r.verdict) {
                        "良好" -> Triple(
                            Color(0xFF2E7D32).copy(alpha = 0.15f),
                            Color(0xFF2E7D32),
                            "✓ 网络良好"
                        )
                        "一般" -> Triple(
                            Color(0xFFEF6C00).copy(alpha = 0.15f),
                            Color(0xFFEF6C00),
                            "⚠ 网络一般"
                        )
                        else -> Triple(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                            "✗ 网络弱"
                        )
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardColor)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Text(
                            text = "探测方式：${r.probeMode}",
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 指标卡
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "统计指标：",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "目标：${r.target}（持续 ${r.durationSec} 秒，间隔 ${r.intervalMs}ms）",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "发送 ${r.sent} 次 / 成功 ${r.received} 次",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "丢包率：${"%.1f".format(r.lossPercent)}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "平均时延：${r.avgMs ?: "-"} ms",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "最小时延：${r.minMs ?: "-"} ms　最大时延：${r.maxMs ?: "-"} ms",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "网络抖动：${r.jitterMs ?: "-"} ms（相邻成功样本 RTT 差均值）",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = r.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    // 样本时延列表（最近 20 条）
                    Text(
                        text = "样本时延（最近 ${minOf(20, r.samples.size)} 条 / 共 ${r.samples.size} 条，超时=丢包）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Card(Modifier.fillMaxWidth()) {
                        val recent = r.samples.takeLast(20)
                        val startIdx = r.samples.size - recent.size
                        Column(Modifier.padding(12.dp)) {
                            recent.forEachIndexed { i, s ->
                                Text(
                                    text = "第 ${startIdx + i + 1} 次：${if (s == null) "超时" else "$s ms"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s == null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                    // 分享/导出报告（装维留档），共用一份报告文本
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ShareHelper.shareText(context, "弱网检测报告", buildWeakReportText(r))
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("分享报告")
                        }
                        ExportReportButton(
                            fileName = "弱网检测报告",
                            text = buildWeakReportText(r),
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }

            // ===== 主流平台对比列表（一键批量结果） =====
            batchResults?.let { list ->
                Spacer(Modifier.height(14.dp))
                val good = list.count { it.second.verdict == "良好" }
                val fair = list.count { it.second.verdict == "一般" }
                val weak = list.count { it.second.verdict == "弱" }
                Text(
                    text = "主流平台对比（${list.size} 个平台：$good 良好、$fair 一般、$weak 弱）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        list.forEach { (p, r) ->
                            val dotColor = when (r.verdict) {
                                "良好" -> Color(0xFF2E7D32)
                                "一般" -> Color(0xFFEF6C00)
                                else -> MaterialTheme.colorScheme.error
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(dotColor, CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = p.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(76.dp)
                                )
                                Text(
                                    text = "丢包 ${"%.1f".format(r.lossPercent)}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(84.dp)
                                )
                                Text(
                                    text = "平均 ${r.avgMs ?: "-"}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(78.dp)
                                )
                                Text(
                                    text = "抖动 ${r.jitterMs ?: "-"}ms",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 对比结果分享/导出
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            ShareHelper.shareText(context, "主流平台弱网对比报告", buildBatchReportText(list))
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("分享对比")
                    }
                    ExportReportButton(
                        fileName = "主流平台弱网对比报告",
                        text = buildBatchReportText(list),
                        modifier = Modifier.height(48.dp)
                    )
                }
            }
        }
    }
}

/** 当前网关 IP（WifiManager dhcpInfo）；未连接 Wi-Fi 或无网关返回 null */
private fun currentGateway(context: Context): String? = runCatching {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val g = wifi.dhcpInfo?.gateway ?: 0
    if (g == 0) null else "${g and 0xFF}.${(g ushr 8) and 0xFF}.${(g ushr 16) and 0xFF}.${(g ushr 24) and 0xFF}"
}.getOrNull()

/** 弱网检测报告文本（分享/导出共用）：标题 + 时间 + 指标 + 结论 + 样本明细 */
private fun buildWeakReportText(r: WeakNetResult): String = buildString {
    appendLine("装维通 · 弱网检测报告")
    appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
    if (r.verdict == "参数错误") {
        appendLine("结论：参数错误")
        appendLine("说明：${r.summary}")
        return@buildString
    }
    appendLine("目标：${r.target}（持续 ${r.durationSec} 秒，间隔 ${r.intervalMs}ms）")
    appendLine("探测方式：${r.probeMode}")
    appendLine("发送 ${r.sent} 次 / 成功 ${r.received} 次，丢包率 ${"%.1f".format(r.lossPercent)}%")
    appendLine("时延：平均 ${r.avgMs ?: "-"} ms，最小 ${r.minMs ?: "-"} ms，最大 ${r.maxMs ?: "-"} ms")
    appendLine("抖动 jitter：${r.jitterMs ?: "-"} ms")
    appendLine("判定：${r.verdict}")
    appendLine("结论：${r.summary}")
    appendLine("样本明细（每次探测 RTT，超时=丢包）：")
    r.samples.forEachIndexed { i, s ->
        appendLine("第 ${i + 1} 次：${if (s == null) "超时" else "$s ms"}")
    }
}

/** 主流平台对比报告文本（分享/导出共用）：汇总 + 各平台明细 */
private fun buildBatchReportText(list: List<Pair<PlatformTarget, WeakNetResult>>): String = buildString {
    appendLine("装维通 · 主流平台弱网对比报告")
    appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
    appendLine("共测 ${list.size} 个平台，每平台持续 6 秒")
    val good = list.count { it.second.verdict == "良好" }
    val fair = list.count { it.second.verdict == "一般" }
    val weak = list.count { it.second.verdict == "弱" }
    appendLine("汇总：${good} 良好、${fair} 一般、${weak} 弱")
    appendLine()
    list.forEach { (p, r) ->
        appendLine(
            "【${p.name}】${p.host}：${r.verdict}，丢包 ${"%.1f".format(r.lossPercent)}%，" +
                "平均 ${r.avgMs ?: "-"}ms，最小 ${r.minMs ?: "-"}ms，最大 ${r.maxMs ?: "-"}ms，" +
                "抖动 ${r.jitterMs ?: "-"}ms（${r.probeMode}）"
        )
        appendLine("   ${r.summary}")
    }
}
