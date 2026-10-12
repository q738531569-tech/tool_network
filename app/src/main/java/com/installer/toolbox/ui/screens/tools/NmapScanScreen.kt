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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.network.util.IpUtil
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.scan.NmapScanner
import com.installer.toolbox.core.network.scan.NmapScanner.NmapResult
import kotlinx.coroutines.launch

/**
 * nmap 扫描页：目标 + 参数输入，通过 root 调用内置 nmap（APK 已内置，无需 Termux）
 * 做深度扫描（服务与版本探测），展示端口列表与原始输出摘要。需要 root。
 */
@Composable
fun NmapScanScreen() {
    var target by remember { mutableStateOf("") }
    var options by remember { mutableStateOf("-Pn -sV --top-ports 100 --open") }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NmapResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 进入页面时在 IO 线程检测一次内置 nmap 是否可用（解压 + root 复制，耗时）
    var nmapAvailable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        nmapAvailable = NmapScanner.isBuiltinNmapAvailable(context, RootShell::run)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "nmap 扫描",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "通过 root 调用内置 nmap（APK 已内置，无需安装 Termux）对目标做深度扫描（服务与版本探测）。需要 root 权限；未检测到 root 时内置 nmap 不可用，可改用『网络诊断』里的端口扫描（免 root）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        if (nmapAvailable == false) {
            Text(
                text = "内置 nmap 不可用：执行需要 root（未检测到 root，或内置二进制初始化失败）。可改用『网络诊断』端口扫描（免 root，功能较精简）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text("目标 IP / 域名") },
            placeholder = { Text("如 192.168.1.5 或 example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = options,
            onValueChange = { options = it },
            label = { Text("nmap 参数") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                scope.launch {
                    val t = target.trim()
                    if (t.isBlank()) {
                        error = "请填写目标 IP 或域名"
                        return@launch
                    }
                    // 若看起来像 IP 则校验格式（域名允许）
                    if (t.matches(Regex("""[\d.]+""")) && !IpUtil.isValidIp(t)) {
                        error = "目标 IP 格式不正确（需为点分十进制，如 192.168.1.5；域名也可）"
                        return@launch
                    }
                    scanning = true
                    error = null
                    result = try {
                        NmapScanner.scan(context, t, options.ifBlank { "-Pn -sV --top-ports 100 --open" }, rootRun = RootShell::run)
                    } catch (e: Exception) {
                        error = "扫描失败：${e.message ?: e.javaClass.simpleName}"
                        null
                    }
                    scanning = false
                }
            },
            enabled = !scanning && target.isNotBlank()
        ) {
            Text(if (scanning) "扫描中…（可能需要数十秒）" else "开始扫描")
        }
        Spacer(Modifier.height(12.dp))

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        result?.let { r ->
            r.note?.let {
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (r.ports.isNotEmpty()) {
                Text(
                    text = "开放端口（${r.ports.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                r.ports.forEach { p ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = "${p.port}/tcp",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(76.dp)
                            )
                            Text(
                                text = p.state,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(64.dp)
                            )
                            Text(
                                text = buildString {
                                    append(p.service ?: "未知服务")
                                    p.version?.let { append("  $it") }
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (r.raw.isNotBlank()) {
                Text(
                    text = "原始输出摘要",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        text = r.raw.take(1200),
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
