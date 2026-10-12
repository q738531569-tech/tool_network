package com.installer.toolbox.ui.screens.tools

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.scan.LoopDetector
import com.installer.toolbox.ui.components.LogPanel
import com.installer.toolbox.util.ExportReportButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 局域网环路检测页：root tcpdump 抓包统计广播/组播占比与包速率，
 * 判断是否存在环路/广播风暴（交换机/网线错误成环、双路由互连）。
 * 抓包时长可自定义（默认 8 秒，3-30 秒）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopDetectScreen() {
    var detecting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LoopDetector.LoopResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var durationText by remember { mutableStateOf("8") }
    // 运行日志：抓包期间收集 root 命令执行过程（有日志自动展开面板，可手动开合）
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    // 风暴值守模式（时隐时现的风暴源：循环抓包，风暴一出现立即记录元凶并报警）
    var watchMode by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf(false) }
    var watchSec by remember { mutableStateOf(0L) }
    var alerts by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val hasRoot = RootShell.isAvailable()
    val context = LocalContext.current
    // 值守期间屏幕常亮（放桌上插电值守，风暴一来震动+蜂鸣，不用人盯）
    val view = LocalView.current
    var screenOn by remember { mutableStateOf(false) }
    LaunchedEffect(screenOn) { view.keepScreenOn = screenOn }
    val scrollState = rememberScrollState()

    fun start() {
        val sec = durationText.toIntOrNull()?.coerceIn(3, 120) ?: 8
        detecting = true
        error = null
        result = null
        scope.launch {
            // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
            logs = emptyList()
            showLogs = true
            RootShell.logListener = { line -> logs = logs + line }
            try {
                try {
                    result = LoopDetector.detect(
                        rootRun = if (hasRoot) RootShell::run else null,
                        durationSec = sec
                    )
                } catch (e: Exception) {
                    error = "检测失败：${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                RootShell.logListener = null
            }
            detecting = false
        }
    }

    /** 风暴值守：循环抓包（每轮 10 秒 + 3 秒间隔），广播帧速率/占比超阈值即记录告警并震动蜂鸣 */
    fun startWatch() {
        if (watching || detecting) return
        scope.launch {
            watching = true
            screenOn = true
            watchSec = 0L
            alerts = emptyList()
            logs = emptyList()
            showLogs = true
            RootShell.logListener = { line -> logs = logs + line }
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 80)
            } catch (e: Exception) {
                null
            }
            try {
                while (watching) {
                    val roundSec = 10
                    val r = try {
                        LoopDetector.detect(
                            rootRun = if (hasRoot) RootShell::run else null,
                            durationSec = roundSec
                        )
                    } catch (e: Exception) {
                        delay(3000)
                        watchSec += 3
                        continue
                    }
                    watchSec += roundSec
                    // 告警阈值：广播 ≥3 帧/秒，或占比 ≥25% 且包量足够（现场风暴实测 8.5-9.7 帧/秒、占比 49-58%）
                    val bps = r.broadcastCount.toDouble() / roundSec
                    val storm = bps >= 3.0 || (r.broadcastRatio >= 0.25 && r.totalPackets >= 50)
                    if (storm) {
                        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        val macs = r.topBroadcastSrc.take(3).joinToString("  ") { "${it.first}×${it.second}" }
                        val line = "$t  广播 ${r.broadcastCount} 帧/10 秒，占比 ${"%.0f".format(r.broadcastRatio * 100)}%" +
                            if (macs.isNotEmpty()) "；源 MAC：$macs" else ""
                        alerts = alerts + line
                        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE)) }
                        runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000) }
                    }
                    delay(3000)
                    watchSec += 3
                }
            } finally {
                runCatching { tone?.release() }
                RootShell.logListener = null
                screenOn = false
                watching = false
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("环路检测") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                text = "环路（网线/交换机错误成环、双路由互连）会导致广播风暴、网络瘫痪。本功能抓包统计广播/组播数据占比与多少判断。正常网络广播+组播占比一般 <10%。\n相关功能：『网络诊断』定位具体断点；『抓包分析』（免 root）看本机广播。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            // root 状态提示（常驻）
            Text(
                text = if (hasRoot) {
                    "已启用 root：可抓包统计全网广播/组播。"
                } else {
                    "未检测到 root：环路检测不可用。可改用『抓包分析』（免 root）看本机广播信号，或登录交换机/路由器查看端口流量排查环路。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasRoot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            // 模式切换：单次检测 / 风暴值守（值守针对时隐时现的风暴源）
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FilterChip(
                    selected = !watchMode,
                    onClick = { if (!watching && !detecting) watchMode = false },
                    label = { Text("单次检测") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = watchMode,
                    onClick = { if (!watching && !detecting) watchMode = true },
                    label = { Text("风暴值守") }
                )
            }
            if (watchMode) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "针对时隐时现的风暴源：循环抓包值守，风暴一出现立即记录时间+广播量+源 MAC 并震动蜂鸣报警；屏幕保持常亮，插电放现场即可，不用人盯。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (watching) watching = false else startWatch() },
                    enabled = hasRoot,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (watching) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) else ButtonDefaults.buttonColors()
                ) {
                    Text(
                        if (watching) "停止值守（已值守 ${watchSec / 60} 分 ${watchSec % 60} 秒）"
                        else "开始值守"
                    )
                }
                if (watching && alerts.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("值守中…未发现风暴（每 13 秒一轮自动抓包）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                alerts.forEach { a ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "⚠ $a",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }
            // 抓包时长输入（仅单次模式；值守固定每轮 10 秒）
            if (!watchMode) {
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("抓包时长（秒）") },
                        supportingText = { Text("范围 3-120，默认 8；长抓包可覆盖间歇性风暴") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !detecting,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    // 抓包中实时剩余秒数（倒计时反馈）
                    var remainSec by remember { mutableStateOf(0) }
                    val currentDetecting by rememberUpdatedState(detecting)
                    LaunchedEffect(detecting) {
                        if (detecting) {
                            remainSec = durationText.toIntOrNull()?.coerceIn(3, 120) ?: 8
                            while (currentDetecting) {
                                delay(1000)
                                remainSec = (remainSec - 1).coerceAtLeast(0)
                            }
                        }
                    }
                    Button(
                        onClick = ::start,
                        enabled = !detecting && !watching,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text(if (detecting) "抓包中…剩余 $remainSec 秒" else "开始检测")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            result?.let { r ->
                // 判定卡
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (r.loopLikely) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Text(
                        text = if (r.loopLikely) "⚠ 疑似环路/广播风暴" else "✓ 未见明显环路特征",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (r.loopLikely) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 统计卡
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("抓包统计：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "总包数：${r.totalPackets}（${"%.1f".format(r.packetsPerSec)} 包/秒）",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "广播帧：${r.broadcastCount} 个　组播帧：${r.multicastCount} 个",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "广播+组播占比：${"%.0f".format(r.broadcastRatio * 100)}%（正常参考 <10%）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                r.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 导出报告（装维留档）
                Spacer(Modifier.height(10.dp))
                ExportReportButton(
                    fileName = "环路检测报告",
                    text = buildLoopReportText(r),
                    modifier = Modifier.height(48.dp)
                )
            }

            // 运行日志面板：抓包过程 root 命令执行全可见（有日志自动展开，可手动开合）
            Spacer(Modifier.height(10.dp))
            LogPanel(logs = logs, expanded = showLogs, onToggle = { showLogs = !showLogs })
        }
    }
}

/** 环路检测报告文本（导出用）：标题 + 判定 + 抓包统计 + 说明 */
private fun buildLoopReportText(r: LoopDetector.LoopResult): String = buildString {
    appendLine("装维通 · 环路检测报告")
    appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
    appendLine("判定：${if (r.loopLikely) "疑似环路/广播风暴" else "未见明显环路特征"}")
    appendLine("抓包统计：总包数 ${r.totalPackets}（${"%.1f".format(r.packetsPerSec)} 包/秒）")
    appendLine("广播帧：${r.broadcastCount} 个　组播帧：${r.multicastCount} 个")
    appendLine("广播+组播占比：${"%.0f".format(r.broadcastRatio * 100)}%（正常参考 <10%）")
    r.note?.let { appendLine("说明：$it") }
}
