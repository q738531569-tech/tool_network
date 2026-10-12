package com.installer.toolbox.ui.screens.tools

import android.content.Context
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.diagnose.WakeOnLan
import com.installer.toolbox.core.network.discovery.DeviceScanner
import com.installer.toolbox.core.network.discovery.HostInfo
import com.installer.toolbox.core.network.scan.LanInterfaceResolver
import kotlinx.coroutines.launch

/**
 * 局域网唤醒（Wake-on-LAN）页：输入目标设备网卡 MAC（或从扫描的设备列表直接点选），
 * 向局域网广播 magic packet 远程开机。
 *
 * 无需 root（UDP 广播无需特殊权限）；需目标设备 BIOS/网卡开启网络唤醒，
 * 且目标处于关机/休眠状态、与手机在同一局域网。
 * 记住上次发送的 MAC，方便下次直接发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeOnLanScreen() {
    var sending by remember { mutableStateOf(false) }
    // 上次发送结果：true 成功 / false 失败 / null 尚无结果
    var sentOk by remember { mutableStateOf<Boolean?>(null) }
    var macText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    // 设备选择（免手输 MAC）
    var devices by remember { mutableStateOf<List<HostInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanMsg by remember { mutableStateOf<String?>(null) }
    val rootOk = remember { RootShell.isAvailable() }

    // 记住上次 MAC（SharedPreferences 简化实现）
    val prefs = remember { context.getSharedPreferences("wol", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        macText = prefs.getString("last_mac", "") ?: ""
    }

    fun send() {
        if (sending) return
        val mac = macText.trim()
        sentOk = null
        sending = true
        scope.launch {
            // 绑定局域网接口：多网络时广播不从移动数据默认路由误发
            val bindIp = if (rootOk) {
                try { LanInterfaceResolver.resolve(RootShell::run)?.ip } catch (e: Exception) { null }
            } else null
            sentOk = WakeOnLan.wake(mac, bindIp = bindIp)
            if (sentOk == true) {
                // 发送成功才记住（失败多半是 MAC 还没输对，不强记）
                prefs.edit().putString("last_mac", mac).apply()
            }
            sending = false
        }
    }

    fun scanDevices() {
        if (scanning) return
        scanning = true
        scanMsg = null
        scope.launch {
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
            scanMsg = if (devices.isEmpty()) "未扫描到设备（确认与目标在同一局域网）" else null
            scanning = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("局域网唤醒") },
                navigationIcon = {
                    IconButton(onClick = { backDispatcher?.onBackPressed() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "输入目标设备的网卡 MAC（或从下方设备列表直接点选），向局域网广播唤醒指令，让关机/休眠的电脑远程开机。无需 root；需目标设备 BIOS/系统开启网络唤醒，且手机与目标在同一局域网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = macText,
                onValueChange = {
                    macText = it
                    sentOk = null
                },
                label = { Text("设备 MAC 地址") },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                supportingText = { Text("目标设备的网卡 MAC，格式 XX:XX:XX:XX:XX:XX") },
                singleLine = true,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = ::send,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (sending) "发送中…" else "发送唤醒包")
            }
            Spacer(Modifier.height(12.dp))

            // 结果提示卡片（成功绿 / 失败红）
            sentOk?.let { ok ->
                val bg: Color
                val fg: Color
                val label: String
                val detail: String
                if (ok) {
                    bg = Color(0xFF2E7D32).copy(alpha = 0.15f)
                    fg = Color(0xFF2E7D32)
                    label = "✓ 唤醒包已发送"
                    detail = "唤醒包已发送到局域网（目标需开启网络唤醒）"
                } else {
                    bg = MaterialTheme.colorScheme.errorContainer
                    fg = MaterialTheme.colorScheme.onErrorContainer
                    label = "✗ 发送失败"
                    detail = "MAC 格式不正确"
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = bg)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = fg
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            // ============ 设备列表选择（免手输 MAC） ============
            Text(
                text = "从扫描的设备里选（免手输 MAC）：",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = ::scanDevices,
                enabled = !scanning && !sending,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (scanning) "扫描中…" else "扫描局域网设备")
            }
            Spacer(Modifier.height(8.dp))
            scanMsg?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
            }
            // 只列有 MAC 的设备（无 MAC 无法唤醒）；点卡片直接填入输入框
            devices.filter { !it.mac.isNullOrBlank() }.forEach { host ->
                val selected = host.mac == macText.trim()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(enabled = !sending) {
                            macText = host.mac ?: ""
                            sentOk = null
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = host.ip,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "MAC：${host.mac}" +
                                    (if (!host.vendor.isNullOrBlank()) "　厂商：${host.vendor}" else "") +
                                    (if (!host.hostname.isNullOrBlank()) "　${host.hostname}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (selected) {
                            Text(
                                text = "✓ 已选",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            if (devices.isNotEmpty() && devices.none { !it.mac.isNullOrBlank() }) {
                Text(
                    text = "扫描到的设备都没有 MAC 信息（root 模式下可获得更全的 MAC），请手动输入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
