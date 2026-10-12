package com.installer.toolbox.ui.screens.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.cable.CableProbe
import kotlinx.coroutines.launch

/**
 * 网线测试（需求 2026-08-17）：PDA 有线网口直插被测网线，root 读 sysfs 判定
 * 断芯/短芯/物理层异常。网口由用户自己手动开启（2026-08-18 用户拍板：不再自动拉起），
 * 工具只读采样判定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CableTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootAvailable = remember { RootShell.isAvailable() }

    var ifaces by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedIface by remember { mutableStateOf<String?>(null) }
    var durationSec by remember { mutableStateOf("10") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CableProbe.CableResult?>(null) }
    var sampleText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 进页时枚举网口
    LaunchedEffect(rootAvailable) {
        if (rootAvailable) {
            val list = CableProbe.findEthernetInterfaces { RootShell.run(it) }
            ifaces = list
            selectedIface = list.firstOrNull()
            if (list.isEmpty()) error = "未发现有线网口（eth*/en*）。请确认设备有网口且已插入网线"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网线测试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // root 状态提示（常驻）
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = if (rootAvailable)
                            "✅ 已检测到 root：可读取网口 carrier/速率/错误帧（网口请自行在系统里开启）"
                        else
                            "⚠ 未检测到 root 权限：网线测试不可用（需 root 读网口状态）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (rootAvailable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    if (!rootAvailable) {
                        Text(
                            text = "有网口的设备（如 PDA）需 Magisk root 才能测；无 root 无法读取网口内核状态",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            if (rootAvailable) {
                // 网口选择
                if (ifaces.isNotEmpty()) {
                    Text("网口", style = MaterialTheme.typography.titleMedium)
                    // 简单下拉（网口数通常 1-2 个，用 FilterChip 行展示）
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ifaces.forEach { name ->
                            FilterChip(
                                selected = selectedIface == name,
                                onClick = { selectedIface = name },
                                label = { Text(name) }
                            )
                        }
                    }
                } else {
                    Text(
                        text = "未发现有线网口：请把被测网线插入设备网口后重进本页（或点下方按钮重扫）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Button(onClick = {
                        val list = CableProbe.findEthernetInterfaces { RootShell.run(it) }
                        ifaces = list
                        selectedIface = list.firstOrNull()
                        error = if (list.isEmpty()) "仍未发现有线网口" else null
                    }) { Text("重新扫描网口") }
                }

                // 时长配置（默认值+可填）
                OutlinedTextField(
                    value = durationSec,
                    onValueChange = { durationSec = it.filter { c -> c.isDigit() } },
                    label = { Text("检测时长（秒）") },
                    supportingText = { Text("范围 5-30，默认 10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 开始检测
                Button(
                    onClick = {
                        val iface = selectedIface
                        if (iface == null) { error = "请先选择网口"; return@Button }
                        val sec = durationSec.toIntOrNull()?.coerceIn(5, 30) ?: 10
                        testing = true
                        error = null
                        result = null
                        sampleText = ""
                        scope.launch {
                            val r = CableProbe.test(iface, { RootShell.run(it) }, sec) { sample ->
                                sampleText = "carrier=${sample.carrier} · ${sample.speedMbps ?: "?"}M · ${sample.duplex ?: "-"} · CRC 增长 ${sample.crcDelta}"
                            }
                            result = r
                            testing = false
                        }
                    },
                    enabled = !testing && selectedIface != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (testing) "检测中…（维持网口开启中）" else "开始检测")
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (testing) {
                    Text(sampleText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                // 结果卡
                result?.let { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = r.verdict,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (r.verdict.startsWith("✅")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("网口：${r.interfaceName ?: "-"} · link：${r.carrier ?: "-"}（1=通） · 速率：${r.speedMbps ?: "-"}M · 双工：${r.duplex ?: "-"}")
                            Text(
                                "错误帧（窗口增长）：CRC ${r.crcDelta} · 帧错误 ${r.rxFrameErrors ?: 0} → 总 ${r.rxErrors ?: 0}"
                            )
                            if (r.lossPercent != null) {
                                Text(
                                    "ping 对端（丢包率）：${r.lossPercent}% · 延迟 ${r.avgMs?.let { "%.1f".format(it) } ?: "-"}ms · 抖动 ${r.jitterMs?.let { "%.1f".format(it) } ?: "-"}ms"
                                )
                            }
                            r.note?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "说明：被测网线一头插设备网口（请先在系统里手动开启有线网口），另一头必须接通电的对端（交换机/路由器/光猫）——网卡靠对端协商出速率，对端不接或悬空测不出。判定依据：link 不通=线断/严重短路；速率 100M=8 芯只剩 2 对工作（断芯/短芯）；错误帧暴涨=短路/干扰/接触不良；**ping 对端丢包率高=线质量差（丢包严重直接判断）**。测不出哪对芯断/短、测不出短路位置（需专业线缆仪/万用表），轻微短路且链路照常时也可能不报。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
