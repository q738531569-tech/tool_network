package com.installer.toolbox.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.network.diagnose.SubnetCalculator

/**
 * IP 计算器：输入 IP + CIDR 前缀/掩码，计算网络地址、广播、可用范围等。
 * 纯本地计算，无需网络权限。
 */
@Composable
fun SubnetCalcScreen() {
    var ip by remember { mutableStateOf("192.168.1.5") }
    var prefixOrMask by remember { mutableStateOf("24") }
    var maskMode by remember { mutableStateOf(false) } // false=CIDR 前缀, true=掩码

    val result = SubnetCalculator.calculate(ip, prefixOrMask)
    val inputInvalid = result == null &&
        (ip.isNotBlank() && prefixOrMask.isNotBlank())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "IP 计算器",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "输入 IP 地址和子网前缀/掩码，实时计算网络信息（纯本地计算）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))

        // 输入区
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("IP 地址") },
                placeholder = { Text("如 192.168.1.5") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prefixOrMask,
                onValueChange = { prefixOrMask = it },
                label = { Text(if (maskMode) "子网掩码" else "CIDR 前缀") },
                placeholder = { Text(if (maskMode) "如 255.255.255.0" else "如 24") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { maskMode = !maskMode },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(if (maskMode) "改为前缀" else "改为掩码")
            }
        }

        Spacer(Modifier.height(8.dp))
        if (inputInvalid) {
            Text(
                text = "输入格式有误：IP 需为点分十进制（如 192.168.1.5），前缀 0-32 或合法连续掩码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(12.dp))

        // 结果卡片
        when {
            result != null -> {
                val r = result
                ResultRow("网络地址", r.network + "/" + r.prefix)
                ResultRow("广播地址", r.broadcast)
                ResultRow("可用主机范围", "${r.firstHost} ~ ${r.lastHost}")
                ResultRow("总主机数", r.totalHosts.toString())
                ResultRow("可用主机数", r.usableHosts.toString())
                ResultRow("子网掩码", r.mask)
                ResultRow("通配符掩码", r.wildcard)
                ResultRow("IP 类别", r.ipClass)
                ResultRow(
                    "地址类型",
                    when {
                        r.isReserved && r.isPrivate -> "私有 + 保留地址"
                        r.isPrivate -> "私有地址（内网）"
                        r.isReserved -> "保留/特殊地址"
                        else -> "公网地址"
                    }
                )
            }

            !inputInvalid -> {
                Text(
                    "等待输入…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 常用 CIDR 速查表
        Text(
            text = "常用 CIDR 速查",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        listOf(24, 25, 26, 27, 28, 29, 30).forEach { p ->
            val mask = SubnetCalculator.prefixToMask(p)
            val hosts = if (p >= 31) 0L else (1L shl (32 - p)) - 2
            QuickRow(prefix = p, mask = mask, hosts = hosts)
        }
    }
}

/** 结果行 */
@Composable
private fun ResultRow(label: String, value: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 速查表行 */
@Composable
private fun QuickRow(prefix: Int, mask: String, hosts: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = "/$prefix",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = mask,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "可用 ${hosts} 台",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
