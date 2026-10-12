package com.installer.toolbox.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.installer.toolbox.core.network.util.IpUtil
import com.installer.toolbox.core.agent.InteractiveTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程终端页（SSH/Telnet 统一）——**交互式终端**：
 * 连接后保持会话，底部输入命令、上方连续回显输出（类似 putty/xshell），
 * 而非"表单单次执行"。账密仅本次会话内存使用，不保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteShellScreen() {
    // 连接表单状态
    var protocol by remember { mutableStateOf(InteractiveTerminal.Protocol.SSH) }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }

    // 终端会话状态
    var session by remember { mutableStateOf<InteractiveTerminal.Session?>(null) }
    var terminalMode by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun connect() {
        val h = host.trim()
        if (h.isBlank()) {
            connectError = "请填写主机 IP"
            return
        }
        // 若看起来像 IP（含数字+点）则必须为合法 IPv4；域名允许
        if (h.matches(Regex("""[\d.]+""")) && !IpUtil.isValidIp(h)) {
            connectError = "主机 IP 格式不正确（需为点分十进制，如 192.168.1.1）"
            return
        }
        if (protocol == InteractiveTerminal.Protocol.SSH && username.isBlank()) {
            connectError = "SSH 需要填写用户名（如 root）"
            return
        }
        connecting = true
        connectError = null
        scope.launch {
            val (s, err) = withContext(Dispatchers.IO) {
                InteractiveTerminal.connect(
                    protocol = protocol,
                    host = host.trim(),
                    port = portText.toIntOrNull() ?: if (protocol == InteractiveTerminal.Protocol.SSH) 22 else 23,
                    username = username.trim().ifEmpty { "root" },
                    password = password
                )
            }
            connecting = false
            if (err != null || s == null) {
                connectError = err ?: "连接失败"
                return@launch
            }
            session = s
            lines = emptyList()
            terminalMode = true
            // 首次读取欢迎 banner / 登录提示
            val banner = withContext(Dispatchers.IO) { s.drainOutput().trim() }
            if (banner.isNotBlank()) lines = listOf(banner)
        }
    }

    fun send() {
        val cmd = input.trim()
        if (cmd.isEmpty() || session == null) return
        input = ""
        lines = lines + "> $cmd"
        scope.launch {
            withContext(Dispatchers.IO) { session?.send(cmd) }
        }
    }

    fun disconnect() {
        scope.launch {
            withContext(Dispatchers.IO) { session?.disconnect() }
        }
        session = null
        terminalMode = false
        lines = emptyList()
    }

    // 终端模式：轮询输出并追加
    if (terminalMode) {
        LaunchedEffect(Unit) {
            while (session != null) {
                delay(200)
                val out = withContext(Dispatchers.IO) { session?.drainOutput().orEmpty() }
                if (out.isNotBlank()) {
                    lines = lines + out
                }
            }
        }
        // 自动滚动到底部
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (terminalMode) "远程终端（已连接）" else "远程终端") },
                actions = {
                    if (terminalMode) {
                        TextButton(onClick = ::disconnect) { Text("断开") }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (!terminalMode) {
            // ==================== 连接表单 ====================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .padding(16.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = protocol == InteractiveTerminal.Protocol.SSH,
                        onClick = {
                            protocol = InteractiveTerminal.Protocol.SSH
                            portText = ""
                        },
                        label = { Text("SSH（22）") }
                    )
                    FilterChip(
                        selected = protocol == InteractiveTerminal.Protocol.TELNET,
                        onClick = {
                            protocol = InteractiveTerminal.Protocol.TELNET
                            portText = ""
                        },
                        label = { Text("Telnet（23）") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("主机 IP") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = ::connect,
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (connecting) "连接中…" else "连接")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "连接后进入交互式终端：底部输入命令、回车执行、上方连续回显。账密仅本次会话使用，不保存。Telnet 部分老设备密码可为空。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                connectError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            // ==================== 终端视图 ====================
            // 输出区 weight(1f) 必须是 Column 的**直接子级**且中间不能有包装层
            // （否则 weight 不生效、输出区把输入行挤出屏幕）。imePadding 只作用于
            // 底部输入行：键盘弹出时输入行上移，输出区正常收缩（参照 AiScreen 先例）。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 输出区（终端风格：深色背景 + 亮色文字，ANSI 颜色码渲染为彩色；每行可长按复制）
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF1E1E1E))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    itemsIndexed(lines) { index, text ->
                        SelectionContainer {
                            Text(
                                text = ansiToAnnotated(text),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE0E0E0),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                // 底部输入行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("输入命令，回车执行") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = ::send) { Text("发送") }
                }
            }
        }
    }
}

