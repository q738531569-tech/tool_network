package com.installer.toolbox.ui.screens.ai

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.installer.toolbox.core.agent.DeepSeekClient
import com.installer.toolbox.core.agent.support.AiMemory
import com.installer.toolbox.core.agent.support.AiNotes
import com.installer.toolbox.core.agent.support.CredentialVault
import com.installer.toolbox.ui.screens.ai.AiViewModel.UiMsg
import com.installer.toolbox.util.ShareHelper
import java.util.Calendar
import kotlinx.coroutines.delay

/** 状态点颜色：已连接 = 绿，未配置 = 橙 */
private val ConnectedGreen = Color(0xFF2E7D32)
private val WarnOrange = Color(0xFFEF6C00)

/**
 * AI 对话助手页（重构版）。
 *
 * 结构（自上而下）：
 * 1. 标题"AI 诊断助手" + 连接状态行（未配置可点击跳设置）
 * 2. 顶部操作：新建会话 / 会话列表 / 清空对话（消息非空时出现）
 * 3. 消息气泡列表（user 右 / assistant 左，自动滚底）
 * 4. 底部自由对话输入框（多行 + 发送按钮，busy 禁用，未配 Key 弹 Toast）
 *
 * API Key 管理已移到设置页，本页通过 [onNavigateToSettings] 跳转。
 */
