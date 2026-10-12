package com.installer.toolbox.ui.screens.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.installer.toolbox.core.capture.StormState
import com.installer.toolbox.core.capture.TrafficSnapshot
import com.installer.toolbox.util.ShareHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抓包分析页：开始/停止抓包（免 root VpnService）、实时统计、广播/组播风暴告警。
 *
 * 启动流程（权限链）：
 * 1. Android 13+ 先请求 POST_NOTIFICATIONS（前台服务通知需要）；
 * 2. 调 VpnService.prepare()，返回非 null Intent 则经 launcher 弹系统 VPN 授权框，
 *    结果 RESULT_OK 后（或直接已授权）再 startForegroundService；
 * 3. ViewModel.startAfterVpnPermission() 真正启动 [PacketCaptureService]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(viewModel: CaptureViewModel = viewModel()) {
    val isRunning by viewModel.isRunning.collectAsState()
    val latest by viewModel.latest.collectAsState()
    val storm by viewModel.storm.collectAsState()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }

    // VPN 授权结果：RESULT_OK 才真正启动前台服务
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startAfterVpnPermission()
        } else {
            notice = "未授权 VPN 连接，无法启动抓包"
        }
    }

    /** 走 VPN 授权流程：已授权则直接启动，未授权则弹出系统授权框 */
    fun startVpnFlow() {
        notice = null
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            // prepare 返回 null 表示此前已授权
            viewModel.startAfterVpnPermission()
        }
    }

    // Android 13+ 通知权限：授权通过后再走 VPN 授权流程
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVpnFlow()
        } else {
            notice = "未授予通知权限，无法启动抓包服务"
        }
    }

    fun onStartClick() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVpnFlow()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("抓包分析") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // 开始/停止按钮 + 运行状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (isRunning) viewModel.stop() else onStartClick() }) {
                    Text(if (isRunning) "停止抓包" else "开始抓包")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (isRunning) "抓包运行中…" else "未在抓包",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))

            // 风暴状态卡片（醒目显示）
            StormCard(storm)
            Spacer(Modifier.height(12.dp))

            // 实时统计卡片
            StatsCard(latest, context)
            Spacer(Modifier.height(12.dp))

            // 说明文字（口语化：免 root 可用 + 只统计不改数据 + root 帧级检测指向环路检测）
            Text(
                text = "说明：抓包需授权 VPN 连接，只统计经过本机的流量，不改动任何数据。\n" +
                    "本页风暴检测看的是普通广播/组播数据包的多少（免 root）；" +
                    "要抓更底层的完整风暴数据需 root，用『环路检测』（极客工具）即可。\n" +
                    "相关功能：想看某台设备单独传了多少流量，用『流量审计』（需 root）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 风暴状态卡片：按状态着色（绿/橙/红）并附中文说明 */
@Composable
private fun StormCard(storm: StormState) {
    val color = stormColor(storm)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(color, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = storm.description,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "检测规则（启发式）：广播/组播包速率连续 3 个周期超过阈值" +
                    "（800 包/秒）且占比超过 20% 判定风暴；连续 3 个周期回落解除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 实时统计卡片：周期快照各字段 + 分享统计（装维留档） */
@Composable
private fun StatsCard(snapshot: TrafficSnapshot?, context: Context) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "实时统计",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (snapshot != null) {
                    Button(
                        onClick = {
                            ShareHelper.shareText(context, "抓包统计报告", buildShareText(snapshot))
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("分享统计")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (snapshot == null) {
                Text(
                    text = "暂无数据：开始抓包并等待首个统计周期（约 1 秒）…",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val broadcastRate = if (snapshot.periodSeconds > 0.0) {
                    (snapshot.broadcastPackets + snapshot.multicastPackets) / snapshot.periodSeconds
                } else {
                    0.0
                }
                Text("总包数：${snapshot.totalPackets} · 总字节：${formatBytes(snapshot.totalBytes)}")
                Text("包速率：${formatRate(snapshot.packetsPerSec)} · 字节速率：${formatBytes(snapshot.bytesPerSec)}/s")
                Text(
                    "TCP：${snapshot.tcpPackets} · UDP：${snapshot.udpPackets} · " +
                        "ICMP：${snapshot.icmpPackets} · 其他：${snapshot.otherPackets}"
                )
                Text("广播包：${snapshot.broadcastPackets} · 组播包：${snapshot.multicastPackets}")
                Text(
                    "广播/组播速率：${formatRate(broadcastRate)} · " +
                        "占比：${(snapshot.broadcastRatio * 100).toInt()}%"
                )
            }
        }
    }
}

/** 把抓包统计快照拼成分享文本（与统计卡显示一致，另附风暴状态与时间） */
private fun buildShareText(snapshot: TrafficSnapshot): String {
    val sb = StringBuilder()
    sb.appendLine("装维通 · 抓包统计报告")
    sb.appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
    sb.appendLine("风暴状态：${snapshot.stormState.description}")
    val broadcastRate = if (snapshot.periodSeconds > 0.0) {
        (snapshot.broadcastPackets + snapshot.multicastPackets) / snapshot.periodSeconds
    } else {
        0.0
    }
    sb.appendLine("总包数：${snapshot.totalPackets} · 总字节：${formatBytes(snapshot.totalBytes)}")
    sb.appendLine("包速率：${formatRate(snapshot.packetsPerSec)} · 字节速率：${formatBytes(snapshot.bytesPerSec)}/s")
    sb.appendLine(
        "TCP：${snapshot.tcpPackets} · UDP：${snapshot.udpPackets} · " +
            "ICMP：${snapshot.icmpPackets} · 其他：${snapshot.otherPackets}"
    )
    sb.appendLine("广播包：${snapshot.broadcastPackets} · 组播包：${snapshot.multicastPackets}")
    sb.appendLine("广播/组播速率：${formatRate(broadcastRate)} · 占比：${(snapshot.broadcastRatio * 100).toInt()}%")
    return sb.toString()
}

/** 风暴状态 → 颜色（绿/橙/红） */
private fun stormColor(state: StormState): Color = when (state) {
    StormState.NORMAL -> Color(0xFF2E7D32)
    StormState.WARNING -> Color(0xFFEF6C00)
    StormState.STORM -> Color(0xFFC62828)
}

private fun formatBytes(bytes: Long): String = formatBytes(bytes.toDouble())

private fun formatBytes(bytes: Double): String = when {
    bytes >= 1024.0 * 1024 * 1024 -> String.format(Locale.CHINA, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024.0 * 1024 -> String.format(Locale.CHINA, "%.2f MB", bytes / (1024.0 * 1024))
    bytes >= 1024.0 -> String.format(Locale.CHINA, "%.2f KB", bytes / 1024.0)
    else -> String.format(Locale.CHINA, "%.0f B", bytes)
}

private fun formatRate(rate: Double): String =
    String.format(Locale.CHINA, "%.1f 包/秒", rate)
