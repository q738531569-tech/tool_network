package com.installer.toolbox.ui.screens.wifi

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.installer.toolbox.core.network.wifi.WifiAnalyzer
import com.installer.toolbox.core.network.wifi.WifiRadar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Wi-Fi 雷达页：以雷达图实时展示周边 AP（找 AP 用）。
 *
 * 距离 = 信号强度（越强越靠近圆心=手机），角度 = 按 BSSID 哈希稳定分配（无罗盘）。
 * 拿着手机走动观察：信号最强的方向即 AP 所在方位。约 4 秒刷新一次
 * （Android 13+ 系统限制真实扫描约 30 秒一次，RSSI 变化需稍等生效）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiRadarScreen() {
    val context = LocalContext.current
    var points by remember { mutableStateOf<List<WifiRadar.RadarPoint>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanCount by remember { mutableStateOf(0) }
    val connected = remember { WifiAnalyzer.currentConnection(context) }
    val textMeasurer = rememberTextMeasurer()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            running = true
        } else {
            error = "未授予定位权限，无法扫描周边 Wi-Fi"
        }
    }

    // 实时扫描循环（每 4 秒刷新一次）
    LaunchedEffect(running) {
        while (running) {
            try {
                points = WifiRadar.scan(context)
                scanCount++
                error = null
            } catch (e: Exception) {
                error = e.message ?: "扫描失败"
            }
            delay(4000)
        }
    }

    fun start() {
        error = null
        if (WifiAnalyzer.hasScanPermission(context)) {
            running = true
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wi-Fi 雷达") }) }
    ) { innerPadding ->
        // 整页统一滚动：本机/按钮/雷达图/AP 列表同一滚动流，AP 列表不再被雷达图挤成小区域
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 当前连接 + 控制
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "当前 Wi-Fi：${connected?.ssid ?: "未连接"}（${connected?.rssiDbm ?: "-"} dBm）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (running) running = false else start()
                        }
                    ) { Text(if (running) "停止" else "开始扫描") }
                }
            }
            item {
                Text(
                    text = "雷达图：圆点离圆心越近 = 信号越强；方向为稳定布局（无罗盘）。拿着手机慢慢走动，**信号最强的方向就是 AP 所在位置**。约 4 秒刷新一次（Android 扫描频率限制，信号值变化需稍等）。\n相关功能：想看信道挤不挤、信号强弱对比，用『Wi-Fi 分析』。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            error?.let { msg ->
                item {
                    Text(text = "扫描失败：$msg", color = MaterialTheme.colorScheme.error)
                }
            }

            // 雷达图
            item {
                Card(Modifier.fillMaxWidth()) {
                    RadarCanvas(points = points, connectedRssi = connected?.rssiDbm, textMeasurer = textMeasurer)
                }
            }
            item {
                Text(
                    text = if (running) "雷达运行中…已刷新 $scanCount 次，发现 ${points.size} 个 AP"
                    else "点击「开始扫描」启动雷达（需定位权限）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // AP 列表（信号从强到弱）
            if (points.isNotEmpty()) {
                item {
                    Text(
                        text = "周边 AP（${points.size} 个，按信号排序）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                items(points, key = { it.ap.bssid }) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = p.ap.ssid,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${p.ap.levelDbm} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "路由器 MAC：${p.ap.bssid}　CH${p.ap.channel}（${p.ap.frequency} MHz）　加密：${p.ap.security}　${WifiRadar.distanceText(p)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 雷达图绘制：同心圆 + 十字线 + AP 点（距离=信号，角度=BSSID 哈希） */
@Composable
private fun RadarCanvas(
    points: List<WifiRadar.RadarPoint>,
    connectedRssi: Int?,
    textMeasurer: TextMeasurer
) {
    // 颜色在 Composable 上下文取好（Canvas 的 DrawScope 非 Composable，不能访问 MaterialTheme）
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .padding(8.dp)
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2 - 24f
        val ringColor = outline.copy(alpha = 0.4f)

        // 同心圆（100% / 60% / 30%）
        drawCircle(color = ringColor, radius = radius)
        drawCircle(color = ringColor.copy(alpha = 0.3f), radius = radius * 0.6f)
        drawCircle(color = ringColor.copy(alpha = 0.2f), radius = radius * 0.3f)
        // 十字线
        drawLine(color = ringColor.copy(alpha = 0.25f), start = Offset(center.x, center.y - radius), end = Offset(center.x, center.y + radius))
        drawLine(color = ringColor.copy(alpha = 0.25f), start = Offset(center.x - radius, center.y), end = Offset(center.x + radius, center.y))
        // 中心：手机（当前连接信号）
        drawCircle(color = primary, radius = 8f, center = center)
        drawCircle(
            color = primary.copy(alpha = 0.25f),
            radius = 16f,
            center = center
        )
        if (connectedRssi != null) {
            val t = androidx.compose.ui.text.AnnotatedString("本机 ${connectedRssi}dBm")
            val layout = textMeasurer.measure(t, style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp))
            drawText(layout, topLeft = Offset(center.x - layout.size.width / 2, center.y + 20f))
        }

        // AP 点：距离优先 RTT 米数（40 米满量程），退回 RSSI 估算；角度=稳定哈希
        // 标签：完整 SSID（不截断），按信号从强到弱逐个尝试"上/下/右/左"四个候选位，
        // 选第一个不与已放标签碰撞的位置；全部碰撞才跳过（信号强的优先占位）
        val placedLabels = mutableListOf<androidx.compose.ui.geometry.Rect>()
        points.forEach { p ->
            val angleRad = Math.toRadians(p.angleDeg.toDouble())
            val effDist = p.distanceMeters?.let { (1f - it / 40f).coerceIn(0.1f, 1f) } ?: p.distance
            val r = radius * (1f - effDist)
            val x = center.x + (r * cos(angleRad)).toFloat()
            val y = center.y + (r * sin(angleRad)).toFloat()
            val apColor = if (effDist >= 0.7f) Color(0xFF2E7D32) else tertiary
            drawCircle(color = apColor, radius = 7f, center = Offset(x, y))
            drawCircle(color = apColor.copy(alpha = 0.2f), radius = 12f, center = Offset(x, y))
            // 完整 SSID 标签，候选位按上/下/右/左尝试（信号强的先占位）
            val label = p.ap.ssid.ifBlank { "（隐藏）" }
            val layout = textMeasurer.measure(
                androidx.compose.ui.text.AnnotatedString(label),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 9.sp,
                    color = onSurfaceVariant
                )
            )
            val w = layout.size.width.toFloat()
            val h = layout.size.height.toFloat()
            val candidates = listOf(
                Offset(x - w / 2, y - h - 6f),
                Offset(x - w / 2, y + 14f),
                Offset(x + 12f, y - h / 2),
                Offset(x - w - 12f, y - h / 2)
            )
            val chosen = candidates.firstOrNull { pos ->
                val rect = androidx.compose.ui.geometry.Rect(
                    pos, androidx.compose.ui.geometry.Size(w, h)
                )
                placedLabels.none { it.overlaps(rect) }
            }
            if (chosen != null) {
                drawText(layout, topLeft = chosen)
                placedLabels += androidx.compose.ui.geometry.Rect(
                    chosen, androidx.compose.ui.geometry.Size(w, h)
                )
            }
        }
    }
}
