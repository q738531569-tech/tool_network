package com.installer.toolbox.ui.screens.remote

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Calendar
import kotlinx.coroutines.delay

/** 在线状态绿点 / 离线灰点 */
private val OnlineGreen = Color(0xFF2E7D32)
private val OfflineGray = Color(0xFFBDBDBD)

/** 离线提示横幅暖橙色 */
private val OfflineBannerOrange = Color(0xFFD46B08)

/**
 * 远程控制页：App 内置远程控制台（装维不用浏览器，App 内直接登录控制台远程操控另一台 PDA）。
 *
 * 三阶段状态机（由 [RemoteControlViewModel.phase] 驱动）：
 * 1. 登录页：账号/密码 + 登录/注册；
 * 2. 设备列表：账号名下设备（在线/电量/SSID）+ 全局授权勾选 + 连接；
 * 3. 控制台：对齐 App 内 AI 助手页体验——消息气泡 + 💭 思考折叠 + 🔧 工具折叠卡 +
 *    耗时/缓存命中 + 会话管理 + 历史瀑布流 + 审批弹窗。
 *
 * UI 与 AiScreen 对齐；消息气泡渲染为独立复制版（不复用 AiScreen 私有 MessageBubble，
 * 避免改动 AiScreen/AiViewModel，且本页消息模型与 UiMsg 不同）。
 */
@Composable
fun RemoteControlScreen(viewModel: RemoteControlViewModel = viewModel()) {
    val context = LocalContext.current
    // 顶部临时提示（绑定失败/被顶掉/已断开等）：Toast 展示后清空
    LaunchedEffect(viewModel.notice) {
        viewModel.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissNotice()
        }
    }
    when (viewModel.phase) {
        RemotePhase.LOGIN -> LoginView(viewModel)
        RemotePhase.DEVICE_LIST -> DeviceListView(viewModel)
        RemotePhase.CONSOLE -> ConsoleView(viewModel)
    }
}

// ==================== 登录页 ====================

/** 登录/注册表单：账号 + 密码 + 登录按钮 + 注册按钮（注册=同输入发 register_account） */
@Composable
private fun LoginView(vm: RemoteControlViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🛰 远程控制台",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在家遥控客户现场的 PDA，用它的 AI 排查网络",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("装维账号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), // 账号用英文键盘
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pwd,
            onValueChange = { pwd = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), // 密码用密码键盘
            modifier = Modifier.fillMaxWidth()
        )
        // 登录失败 / 被顶下线 提示
        vm.loginError?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
        // 注册成功提示
        vm.registerSuccess?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (vm.connecting) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("连接中…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.login(name, pwd) },
                enabled = !vm.connecting && name.isNotBlank() && pwd.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("登录")
            }
            OutlinedButton(
                onClick = { vm.register(name, pwd) },
                enabled = !vm.connecting && name.isNotBlank() && pwd.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("注册")
            }
        }
    }
}

// ==================== 设备列表页 ====================

/** 设备列表：账号名下设备（名称/归属/在线/电量/SSID）+ 连接按钮 + 全局授权勾选 */
@Composable
private fun DeviceListView(vm: RemoteControlViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "🛰 远程控制台",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (vm.reconnecting) {
                Text(
                    text = "重连中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (vm.devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无设备（PDA 需在设置页开启「🛰 远程控制」）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.devices, key = { it.deviceId }) { d ->
                    DeviceCard(d, vm)
                }
            }
        }
    }
}

/** 单张设备卡片：在线绿点 + 名称 + 归属/型号/SSID/电量 + 连接按钮 */
@Composable
private fun DeviceCard(d: RemoteDevice, vm: RemoteControlViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (d.online) OnlineGreen else OfflineGray, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = d.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                val sub = buildString {
                    append("归属：").append(d.account.ifBlank { "未绑定" })
                    append(" · ").append(d.deviceId.take(12))
                    if (d.model.isNotBlank()) append(" · ").append(d.model)
                    if (d.ssid.isNotBlank()) append(" · ").append(d.ssid)
                    if (d.battery >= 0) append(" · 电量 ").append(d.battery).append("%")
                    append(" · ").append(if (d.online) "在线" else "离线")
                }
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.bindDevice(d.deviceId) }, enabled = d.online) {
                Text(if (vm.boundDeviceId == d.deviceId) "已绑定" else "连接")
            }
        }
    }
}