@Composable
fun AiScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: AiViewModel = viewModel()
) {
    val keyReady by viewModel.apiKeyReady.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val checkingServer by viewModel.checkingServer.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val status by viewModel.status.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val shellLevel by viewModel.shellLevel.collectAsState()
    val pendingShell by viewModel.pendingShell.collectAsState()
    val cacheStats by viewModel.cacheStats.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val interrupted by viewModel.interrupted.collectAsState()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    var showShellLevelDialog by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var pendingDeleteSession by remember { mutableStateOf<String?>(null) }
    var pendingClearSession by remember { mutableStateOf<String?>(null) }
    var pendingDeleteRound by remember { mutableStateOf<Long?>(null) }
    // 重命名会话：id -> 当前会话 + 输入框内容（弹窗内编辑，确定时提交）
    var pendingRenameSession by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 回到本页（如从设置页返回）时刷新 API Key 状态
    LaunchedEffect(Unit) { viewModel.refreshKey() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // 键盘弹出：整个列（消息区+输入区）统一上移收缩，微信式
            .imePadding()
    ) {
        // ---- 1. 标题 + 连接状态行 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "AI 诊断助手",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            // root shell 审批档位按钮（在标题文字后面；点击弹出三档选择）
            TextButton(
                onClick = { showShellLevelDialog = true },
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    text = "🛡️ ${shellLevel.label}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when (shellLevel) {
                        AiViewModel.ShellLevel.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                        AiViewModel.ShellLevel.ASK -> MaterialTheme.colorScheme.primary
                        AiViewModel.ShellLevel.SESSION -> MaterialTheme.colorScheme.error
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showMemory = true }) {
                Icon(Icons.Filled.Psychology, contentDescription = "AI 记忆")
            }
            IconButton(onClick = { viewModel.newSession() }) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
            IconButton(onClick = { showSessions = true }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "会话列表")
            }
            if (messages.isNotEmpty()) {
                // 分享/清空收进"更多"菜单（此前直排导致分享按钮仅 14px、清空被挤出屏幕）
                Box {
                    IconButton(onClick = { showMore = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(
                            text = { Text("分享对话") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = {
                                showMore = false
                                val sb = StringBuilder()
                                sb.appendLine("装维通 · AI 诊断对话")
                                sb.appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                                messages.forEach { m ->
                                    sb.appendLine("${if (m.role == "user") "问" else "答"}: ${m.text}")
                                }
                                ShareHelper.shareText(context, "AI 诊断对话", sb.toString())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("清空会话消息", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.DeleteSweep,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMore = false
                                pendingClearSession = currentSessionId
                            }
                        )
                    }
                }
            }
        }
        // 远程控制已开启提示横幅（对话两端实时同步，本地照常可聊）
        val remotePrefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
        if (remotePrefs.getBoolean("enabled", false)) {
            Text(
                text = "🛰 远程控制已开启——本机与控制端对话实时同步，root 命令审批在控制端（ARP 类命令免审批）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (keyReady) ConnectedGreen else WarnOrange, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            if (keyReady) {
                Text(
                    text = when {
                        checkingServer -> "$modelName 正在检测连接…"
                        serverStatus == DeepSeekClient.ServerStatus.OK ->
                            "$modelName 连接服务器正常"
                        serverStatus == DeepSeekClient.ServerStatus.UNAUTHORIZED ->
                            "已连接服务器，但 API Key 无效（点此到「设置」检查）"
                        serverStatus == DeepSeekClient.ServerStatus.INSUFFICIENT_BALANCE ->
                            "⚠ DeepSeek 账户余额不足，请充值后重试（platform.deepseek.com）"
                        serverStatus == DeepSeekClient.ServerStatus.RATE_LIMITED ->
                            "⚠ 请求过于频繁被限流，请稍等片刻后重试"
                        serverStatus == DeepSeekClient.ServerStatus.FAIL ->
                            "无法连接 DeepSeek 服务器（请检查网络）"
                        else -> "$modelName 连接服务器正常"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (serverStatus) {
                        DeepSeekClient.ServerStatus.INSUFFICIENT_BALANCE,
                        DeepSeekClient.ServerStatus.RATE_LIMITED,
                        DeepSeekClient.ServerStatus.UNAUTHORIZED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                // 实时余额显示（每轮完成后与连接检测时自动刷新；查询失败静默不显示）
                val balance by viewModel.balance.collectAsState()
                if (!balance.isNullOrBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "💰 余额 $balance",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (serverStatus == DeepSeekClient.ServerStatus.INSUFFICIENT_BALANCE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            } else {
                Text(
                    text = "⚠ 未配置 API Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // 上下文缓存命中率（DeepSeek 自动缓存：前缀稳定即命中，输入价约 1/10）
        cacheStats?.cacheHitRate?.let { rate ->
            Text(
                text = "⚡ 上下文缓存命中 ${(rate * 100).toInt()}%（命中 ${cacheStats!!.cacheHitTokens / 1024}K / 未命中 ${cacheStats!!.cacheMissTokens / 1024}K token）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // ---- 3. 消息列表（微信式：reverseLayout 倒序，最新消息贴底部，键盘弹出自动上移）----
        val listState = rememberLazyListState()
        // 新消息自动切屏（reverseLayout 下 index 0 = 最新消息；发消息/新回复到达滚到底部，每动作一次）
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }
        // 流式内容增长（思考过程/工具日志/回复正文）自动跟随：仅在用户停留在最新消息附近且
        // 未主动滑动时跟随；用户上翻浏览历史时不抢焦点（可自由滑动，滚回底部恢复跟随）
        LaunchedEffect(messages) {
            if (messages.isNotEmpty() && !listState.isScrollInProgress) {
                val first = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                if (first == 0 && offset < 200) {
                    listState.scrollToItem(0)
                }
            }
        }
        // 工具开始执行时滚到底部（状态行紧跟当前气泡下方可见）
        LaunchedEffect(status) {
            if (status != null) listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true
        ) {
            // reverseLayout 第一个 item 视觉最底：中断提示紧跟最后一条气泡下方（气泡与输入框之间）
            if (interrupted && !busy) {
                item {
                    Text(
                        text = "⚠ 上次对话未完成（回复中断）。可直接重新提问，或忽略此提示继续。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                }
            }
            // reverseLayout 第一个 item 视觉最底：工具调用状态行紧跟当前气泡下方（气泡与输入框之间），带已耗时实时刷新
            status?.let { s ->
                item {
                    var elapsed by remember { mutableStateOf(0) }
                    LaunchedEffect(status) {
                        elapsed = 0
                        while (true) {
                            delay(1000)
                            elapsed++
                        }
                    }
                    Text(
                        text = "⚙ $s（已用 $elapsed 秒）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                }
            }
            if (messages.isEmpty()) {
                item {
                    if (keyReady) {
                        // 已配置：空状态引导文案
                        Text(
                            text = "AI 可代跑 47 个工具（扫描/诊断/摄像头/审计等），口述即可执行",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    } else {
                        // 未配置：引导卡片（图标 + 说明 + 去设置 + 注册链接）
                        KeyGuideCard(onNavigateToSettings)
                    }
                }
            }
            // reverseLayout：倒序渲染（最新在前/贴底）
            items(messages.asReversed()) { msg ->
                // busy 且为最后一条 assistant 即"本轮思考中"：思考过程保持展示（工具轮过渡语
                // 出现时也不折叠——只有本轮完成、正式输出落地后才折叠思考，与用户要求一致）
                val isThinking = busy && msg == messages.lastOrNull() && msg.role == "assistant"
                if (isThinking) {
                    // 思考中：有思考链时显示思考内容气泡（实时滚动），否则跳动省略号
                    if (!msg.reasoning.isNullOrBlank()) {
                        MessageBubble(msg, isThinking = true)
                    } else {
                        LoadingDots(isUser = false)
                    }
                } else {
                    // 空闲时可删该轮（user 或 assistant 气泡都触发，删除的是整轮）
                    MessageBubble(msg, onDeleteRound = if (!busy) {
                        { pendingDeleteRound = msg.timestamp }
                    } else null)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ---- 4. 底部输入区（紧凑一行；imePadding 在根列统一处理，键盘弹出整体上移）----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入你的问题…") },
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                // 进行中：发送按钮切换为停止按钮（终止当前轮，保留已产生的部分内容）
                Button(
                    onClick = { viewModel.stopCurrentRound() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            } else {
                Button(
                    onClick = {
                        when {
                            // 未配 Key：提示去设置（按钮保持可点，保证提示可达）
                            !keyReady -> Toast.makeText(
                                context, "请先到设置填写 API Key", Toast.LENGTH_SHORT
                            ).show()
                            input.isNotBlank() -> {
                                if (viewModel.sendText(input)) {
                                    input = ""
                                }
                            }
                        }
                    },
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("发送")
                }
            }
        }
    }

    // 清空会话确认弹窗（丢弃消息与上下文，保留记忆与 md 档案）
    pendingClearSession?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingClearSession = null },
            title = { Text("清空会话消息") },
            text = {
                Text(
                    "确定清空该会话的全部消息吗？\n\n" +
                        "· 清空后上下文重新开始（消息不可恢复）；\n" +
                        "· AI 记忆与资料档案（md）保留——重新聊天时 AI 仍记得关键信息；\n" +
                        "· 会话本身保留在会话列表，标题不变（手动命名的标题继续保留）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearSession(id)
                    pendingClearSession = null
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearSession = null }) { Text("取消") }
            }
        )
    }

    // 删除一轮对话确认弹窗（只删该轮 user+assistant 两条消息，记忆/档案不受影响）
    pendingDeleteRound?.let { ts ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRound = null },
            title = { Text("删除这一轮对话") },
            text = {
                Text(
                    "确定删除这一轮对话吗？\n\n" +
                        "· 该轮问题与回答将从会话中移除，不再进入 AI 上下文；\n" +
                        "· AI 记忆与资料档案不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRound(ts)
                    pendingDeleteRound = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRound = null }) { Text("取消") }
            }
        )
    }

    // 会话列表弹窗
    if (showSessions) {
        SessionListDialog(
            sessions = sessions,
            currentId = currentSessionId,
            onSwitch = {
                viewModel.switchSession(it)
                showSessions = false
            },
            onDelete = { pendingDeleteSession = it },
            onClear = { pendingClearSession = it },
            onRename = { id ->
                pendingRenameSession = id to (sessions.find { it.id == id }?.title ?: "")
            },
            onDismiss = { showSessions = false }
        )
    }

    // 删除会话确认弹窗（提示：该会话的 AI 记忆与资料档案将一并删除）
    pendingDeleteSession?.let { id ->
        val memoryCount = AiMemory.countBySession(context, id)
        val noteCount = AiNotes.list(context, id).size
        val credCount = CredentialVault.countBySession(context, id)
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            title = { Text("删除会话") },
            text = {
                Text(
                    when {
                        memoryCount > 0 && noteCount > 0 && credCount > 0 ->
                            "确定删除该会话吗？该会话中 AI 记忆的 $memoryCount 条信息、$noteCount 份资料档案（md）与 $credCount 条凭据（保险库密码）将一并删除（其他会话不受影响）。"
                        memoryCount > 0 && credCount > 0 ->
                            "确定删除该会话吗？该会话中 AI 记忆的 $memoryCount 条信息与 $credCount 条凭据（保险库密码）将一并删除（其他会话不受影响）。"
                        noteCount > 0 && credCount > 0 ->
                            "确定删除该会话吗？该会话的 $noteCount 份资料档案（md）与 $credCount 条凭据（保险库密码）将一并删除（其他会话不受影响）。"
                        memoryCount > 0 && noteCount > 0 ->
                            "确定删除该会话吗？该会话中 AI 记忆的 $memoryCount 条信息与 $noteCount 份资料档案（md）将一并删除（其他会话不受影响）。"
                        memoryCount > 0 ->
                            "确定删除该会话吗？该会话中 AI 记忆的 $memoryCount 条信息将一并删除（其他会话的记忆保留）。"
                        noteCount > 0 ->
                            "确定删除该会话吗？该会话的 $noteCount 份资料档案（md）将一并删除（其他会话不受影响）。"
                        credCount > 0 ->
                            "确定删除该会话吗？该会话的 $credCount 条凭据（保险库密码）将一并删除（其他会话不受影响）。"
                        else ->
                            "确定删除该会话吗？（该会话无 AI 记忆、资料档案与凭据）"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(id)
                    pendingDeleteSession = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSession = null }) { Text("取消") }
            }
        )
    }

    // 会话重命名弹窗（标题可编辑：手动命名后不再被首条消息自动覆盖）
    pendingRenameSession?.let { (id, initial) ->
        // 预填当前标题并全选（直接打字即整体替换）；自动聚焦弹出键盘
        var titleState by remember(id) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        val submit = {
            if (titleState.text.isNotBlank()) {
                viewModel.renameSession(id, titleState.text)
                pendingRenameSession = null
            }
        }
        AlertDialog(
            onDismissRequest = { pendingRenameSession = null },
            title = { Text("重命名会话") },
            text = {
                Column {
                    Text(
                        "标题会在会话列表中完整展示；手动命名后不再随新消息自动变化。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = titleState,
                        onValueChange = { titleState = it.copy(text = it.text.take(32)) },
                        label = { Text("会话标题") },
                        singleLine = true,
                        supportingText = { Text("${titleState.text.length}/32") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = titleState.text.isNotBlank(),
                    onClick = { submit() }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameSession = null }) { Text("取消") }
            }
        )
    }

    // AI 记忆管理弹窗（按会话隔离：只显示当前会话的记忆与凭据；一个会话一个客户，不跨会话串）
    if (showMemory) {
        val items = remember(showMemory, currentSessionId) { AiMemory.ofSession(context, currentSessionId) }
        val creds = remember(showMemory, currentSessionId) { CredentialVault.list(context, currentSessionId) }
        AlertDialog(
            onDismissRequest = { showMemory = false },
            title = { Text("本会话记忆（${items.size} 条 · 凭据 ${creds.size} 条）") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (items.isEmpty() && creds.isEmpty()) {
                        Text(
                            text = "本会话暂无记忆与凭据。AI 在当前会话中发现重要信息（网络拓扑/排查结论等）会自动记忆，发现密码会自动存进加密保险库；每个会话对应一个客户，互不串用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        items.forEachIndexed { i, item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${i + 1}. ${item.text}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    AiMemory.remove(context, item.text)
                                    showMemory = false
                                }) {
                                    Text("删", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (creds.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "🔐 凭据保险库（AES 加密存储；点眼睛可临时查看密码值，关闭弹窗自动隐藏）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            // 已展开查看密码值的凭据 id（仅弹窗内内存态，关闭弹窗自动清空）
                            var revealedCreds by remember(showMemory, currentSessionId) { mutableStateOf<Set<String>>(emptySet()) }
                            creds.forEachIndexed { i, c ->
                                val revealed = c.id in revealedCreds
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${i + 1}. ${c.label}${if (c.device.isNotBlank()) "（${c.device}）" else ""} · ${c.type}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        // 眼睛按钮：切换显示/隐藏密码值（内置 AES 解密，仅弹窗内可见）
                                        IconButton(
                                            onClick = {
                                                revealedCreds = if (revealed) revealedCreds - c.id else revealedCreds + c.id
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                contentDescription = if (revealed) "隐藏密码值" else "查看密码值",
                                                tint = if (revealed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        TextButton(onClick = {
                                            CredentialVault.remove(context, c.id)
                                            showMemory = false
                                        }) {
                                            Text("删", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    if (revealed) {
                                        val value = remember(c.id, revealedCreds) {
                                            CredentialVault.getValue(context, c.id).orEmpty()
                                        }
                                        SelectionContainer {
                                            Text(
                                                text = "密码值：$value",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMemory = false }) { Text("关闭") }
            }
        )
    }

    // root shell 审批档位选择弹窗（标题旁按钮触发）
    if (showShellLevelDialog) {
        AlertDialog(
            onDismissRequest = { showShellLevelDialog = false },
            title = { Text("root 命令审批档位") },
            text = {
                Column {
                    Text(
                        text = "AI 调用 root_shell 执行命令前如何审批：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    AiViewModel.ShellLevel.entries.forEach { level ->
                        TextButton(
                            onClick = {
                                viewModel.setShellLevel(level)
                                showShellLevelDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (shellLevel == level) "✔ ${level.label}：${level.desc}" else "${level.label}：${level.desc}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (level == AiViewModel.ShellLevel.SESSION) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShellLevelDialog = false }) { Text("关闭") }
            }
        )
    }

    // root shell 命令审批弹窗（每步审批档位下，AI 请求执行 root 命令时弹出）
    pendingShell?.let { p ->
        AlertDialog(
            onDismissRequest = { viewModel.approvePendingShell(false) },
            title = { Text("root shell 命令审批") },
            text = {
                Column {
                    Text(
                        text = "AI 请求以 root 权限执行以下命令：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = p.command,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "⚠ root 命令可能修改系统/网络配置或删除数据，请确认命令内容安全后再允许。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.approvePendingShell(true) }) {
                    Text("允许执行", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.approvePendingShell(false) }) {
                    Text("拒绝")
                }
            }
        )
    }
}

/** 会话列表弹窗（微信式）：第一行标题完整展示，第二行时间 + 功能按钮（清空/重命名/删除） */
@Composable
private fun SessionListDialog(
    sessions: List<AiViewModel.ChatSession>,
    currentId: String,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: (String) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话列表（历史上下文）") },
        text = {
            if (sessions.isEmpty()) {
                Text("暂无会话，点击右上角 + 新建")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(sessions) { s ->
                        // 两行布局：行 1 标题（完整），行 2 时间 + 清空/重命名/删除按钮
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSwitch(s.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            // 当前会话标记
                            Text(
                                text = if (s.id == currentId) "●" else "○",
                                color = if (s.id == currentId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                // 第一行：标题完整展示（两行截断兜底，正常一行放得下）
                                Text(
                                    text = s.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (s.id == currentId) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(Modifier.height(4.dp))
                                // 第二行：时间 + 功能按钮
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = formatMsgTime(s.updatedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    // 清空会话消息（保留会话/记忆/档案，走确认弹窗）
                                    IconButton(
                                        onClick = { onClear(s.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.DeleteSweep,
                                            contentDescription = "清空会话消息",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    // 重命名会话（标题可编辑）
                                    IconButton(
                                        onClick = { onRename(s.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Edit,
                                            contentDescription = "重命名会话",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    // 删除会话（连带删记忆与档案）
                                    IconButton(
                                        onClick = { onDelete(s.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除会话",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 未配置 API Key 的引导卡片（空状态居中展示，替代原来的两行小字） */
@Composable
private fun KeyGuideCard(onNavigateToSettings: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "启用 AI 排障助手",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "在「设置」中填写 DeepSeek API Key 后，直接提问即可——AI 会自动调用扫描 / Ping / 端口等工具帮你排查网络问题。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onNavigateToSettings) {
                Text("去设置填写 API Key")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildAnnotatedString {
                    append("没有 Key？")
                    withLink(LinkAnnotation.Clickable("https://platform.deepseek.com") {
                        uriHandler.openUri("https://platform.deepseek.com")
                    }) {
                        append(" ")
                        pushStyle(SpanStyle(color = Color(0xFF1565C0), textDecoration = TextDecoration.Underline))
                        append("platform.deepseek.com")
                        pop()
                        append(" 注册创建")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单条消息气泡：user 右对齐（主题色容器），assistant 左对齐（surfaceVariant 容器），
 * 气泡下方显示相对时间戳（今天 HH:mm / 昨天 / 日期）；
 * assistant 消息：思考过程折叠（思考中实时展开、完成默认收起）+ 工具调用记录折叠；
 * onDeleteRound 非空时时间戳旁显示 🗑（删除该轮 user+assistant，不再进入 AI 上下文） */
@Composable
private fun MessageBubble(msg: UiMsg, isThinking: Boolean = false, onDeleteRound: (() -> Unit)? = null) {
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // 思考过程（仅 assistant）：思考中默认展开实时打印；本轮完成（正式输出落地）后
                // 自动折叠，随时可点开回看（同一条消息内用户手动展开/收起不被流式更新重置）
                if (!msg.reasoning.isNullOrBlank()) {
                    var reasoningExpanded by remember(msg.timestamp) { mutableStateOf(isThinking) }
                    // 正式输出出现（本轮完成）→ 折叠思考；思考/工具执行期间保持展示
                    LaunchedEffect(isThinking) {
                        if (!isThinking) reasoningExpanded = false
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reasoningExpanded = !reasoningExpanded }
                    ) {
                        Text(
                            text = "💭 思考过程（${msg.reasoning!!.length} 字）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (reasoningExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (reasoningExpanded) "收起思考过程" else "展开思考过程",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    AnimatedVisibility(visible = reasoningExpanded) {
                        SelectionContainer {
                            Text(
                                text = msg.reasoning.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                // 工具调用记录（仅 assistant）：折叠卡片，展开看输入/输出/过程日志；
                // 正在执行的最后一条默认展开（实时滚动看过程）
                if (msg.toolCalls.isNotEmpty()) {
                    msg.toolCalls.forEachIndexed { i, tc ->
                        val running = isThinking && i == msg.toolCalls.size - 1
                        var tcExpanded by remember(tc, running) { mutableStateOf(running) }
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { tcExpanded = !tcExpanded }
                            ) {
                                Text(
                                    text = "🔧 工具 ${i + 1}/${msg.toolCalls.size}：${tc.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (tcExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (tcExpanded) "收起工具详情" else "展开工具详情",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            AnimatedVisibility(visible = tcExpanded) {
                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                    if (tc.input.isNotBlank()) {
                                        Text(
                                            text = "入参：${tc.input.take(300)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "输出：${tc.output.take(800)}${if (tc.output.length > 800) "……" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                // 气泡正文（可选择复制；与上方思考/工具块隔开一点，层次更清晰）
                SelectionContainer {
                    Text(
                        text = msg.text.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        // 时间戳（气泡下方小字；assistant 带本轮耗时；空闲时可删整轮 🗑）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = formatMsgTime(msg.timestamp) +
                    if (!isUser && msg.roundSec > 0) "　·　本轮 ${msg.roundSec} 秒" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (onDeleteRound != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除这一轮对话",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onDeleteRound() }
                )
            }
        }
    }
}

/** 消息/会话时间格式化：今天 HH:mm；昨天 HH:mm；今年 M月d日；更早 yyyy/M/d */
private fun formatMsgTime(ts: Long): String {
    if (ts <= 0) return ""
    // ts 异常小（< 2001 年，如旧消息合成 ts=1,2,3…）显示「更早」，避免出现 1970 年
    if (ts < 1000000000000L) return "更早"
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    val hm = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    return when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> hm
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天 $hm"
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $hm"
        else -> "${cal.get(Calendar.YEAR)}/${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
    }
}

/** AI 思考中的跳动省略号气泡（三个点上下跳动，模拟打字机等待） */
@Composable
private fun LoadingDots(isUser: Boolean) {
    // 三个圆点循环上下跳动
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(350)
            step = (step + 1) % 3
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(3) { i ->
                    val up = i == step
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .graphicsLayer {
                                translationY = if (up) -4f else 0f
                            }
                            .background(
                                color = MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}
