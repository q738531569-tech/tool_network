package com.installer.toolbox.ui.screens.settings

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.common.util.UsageStats
import com.installer.toolbox.core.agent.support.CrashCollector
import com.installer.toolbox.core.agent.support.FeedbackHelper
import com.installer.toolbox.util.ShareHelper

/**
 * 诊断信息页：设备/系统/App 信息 + 功能使用统计 + 崩溃日志，
 * 出问题时装维可一键导出（含崩溃日志与统计）发给开发者，方便定位与优化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticInfoScreen() {
    val context = LocalContext.current
    var expandedCrash by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    fun refresh() {
        refreshTick++
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("诊断信息") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "以下信息仅保存在本机、不上传；『导出诊断包』可主动分享给开发者（含网络信息前会提示勾选）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))

            // 设备信息
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "设备信息",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = FeedbackHelper.buildDiagnosticText(context, includeNetwork = false, includeUsage = false)
                            .replace("=== 装维工具箱 诊断信息 ===", "")
                            .replace("生成时间：.*\n".toRegex(), ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // 功能使用统计
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = "功能使用统计（本机累计，启动 ${UsageStats.appStarts(context)} 次）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { UsageStats.reset(context); refresh() }) {
                            Text("重置统计")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val all = UsageStats.all(context)
                    if (all.isEmpty()) {
                        Text(
                            text = "暂无使用记录（使用各功能后自动统计）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        all.forEach { (k, v) ->
                            Text(
                                text = "${UsageStats.LABELS[k] ?: k}：$v 次",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // 崩溃日志
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = "崩溃日志",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        val logs = CrashCollector.logs(context)
                        if (logs.isNotEmpty()) {
                            TextButton(onClick = { CrashCollector.clear(context); refresh() }) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("清空")
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val logs = CrashCollector.logs(context)
                    if (logs.isEmpty()) {
                        Text(
                            text = "暂无崩溃记录（App 运行正常）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "共 ${logs.size} 份（点文件名查看内容）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        logs.forEach { f ->
                            TextButton(onClick = { expandedCrash = f.readText() }) {
                                Text("· ${f.name}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // 崩溃内容展开
            expandedCrash?.let { txt ->
                Spacer(Modifier.height(10.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                text = "崩溃详情",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { expandedCrash = null }) {
                                Text("收起")
                            }
                        }
                        Text(
                            text = txt.take(1500) + if (txt.length > 1500) "……" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 导出诊断包（含崩溃日志 + 统计 + 设备信息；网络信息可选）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val sb = StringBuilder(FeedbackHelper.buildDiagnosticText(context, includeNetwork = false, includeUsage = true))
                        val logs = CrashCollector.logs(context)
                        if (logs.isNotEmpty()) {
                            sb.appendLine()
                            sb.appendLine("=== 崩溃日志 ===")
                            logs.forEach { f ->
                                sb.appendLine("---- ${f.name} ----")
                                sb.appendLine(f.readText().take(2000))
                            }
                        }
                        ShareHelper.shareText(context, "装维工具箱诊断包", sb.toString())
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导出诊断包")
                }
                OutlinedButton(
                    onClick = {
                        val sb = StringBuilder(FeedbackHelper.buildDiagnosticText(context, includeNetwork = true, includeUsage = true))
                        ShareHelper.shareText(context, "装维工具箱诊断包（含网络信息）", sb.toString())
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("导出（含网络）")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "提示：『含网络』导出的诊断包包含 Wi-Fi 名称/局域网 IP/网关等现场信息，发送前请确认可外发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