// ==================== 控制台页 ====================

/** 控制台：顶部设备状态 + 会话管理 + 授权勾选 + 缓存行 + 消息瀑布流 + 底部输入 + 弹窗 */
@Composable
private fun ConsoleView(vm: RemoteControlViewModel) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingDeleteRound by remember { mutableStateOf<Long?>(null) } // 待确认删除的轮 ts
    val bound = vm.devices.firstOrNull { it.deviceId == vm.boundDeviceId }
    val online = !vm.deviceOffline && bound?.online == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // 键盘弹出：整个列（消息区+输入区）统一上移收缩，微信式
            .imePadding()
    ) {
        // ---- 顶部：设备名 + 在线状态 + 断开 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (online) OnlineGreen else OfflineGray, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = bound?.name ?: (vm.boundDeviceId ?: "设备"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (online) "在线" else if (vm.deviceOffline) "离线" else "连接中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.unbind() }) {
                Text("断开")
            }
        }
        Spacer(Modifier.height(6.dp))

        // ---- 会话管理条：下拉选择 + 新建/重命名/删除/清空 ----
        SessionBar(
            vm = vm,
            onRename = { showRename = true },
            onDelete = { showDeleteConfirm = true },
            onClear = { showClearConfirm = true },
            onMemory = { vm.openMemory() }
        )

        // ---- root 审批档位（点击循环切换：每步审批→会话允许→关闭；与 App 本地 AI 页一致）----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.cycleShellLevel() }
        ) {
            val (label, color) = when (vm.shellLevel) {
                "session" -> "🛡 root 审批：会话允许" to Color(0xFFE67E22)
                "off" -> "🛡 root 审批：已关闭" to MaterialTheme.colorScheme.error
                else -> "🛡 root 审批：每步审批" to MaterialTheme.colorScheme.primary
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "点击切换 · ARP 免审批",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // ---- 余额 + 缓存命中顶部状态行（每轮完成设备端推送 / usage 事件）----
        Row {
            vm.balanceStat?.let {
                Text(
                    text = "💰 余额 $it　",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            vm.cacheStat?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // ---- 消息列表（reverseLayout + 历史瀑布流）----
        MessageList(
            vm = vm,
            onDeleteRound = { ts ->
                // busy 时点删除提示；否则弹确认框
                if (vm.busy) {
                    Toast.makeText(context, "任务执行中", Toast.LENGTH_SHORT).show()
                } else {
                    pendingDeleteRound = ts
                }
            }
        )

        // ---- 离线提示 ----
        if (vm.deviceOffline) {
            Text(
                text = "⚠ 设备已离线（现场断网/断电/App 被关），等待其重连…",
                style = MaterialTheme.typography.bodySmall,
                color = OfflineBannerOrange,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(4.dp))

        // ---- 底部输入区：发送/停止按钮（busy 切换红色停止）----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入指令，例如：帮我检测 PCDN…") },
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            if (vm.busy) {
                Button(
                    onClick = { vm.stopRound() },
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
                        if (input.isNotBlank()) {
                            vm.sendChat(input)
                            input = ""
                        }
                    },
                    enabled = !vm.deviceOffline && vm.boundDeviceId != null,
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("发送")
                }
            }
        }
    }

    // ---- 审批弹窗（root_shell 命令审批 + 120 秒倒计时）----
    vm.pendingApproval?.let { ApprovalDialog(vm, it) }

    // ---- 记忆弹窗（本会话记忆：列表 + 单删 + 清空本会话）----
    if (vm.memoryOpen) {
        MemoryDialog(vm)
    }

    // ---- 单轮删除确认弹窗 ----
    pendingDeleteRound?.let { ts ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRound = null },
            title = { Text("删除这一轮对话") },
            text = { Text("删除这一轮对话？设备端将同步移除，不再进入 AI 上下文。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delRound(ts)
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

    // ---- 重命名弹窗 ----
    if (showRename) {
        RenameDialog(vm, onDismiss = { showRename = false })
    }

    // ---- 删除确认弹窗 ----
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除会话") },
            text = { Text("确认删除当前会话？将连带删除该会话的记忆、档案与凭据。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.currentSessionId?.let { vm.deleteSession(it) }
                    showDeleteConfirm = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // ---- 清空确认弹窗 ----
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空会话消息") },
            text = { Text("确认清空当前会话消息？（记忆与档案保留）") },
            confirmButton = {
                TextButton(onClick = {
                    vm.currentSessionId?.let { vm.clearSession(it) }
                    showClearConfirm = false
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 会话管理条：会话下拉选择 + 新建/重命名/删除/清空图标按钮 */
@Composable
private fun SessionBar(
    vm: RemoteControlViewModel,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onMemory: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentTitle = vm.sessions.firstOrNull { it.id == vm.currentSessionId }?.title ?: "选择会话"
    val idle = !vm.busy && !vm.deviceOffline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 会话下拉（Box 内 DropdownMenu 锚定当前会话标题按钮）
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = currentTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.ExpandMore, contentDescription = "选择会话", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (vm.sessions.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("暂无会话") },
                        onClick = { expanded = false },
                        enabled = false
                    )
                } else {
                    vm.sessions.forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${s.title} · ${formatRemoteTime(s.updatedAt)}" +
                                        (if (s.count > 0) " (${s.count})" else "")
                                )
                            },
                            onClick = {
                                vm.switchSession(s.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
        IconButton(onClick = { vm.newSession() }, enabled = idle) {
            Icon(Icons.Filled.Add, contentDescription = "新建会话")
        }
        IconButton(onClick = onRename, enabled = idle && vm.currentSessionId != null) {
            Icon(Icons.Filled.Edit, contentDescription = "重命名会话")
        }
        IconButton(onClick = onDelete, enabled = idle && vm.currentSessionId != null) {
            Icon(Icons.Filled.Delete, contentDescription = "删除会话", tint = MaterialTheme.colorScheme.error)
        }
        IconButton(onClick = onClear, enabled = idle && vm.currentSessionId != null) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空会话消息")
        }
        // 🧠 记忆：查看/单删/清空当前会话记忆（busy/离线/无当前会话禁用，与 Web 端 btnMem 一致）
        IconButton(onClick = onMemory, enabled = idle && vm.currentSessionId != null) {
            Icon(Icons.Filled.Psychology, contentDescription = "本会话记忆")
        }
    }
}

/** 消息列表（reverseLayout 同 AiScreen）+ 历史瀑布流（上滑到顶自动加载更早 5 条）；ColumnScope 接收者使 weight 可用 */
@Composable
private fun ColumnScope.MessageList(vm: RemoteControlViewModel, onDeleteRound: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val messages = vm.messages

    // 新消息滚底（reverseLayout 下 index 0 = 最新消息贴底）。
    // 用最后一条消息 id 作 key 而非 size：前插更早历史也会使 size 变大，用 size 会误拉回底部，
    // 破坏瀑布流的视口保持；只有「尾部新增」（发消息/系统提示）才会改变 lastId。
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.scrollToItem(0)
    }
    // 流式内容增长（思考/工具日志/正文）自动跟随：仅用户停在最新消息附近且未主动滑动时跟随
    val lastAsst = messages.lastOrNull { it.role == "assistant" }
    LaunchedEffect(lastAsst?.text?.length, lastAsst?.reasoning?.length, lastAsst?.toolCards?.size, lastAsst?.statusText) {
        if (messages.isNotEmpty() && !listState.isScrollInProgress) {
            val first = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            if (first == 0 && offset < 200) listState.scrollToItem(0)
        }
    }
    // 瀑布流：上滑到顶（最旧消息可见 = reverseLayout 最后一个 item 进入视口）自动加载更早历史
    val reachedTop by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastIdx >= info.totalItemsCount - 1
        }
    }
    // 触发加载更早；把 loadingMore 也作为 key，保证内容不足一屏时「自动补满」——
    // 每次加载完成后若仍处于顶部（内容仍不满屏）会再次触发，直到 hasMore=false 或屏幕填满。
    LaunchedEffect(reachedTop, vm.loadingMore) {
        if (reachedTop && !vm.loadingMore) vm.loadMoreHistory()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        reverseLayout = true
    ) {
        if (messages.isEmpty()) {
            item {
                Text(
                    text = "当前会话暂无消息，输入指令开始远程排查",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                )
            }
        }
        // reverseLayout：倒序渲染（最新在前/贴底）
        items(messages.asReversed(), key = { it.id }) { msg ->
            MessageBubbleRemote(msg, onDeleteRound)
        }
        // reverseLayout 下最后 emit 的 item 在视觉最上方：加载更早指示条（瀑布流触发点）
        if (vm.historyHasMore) {
            item { LoadMoreIndicator(vm.loadingMore) }
        }
    }
}

/** 加载更早指示条：加载中转圈 + 文案；未加载时提示上滑 */
@Composable
private fun LoadMoreIndicator(loading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("加载更早…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            Text(
                text = "↑ 上滑加载更早的消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 单条消息气泡（对齐 AiScreen 的 MessageBubble 简化复制版）：
 * user 右对齐主题色容器；assistant 左对齐 surfaceVariant 容器，含状态行/思考折叠/工具折叠卡/耗时；
 * sys 居中系统提示小字。 */
@Composable
private fun MessageBubbleRemote(msg: RemoteMsg, onDeleteRound: (Long) -> Unit) {
    if (msg.role == "sys") {
        Text(
            text = msg.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        return
    }
    val isUser = msg.role == "user"
    // 已完成轮次（user 或已完成的 assistant，且已拿到轮 ts）才显示删除入口；未完成轮不显示
    val showDelete = msg.roundTs > 0 && (isUser || msg.done)
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
            if (isUser) {
                SelectionContainer {
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // 状态行（流式中：⚙ 正在调用工具… 已用 N 秒）
                    if (msg.statusText.isNotBlank() && !msg.done) {
                        StatusLine(msg)
                    }
                    // 思考过程折叠（历史默认折叠 / 实时展开）
                    if (msg.reasoning.isNotBlank()) {
                        ReasoningBlock(msg)
                    }
                    // 工具调用记录折叠卡（历史默认折叠 / 正在执行的最后一张默认展开）
                    if (msg.toolCards.isNotEmpty()) {
                        msg.toolCards.forEachIndexed { i, tc ->
                            ToolCardBlock(msg, i, tc)
                        }
                    }
                    // 正文（可选择复制；与上方思考/工具块隔开一点；空正文兜底「…」与 AiScreen 一致）
                    val body = when {
                        !msg.done && msg.text.isBlank() -> "⏳ 生成中…"
                        else -> msg.text.ifBlank { "…" }
                    }
                    SelectionContainer {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (msg.error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
        // 气泡下方时间戳（assistant 带本轮耗时）+ 已完成轮的 ✕ 删除入口
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = formatRemoteTime(msg.ts) +
                    if (!isUser && msg.roundSec > 0) "　·　本轮 ${msg.roundSec} 秒" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (showDelete) {
                Spacer(Modifier.width(8.dp))
                // 小垃圾桶：点击进入删除确认（busy 时由上层提示「任务执行中」；与 PDA 屏 AI 页一致）
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除这一轮对话",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onDeleteRound(msg.roundTs) }
                )
            }
        }
    }
}

/** 流式状态行：⚙ 正在调用工具…（已用 N 秒）每秒刷新 */
@Composable
private fun StatusLine(msg: RemoteMsg) {
    var elapsed by remember(msg.id) { mutableStateOf(0) }
    LaunchedEffect(msg.id) {
        elapsed = 0
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    Text(
        text = "⚙ ${msg.statusText}（已用 $elapsed 秒）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/** 思考过程折叠块：历史默认折叠 / 流式中实时展开；完成后（done 变化）自动折叠 */
@Composable
private fun ReasoningBlock(msg: RemoteMsg) {
    // key 带 msg.done：轮次完成时 remember 重置，思考自动折叠（否则展开状态残留不折）
    var expanded by remember(msg.id, msg.done) { mutableStateOf(!msg.done) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Text(
            text = "💭 思考过程（${msg.reasoning.length} 字）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp)
        )
    }
    AnimatedVisibility(visible = expanded) {
        SelectionContainer {
            Text(
                text = msg.reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 工具折叠卡：🔧 工具 i/n：名称 + 入参/输出（历史折叠，正在执行的最后一张展开） */
@Composable
private fun ToolCardBlock(msg: RemoteMsg, index: Int, tc: RemoteToolCard) {
    val running = !msg.done && index == msg.toolCards.size - 1
    var expanded by remember(tc, running) { mutableStateOf(running) }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            Text(
                text = "🔧 工具 ${index + 1}/${msg.toolCards.size}：${tc.name}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起工具详情" else "展开工具详情",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
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

/** 审批弹窗：完整命令 + 允许/拒绝 + 120 秒倒计时，超时静默拒绝 */
@Composable
private fun ApprovalDialog(vm: RemoteControlViewModel, p: RemoteApproval) {
    var remain by remember(p.reqId) { mutableStateOf(120) }
    LaunchedEffect(p.reqId) {
        while (true) {
            val r = ((p.deadline - System.currentTimeMillis()) / 1000).toInt()
            if (r <= 0) {
                vm.timeoutReject()
                break
            }
            remain = r
            delay(500)
        }
    }
    AlertDialog(
        onDismissRequest = { vm.respondApproval(false) },
        title = { Text("🛡 远程审批请求") },
        text = {
            Column {
                Text(
                    text = if (p.toolName.isNotBlank()) "工具：${p.toolName} 请求以 root 权限执行以下命令：" else "AI 请求以 root 权限执行以下命令：",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    SelectionContainer {
                        Text(
                            text = p.command,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "$remain 秒后自动拒绝",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.respondApproval(true) }) {
                Text("允许执行", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.respondApproval(false) }) { Text("拒绝") }
        }
    )
}

/** 记忆弹窗（对齐 App AI 页 🧠 弹窗样式）：列表每条记忆文本+时间+✕单删，底部清空本会话记忆+关闭 */
@Composable
private fun MemoryDialog(vm: RemoteControlViewModel) {
    AlertDialog(
        onDismissRequest = { vm.closeMemory() },
        title = { Text("本会话记忆（${vm.memoryItems.size} 条）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (vm.memoryItems.isEmpty()) {
                    Text(
                        text = "本会话暂无记忆。AI 在当前会话中发现重要信息（网络拓扑/排查结论等）会自动记忆；每个会话对应一个客户，互不串用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    vm.memoryItems.forEachIndexed { i, item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${i + 1}. ${item.text}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (item.ts > 0) {
                                    Text(
                                        text = formatRemoteTime(item.ts),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            // ✕ 单删（按文本精确匹配，设备删完自动回新列表）
                            IconButton(
                                onClick = { vm.deleteMemory(item.text) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "删除该条记忆",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.clearMemory() },
                enabled = vm.memoryItems.isNotEmpty() // 空列表禁用清空
            ) {
                Text("清空本会话记忆", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.closeMemory() }) { Text("关闭") }
        }
    )
}

/** 会话重命名弹窗：预填当前标题并全选，直接打字替换，32 字上限，键盘完成键确认 */
@Composable
private fun RenameDialog(vm: RemoteControlViewModel, onDismiss: () -> Unit) {
    val id = vm.currentSessionId
    val currentTitle = vm.sessions.firstOrNull { it.id == id }?.title ?: ""
    var titleState by remember(id) {
        mutableStateOf(TextFieldValue(currentTitle, TextRange(0, currentTitle.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit = {
        if (id != null && titleState.text.isNotBlank()) {
            vm.renameSession(id, titleState.text)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
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
        },
        confirmButton = {
            TextButton(enabled = titleState.text.isNotBlank(), onClick = { submit() }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 消息/会话时间格式化：今天 HH:mm；昨天 HH:mm；今年 M月d日；更早 yyyy/M/d */
private fun formatRemoteTime(ts: Long): String {
    if (ts <= 0) return ""
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