/**
 * ANSI 16 色表（索引 0-7 普通，粗体 1 时用 8-15 亮色）。
 * 与终端标准配色一致：黑红绿黄蓝紫青白。
 */
private val ANSI_COLORS = listOf(
    Color(0xFF000000), Color(0xFFCD0000), Color(0xFF00CD00), Color(0xFFCDCD00),
    Color(0xFF0000EE), Color(0xFFCD00CD), Color(0xFF00CDCD), Color(0xFFE5E5E5)
)
private val ANSI_BRIGHT = listOf(
    Color(0xFF7F7F7F), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFFFFFF00),
    Color(0xFF5C5CFF), Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFFFFFFFF)
)

/** ANSI 256 色：0-15 标准色，16-231 6×6×6 立方体，232-255 灰度 */
private fun ansi256Color(n: Int): Color {
    return when {
        n < 8 -> ANSI_COLORS[n]
        n < 16 -> ANSI_BRIGHT[n - 8]
        n < 232 -> {
            val v = n - 16
            val table = listOf(0, 95, 135, 175, 215, 255)
            val r = table[v / 36]
            val g = table[(v / 6) % 6]
            val b = table[v % 6]
            Color(0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
        }
        else -> {
            val g = 8 + (n - 232) * 10
            Color(0xFF000000L or (g.toLong() shl 16) or (g.toLong() shl 8) or g.toLong())
        }
    }
}

/**
 * 把带 ANSI SGR 颜色码的终端输出渲染成彩色 [AnnotatedString]。
 * 支持：0 重置 / 1 粗体 / 30-37、40-47（16 色）/ 38;5;N、48;5;N（256 色）/
 * 38;2;R;G;B、48;2;R;G;B（真彩色）。
 */
private fun ansiToAnnotated(text: String): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)
    val builder = AnnotatedString.Builder()
    val sgr = Regex("\u001B\\[([0-9;]*)m")
    var fgColor: Color? = null // null = 默认前景（白）
    var bgColor: Color? = null // null = 无背景
    var bold = false
    var pos = 0

    fun style(): SpanStyle {
        return SpanStyle(
            color = fgColor ?: Color(0xFFE0E0E0),
            background = bgColor ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null
        )
    }

    for (m in sgr.findAll(text)) {
        if (m.range.first > pos) {
            builder.withStyle(style()) { append(text, pos, m.range.first) }
        }
        val params = m.groupValues[1].split(";").mapNotNull { it.toIntOrNull() }
        var i = 0
        while (i < params.size) {
            when (params[i]) {
                0 -> {
                    fgColor = null
                    bgColor = null
                    bold = false
                }
                1 -> bold = true
                in 30..37 -> fgColor = (if (bold) ANSI_BRIGHT else ANSI_COLORS)[params[i] - 30]
                in 40..47 -> bgColor = (if (bold) ANSI_BRIGHT else ANSI_COLORS)[params[i] - 40]
                38, 48 -> {
                    // 扩展色：38;5;N（256 前景）/ 38;2;R;G;B（真彩前景），48 同理背景
                    val isFg = params[i] == 38
                    val mode = params.getOrNull(i + 1)
                    val apply: (Color) -> Unit = { if (isFg) fgColor = it else bgColor = it }
                    when (mode) {
                        5 -> {
                            params.getOrNull(i + 2)?.let { apply(ansi256Color(it)) }
                            i += 2
                        }
                        2 -> {
                            val r = params.getOrNull(i + 2)
                            val g = params.getOrNull(i + 3)
                            val b = params.getOrNull(i + 4)
                            if (r != null && g != null && b != null) {
                                apply(Color(0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()))
                            }
                            i += 4
                        }
                        else -> {
                            // 模式未知，跳过该参数
                        }
                    }
                }
                else -> {
                    // 忽略 90-97 亮色前景 / 100-107 亮色背景等（可后续补）
                }
            }
            i++
        }
        pos = m.range.last + 1
    }
    if (pos < text.length) builder.withStyle(style()) { append(text, pos, text.length) }
    return builder.toAnnotatedString()
}
