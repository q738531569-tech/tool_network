package com.installer.toolbox.core.agent

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 远程命令执行器（SSH + Telnet 统一封装，AI 助手 remote 工具用）。
 *
 * - SSH：JSCH 密码认证，返回退出码（局域网设备常用）；
 * - Telnet：裸 Socket + IAC 协商过滤状态机（登录 → 发命令 → 收集输出），
 *   覆盖路由器/光猫/机顶盒等老设备。
 *
 * 连接参数**不落盘**：仅在本次调用内存中使用，调用结束即释放。
 * 注意：阻塞执行，调用方须运行在 IO 线程并控制总超时。
 */
object RemoteExecutor {

    /** 协议类型 */
    enum class Protocol { SSH, TELNET }

    /** 执行结果 */
    data class RemoteResult(
        val ok: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String?
    )

    /**
     * 执行远程命令（按协议分发）。
     *
     * @param protocol SSH 或 TELNET
     * @param host 目标主机 IP
     * @param port 端口（SSH 默认 22，Telnet 默认 23）
     * @param username 用户名
     * @param password 密码（仅本次调用使用；Telnet 部分设备可为空）
     * @param command 要执行的命令
     * @param timeoutMs 连接 + 执行总超时（毫秒）
     */
    fun execute(
        protocol: Protocol,
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        timeoutMs: Long = 15_000
    ): RemoteResult = when (protocol) {
        Protocol.SSH -> ssh(host, port, username, password, command, timeoutMs)
        Protocol.TELNET -> telnet(host, port, username, password, command, timeoutMs)
    }

    /** 按字符串解析协议（"ssh"/"telnet"，默认 SSH）；未知返回 null */
    fun protocolOf(name: String?): Protocol? = when (name?.lowercase()) {
        "ssh" -> Protocol.SSH
        "telnet" -> Protocol.TELNET
        else -> null
    }

    /**
     * 剥离 ANSI 转义序列（终端颜色/光标控制码，如 `\u001B[1;34m`）。
     * 交互式 shell / pty 输出常带颜色码，UI 与 AI 工具结果都需过滤后展示。
     * 覆盖：CSI（ESC[ 参数/中间/终结字节）、OSC（ESC] ... BEL/ST）、单字节转义。
     */
    internal fun stripAnsi(text: String): String {
        if (text.isEmpty()) return text
        return text.replace(
            Regex(
                "\u001B\\[[0-9;?]*[ -/]*[@-~]" +      // CSI 序列
                    "|\u001B\\][^\u0007]*?(?:\u0007|\u001B\\\\)" + // OSC 序列
                    "|\u001B[@-Z\\\\-_]"               // 单字节转义
            ),
            ""
        )
    }

    // ===================== SSH（JSCH） =====================

