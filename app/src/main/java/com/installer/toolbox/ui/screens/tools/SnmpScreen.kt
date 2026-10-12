package com.installer.toolbox.ui.screens.tools

import android.content.Context
import android.net.wifi.WifiManager
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.diagnose.SnmpProbe
import com.installer.toolbox.core.network.diagnose.SnmpProbe.SnmpResult
import com.installer.toolbox.core.network.scan.LanInterfaceResolver
import com.installer.toolbox.util.ExportReportButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SNMP 面板：输入设备 IP + community，用 SNMP v2c 查询设备系统信息
 * （名称/描述/运行时间/位置/联系人），快速了解路由器/交换机/打印机等支持 SNMP 的设备。
 * 目标留空时自动取当前网关（取不到网关默认 192.168.1.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnmpScreen() {
    var querying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SnmpResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ipText by remember { mutableStateOf("") }
    var communityText by remember { mutableStateOf("public") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun start() {
        // 目标为空 → 自动取当前网关；取不到网关兜底 192.168.1.1
        val target = ipText.trim().ifEmpty { currentGateway(context) ?: "192.168.1.1" }
        // 局域网接口绑定：root 时解析局域网 IP 作源地址（PDA 有线/局域网无外网时
        // 查询不走移动数据默认路由，否则 SNMP 必超时）；解析失败保持系统默认
        val bindIp = if (RootShell.isAvailable()) {
            try { LanInterfaceResolver.resolve(RootShell::run)?.ip } catch (e: Exception) { null }
        } else null
        querying = true
        error = null
        result = null
        scope.launch {
            try {
                result = SnmpProbe.query(
                    ip = target,
                    community = communityText.trim().ifEmpty { "public" },
                    bindIp = bindIp
                )
            } catch (e: Exception) {
                error = "查询失败：${e.message ?: e.javaClass.simpleName}"
            }
            querying = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SNMP 面板") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "用 SNMP v2c 查询设备的系统信息（名称/描述/运行时间/位置/联系人），快速了解路由器、交换机、打印机等设备。无需 root，需目标设备开启 SNMP 服务（默认 community 为 public）。目标留空时自动查询当前网关。\n相关功能：不知道设备 IP？先用『设备发现』扫描。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            // 目标 IP 输入（留空 = 自动取当前网关）
            OutlinedTextField(
                value = ipText,
                onValueChange = { ipText = it },
                label = { Text("目标 IP") },
                placeholder = { Text("默认当前网关") },
                singleLine = true,
                enabled = !querying,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            // community 输入 + 查询按钮
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = communityText,
                    onValueChange = { communityText = it },
                    label = { Text("Community") },
                    supportingText = { Text("默认 public") },
                    singleLine = true,
                    enabled = !querying,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = ::start,
                    enabled = !querying,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(if (querying) "查询中…" else "查询")
                }
            }
            Spacer(Modifier.height(12.dp))

            // 顶层异常提示（查询本身不抛异常，此兜底极少触发）
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            result?.let { r ->
                val err = r.error
                if (err != null) {
                    // 查询失败：可读错误提示
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            text = err,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    // 设备系统信息卡片
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "设备系统信息",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "目标：${r.ip}　community：${r.community}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "名称：${r.sysName ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "描述：${r.sysDescr ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "运行时间：${r.sysUpTime ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "位置：${r.sysLocation ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "联系人：${r.sysContact ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 导出报告（装维留档；失败时也导出，报告里含 error 说明）
                ExportReportButton(
                    fileName = "SNMP 查询报告",
                    text = buildSnmpReportText(r),
                    modifier = Modifier.height(48.dp)
                )
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

/** SNMP 查询报告文本（导出用）：标题 + 时间 + IP + community + 各字段（失败含 error 说明） */
private fun buildSnmpReportText(r: SnmpResult): String = buildString {
    appendLine("装维通 · SNMP 查询报告")
    appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
    appendLine("目标：${r.ip}　community：${r.community}")
    if (r.error != null) {
        appendLine("结果：查询失败")
        appendLine("说明：${r.error}")
        return@buildString
    }
    appendLine("设备名称：${r.sysName ?: "-"}")
    appendLine("系统描述：${r.sysDescr ?: "-"}")
    appendLine("运行时间：${r.sysUpTime ?: "-"}")
    appendLine("位置：${r.sysLocation ?: "-"}")
    appendLine("联系人：${r.sysContact ?: "-"}")
}
