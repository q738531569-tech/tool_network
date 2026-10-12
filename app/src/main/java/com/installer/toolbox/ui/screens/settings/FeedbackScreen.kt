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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.agent.support.FeedbackApi
import com.installer.toolbox.core.agent.support.FeedbackHelper
import kotlinx.coroutines.launch

/**
 * 反馈与建议页：输入反馈内容 + 选择类型 + 可选附带诊断信息/网络信息，
 * 一键提交到服务器（your-server.example 反馈接口，MySQL 入库）；
 * 提交失败时可用『复制留档』兜底（弱网/离线现场先留存，稍后再试）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(FeedbackHelper.FEEDBACK_TYPES[0]) }
    var content by remember { mutableStateOf("") }
    var includeDiagnostic by remember { mutableStateOf(true) }
    var includeNetwork by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }

    fun buildText(): String = FeedbackHelper.buildFeedbackText(
        context = context,
        type = type,
        content = content,
        includeDiagnostic = includeDiagnostic,
        includeNetwork = includeNetwork
    )

    fun submit() {
        if (submitting) return
        submitting = true
        status = null
        scope.launch {
            val r = FeedbackApi.submitFeedback(
                context = context,
                type = type,
                content = content,
                includeDiagnostic = includeDiagnostic,
                includeNetwork = includeNetwork
            )
            r.onSuccess {
                statusOk = true
                status = "✅ 反馈已提交成功，感谢您的反馈！"
            }.onFailure { e ->
                statusOk = false
                status = "❌ 提交失败：${e.message ?: e.javaClass.simpleName}——请检查网络后重试，或点『复制内容』先留存。"
            }
            submitting = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("反馈与建议") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "工具用着不顺、想要新功能、遇到 Bug？直接一键提交到服务器（无需邮箱/微信），我们会根据反馈持续优化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))

            // 反馈类型
            Text("反馈类型", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackHelper.FEEDBACK_TYPES.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) })
                }
            }
            Spacer(Modifier.height(12.dp))

            // 反馈内容
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("反馈内容") },
                placeholder = { Text("例如：PCDN 检测把双镜头摄像头标成可疑；希望增加 XX 功能…") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                minLines = 6
            )
            Spacer(Modifier.height(8.dp))

            // 附带信息开关
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeDiagnostic,
                            onCheckedChange = { includeDiagnostic = it }
                        )
                        Text(
                            text = "附带诊断信息（版本/机型/系统/root 状态/使用统计/崩溃日志条数）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeNetwork,
                            onCheckedChange = { includeNetwork = it }
                        )
                        Text(
                            text = "附带当前网络信息（Wi-Fi 名称/局域网 IP/网关——含现场信息，请确认可外发后再勾选）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 提交与复制兜底
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { submit() },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (submitting) "提交中…" else "一键提交反馈")
                }
                Button(
                    onClick = { clipboard.setText(AnnotatedString(buildText())) },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制留档")
                }
            }
            Spacer(Modifier.height(8.dp))
            status?.let { s ->
                Text(
                    text = s,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = "提示：提交内容包括反馈内容${if (includeDiagnostic) "、诊断信息" else ""}${if (includeNetwork) "、网络信息" else ""}；提交失败时可用『复制留档』保存内容稍后再试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