    private fun ssh(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        timeoutMs: Long
    ): RemoteResult {
        var session: Session? = null
        return try {
            val jsch = JSch()
            session = jsch.getSession(username, host, port)
            session.setPassword(password)
            // 局域网设备常为自签/动态 host key，跳过交互式确认（现场工具场景可接受）
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            session.connect(timeoutMs.toInt())

            val channel = session.openChannel("exec") as ChannelExec
            try {
                channel.setCommand(command)
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                // 注意：JSCH 的 Channel 属性（getOutputStream 等）是只读的，
                // 必须用显式 setter 重定向远程 stdout/stderr
                channel.setOutputStream(out)
                channel.setErrStream(err)
                channel.connect(timeoutMs.toInt())

                // 等待命令结束（最多到总超时，避免长命令挂死）
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                // ByteArrayOutputStream.toString(Charset) 需 API 33，用全版本兼容的 String(bytes, charset)
                val output = stripAnsi(String(out.toByteArray(), Charsets.UTF_8).trim())
                val errText = stripAnsi(String(err.toByteArray(), Charsets.UTF_8).trim())
                RemoteResult(
                    ok = channel.exitStatus == 0,
                    exitCode = channel.exitStatus,
                    // stdout 为空时用 stderr 兜底展示（很多设备把信息打到 stderr）
                    output = if (output.isNotEmpty()) output else errText,
                    error = if (output.isNotEmpty() && errText.isNotEmpty()) errText else null
                )
            } finally {
                try {
                    channel.disconnect()
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            RemoteResult(false, -1, "", e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                session?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    // ===================== Telnet（裸 Socket 状态机） =====================

    /** 登录/命令阶段 */
    private const val STAGE_LOGIN = 0
    private const val STAGE_PASSWORD = 1
    private const val STAGE_PROMPT = 2
    private const val STAGE_COMMAND = 3

    private fun telnet(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        timeoutMs: Long
    ): RemoteResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val connectTimeout = minOf(timeoutMs, 5_000L).toInt()
        return try {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeout)
                // 读超时短一些：状态机靠轮询推进
                socket.soTimeout = 2_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val buffer = StringBuilder()   // 当前阶段累积文本
                val outputSb = StringBuilder() // 命令输出（STAGE_COMMAND 阶段收集）
                var stage = STAGE_LOGIN
                var loginError: String? = null

                while (System.currentTimeMillis() < deadline) {
                    // 读一段干净文本（过滤 IAC 协商）
                    val text = readTelnetText(input, 4096)
                    if (text.isNotEmpty()) buffer.append(text)
                    val all = buffer.toString()

                    when (stage) {
                        STAGE_LOGIN -> {
                            // 等 "login:" 提示；出现 "incorrect" 视为登录失败
                            if (all.contains("incorrect", ignoreCase = true) ||
                                (all.contains("ogin:", ignoreCase = true) && all.contains("again", ignoreCase = true))
                            ) {
                                loginError = "登录失败：用户名或密码错误"
                                break
                            }
                            if (all.contains("ogin:", ignoreCase = true)) {
                                sendLine(output, username)
                                buffer.clear()
                                stage = STAGE_PASSWORD
                            }
                        }
                        STAGE_PASSWORD -> {
                            if (all.contains("assword:", ignoreCase = true)) {
                                sendLine(output, password)
                                buffer.clear()
                                stage = STAGE_PROMPT
                            }
                        }
                        STAGE_PROMPT -> {
                            // 等提示符（$ # > 常见）；登录失败会重新出现 login:
                            if (all.contains("ogin:", ignoreCase = true)) {
                                loginError = "登录失败：用户名或密码错误"
                                break
                            }
                            if (hasPrompt(all)) {
                                sendLine(output, command)
                                buffer.clear()
                                stage = STAGE_COMMAND
                            }
                        }
                        STAGE_COMMAND -> {
                            // 收集输出直到再次出现提示符（取最后一次提示符前的文本为输出）
                            if (hasPrompt(all)) {
                                val idx = all.lastIndexOfPrompt()
                                if (idx > 0) outputSb.append(all, 0, idx)
                                break
                            }
                            // 未到提示符：把已读文本暂存（去重避免重复累加）
                            outputSb.append(all)
                            buffer.clear()
                        }
                    }
                }

                // 收尾：发 exit 关闭会话（尽力而为）
                try {
                    sendLine(output, "exit")
                    socket.close()
                } catch (e: Exception) {
                }

                if (loginError != null) {
                    RemoteResult(false, -1, "", loginError)
                } else {
                    val out = stripAnsi(outputSb.toString().trim())
                    RemoteResult(
                        ok = out.isNotEmpty(),
                        exitCode = if (out.isNotEmpty()) 0 else -1,
                        output = out,
                        error = if (out.isEmpty()) "Telnet 执行未返回输出（可能登录失败或设备无响应）" else null
                    )
                }
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            RemoteResult(false, -1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /** 发送一行并刷新 */
    private fun sendLine(output: OutputStream, line: String) {
        output.write("$line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /**
     * 从 Telnet 流读取文本，**过滤 IAC 协商字节**（0xFF WILL/WONT/DO/DONT/SB...SE）。
     * 返回过滤后的可见文本；流关闭返回空串。
     */
    private fun readTelnetText(input: InputStream, maxBytes: Int): String {
        val sb = StringBuilder()
        var read = 0
        while (read < maxBytes) {
            val b = input.read()
            if (b == -1) break
            read++
            if (b == 0xFF) {
                // IAC 序列
                val cmd = input.read()
                if (cmd == -1) break
                read++
                when (cmd) {
                    0xFF -> sb.append('\u00FF')                 // IAC IAC：字面 0xFF
                    0xFA, 0xFB -> {                              // SB 子协商：读到 IAC SE
                        while (true) {
                            val c = input.read()
                            if (c == -1) break
                            read++
                            if (c == 0xFF) {
                                val c2 = input.read()
                                if (c2 == -1) break
                                read++
                                if (c2 == 0xF0) break
                            }
                        }
                    }
                    0xFB, 0xFC, 0xFD, 0xFE -> {
                        // WILL/WONT/DO/DONT + option 字节，直接丢弃
                        if (input.read() != -1) read++
                    }
                    else -> {
                        // NOP/DM/BRK 等单字节命令，丢弃
                    }
                }
            } else {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }

    /** 是否出现常见提示符（$ # > 结尾；排除路径中含 # 的情况，用行尾判断） */
    private fun hasPrompt(text: String): Boolean {
        val lines = text.lines()
        if (lines.isEmpty()) return false
        val last = lines.last().trim()
        return last.endsWith("$") || last.endsWith("#") || last.endsWith(">")
            || last.matches(Regex(".*[\\w\\]]+[#$>]"))
    }

    /** 最后一个提示符的位置（用于截断输出） */
    private fun String.lastIndexOfPrompt(): Int {
        val lines = lines()
        var last = -1
        var offset = 0
        for (line in lines) {
            if (line.trim().endsWith("$") || line.trim().endsWith("#") || line.trim().endsWith(">")) {
                last = offset + line.length
            }
            offset += line.length + 1
        }
        return last
    }
}
