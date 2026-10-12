package com.installer.toolbox.ui.screens.wifi

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.network.wifi.DfsChecker
import com.installer.toolbox.core.network.wifi.WifiAnalyzer
import kotlinx.coroutines.launch

/**
 * 航空信号检测页（DFS 信道风险检测）：
 * 解决装维现场常见投诉——"飞机经过 Wi-Fi 就断网"。根因是路由器 5G 用了 DFS 信道
 * （与航空雷达共用频段），飞机经过触发雷达检测 → AP 自动跳信道静默 60 秒。
 * 本页扫描判定当前连接与周边 5G AP 的 DFS 信道占用，给出换非 DFS 信道建议。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AviationSignalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DfsChecker.DfsReport?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runCheck() {
        checking = true
        error = null
        scope.launch {
            report = try {
                DfsChecker.check(context)
            } catch (e: Exception) {
                error = e.message ?: "检测失败"
                null
            }
            checking = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            runCheck()
        } else {
            error = "未授予定位权限，无法扫描周边 Wi-Fi"
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("航空信号检测") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Button(
                onClick = {
                    if (WifiAnalyzer.hasScanPermission(context)) runCheck() else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                enabled = !checking
            ) { Text(if (checking) "检测中…（约 5 秒）" else "开始检测") }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "就是 5G 里与飞机雷达共用的那几条信道（动态选频 DFS）。原理：5GHz 频段部分信道（UNII-2：52-64，UNII-2e：100-140）为 DFS（Dynamic Frequency Selection，动态频率选择）信道，与航空雷达共用频谱。按法规，使用 DFS 信道的 AP 须持续监测雷达信号，一旦检测到即自动切换信道并进入约 60 秒静默期（Channel Availability Check），期间网络中断。本页检测当前连接及周边 5G AP 的 DFS 信道占用情况（DFS 仅存在于 5GHz，2.4G AP 不参与统计），辅助定位间歇性断网的频段原因。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(10.dp))

            error?.let {
                Text(text = "检测失败：$it", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            val r = report
            if (r != null) {
                // 结论卡
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (r.connectedIsDfs) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Text(
                        text = r.summary,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.connectedIsDfs) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))

                // DFS 信道 AP 列表（红色警告；0 个时给出明确空状态，避免误以为出错）
                if (r.dfsAps.isEmpty()) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = "✓ 未发现使用 DFS 信道的 5G AP（本网络无 AP 受雷达避让影响，属正常）",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        text = "⚠ 使用 DFS 信道的 5G AP（${r.dfsAps.size} 个，飞机经过可能断网）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    r.dfsAps.forEach { ap ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row2(
                                ssid = ap.ssid,
                                level = ap.levelDbm,
                                channel = ap.channel,
                                freq = ap.frequency,
                                isDfs = true
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // 非 DFS 信道 AP
                if (r.nonDfsAps.isNotEmpty()) {
                    Text(
                        text = "非 DFS 信道的 5G AP（${r.nonDfsAps.size} 个，无雷达避让）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    r.nonDfsAps.take(10).forEach { ap ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row2(
                                ssid = ap.ssid,
                                level = ap.levelDbm,
                                channel = ap.channel,
                                freq = ap.frequency,
                                isDfs = false
                            )
                        }
                    }
                    if (r.nonDfsAps.size > 10) {
                        Text(
                            text = "…共 ${r.nonDfsAps.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (r.dfsAps.isEmpty() && r.nonDfsAps.isEmpty()) {
                    Text(
                        text = "未扫描到 5G 周边 AP（可能周边没有 5G 网络或扫描受限）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "建议：若用户路由器 5G 用了 DFS 信道，登录路由器把 5G 信道固定为非 DFS（${DfsChecker.NON_DFS_CHANNELS.joinToString("/")}）即可避免『飞机经过断网』。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** AP 信息行（DFS 红色标注 / 非 DFS 常规） */
@Composable
private fun Row2(ssid: String, level: Int, channel: Int, freq: Int, isDfs: Boolean) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            text = ssid,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "CH$channel（$freq MHz）· ${level} dBm" +
                if (isDfs) "　⚠ DFS 信道（雷达共用）" else "　安全信道",
            style = MaterialTheme.typography.bodySmall,
            color = if (isDfs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
