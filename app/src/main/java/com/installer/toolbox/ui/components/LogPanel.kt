package com.installer.toolbox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 运行日志面板：工具执行过程全可见（折叠；执行中自动展开滚动；异常行红色）。
 *
 * 各人用功能页（流量审计 / PCDN 检测 / 环路检测 / 静默模式 / 设备发现 / 网络诊断 /
 * ARP 欺骗检测 / 网关冲突检测）在结果区下方复用本组件，把 root 命令执行过程打印出来，
 * 便于用户反馈改进。无日志时整体不显示。
 *
 * **自动滚屏**：展开状态下新日志到达时自动滚到最新一行（执行过程实时可见，不用手动拖）。
 */
@Composable
fun LogPanel(logs: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    if (logs.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📋 运行日志（${logs.size} 条）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起日志" else "展开日志",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            AnimatedVisibility(visible = expanded) {
                val scrollState = rememberScrollState()
                // 每次展开时滚到底部一次（跟随最新），之后不再自动滚——用户可自由滑动翻阅
                LaunchedEffect(expanded) {
                    if (expanded && logs.isNotEmpty()) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                ) {
                    logs.takeLast(200).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.contains("✖") || line.contains("失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
