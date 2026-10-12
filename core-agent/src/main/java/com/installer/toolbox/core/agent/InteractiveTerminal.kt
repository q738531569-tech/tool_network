package com.installer.toolbox.core.agent

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session as JSchSession
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 交互式终端会话（SSH / Telnet 统一，用户自用"终端式"体验）。
 *
 * 与 [RemoteExecutor]（单次执行）不同：本类**保持连接**，支持连续收发
 * （输入命令 → 读输出 → 再输入），像 putty/xshell 一样。
 *
 * - SSH：JSCH ChannelShell 交互式 shell，PipedStream 读写；
 * - Telnet：裸 Socket 保持，IAC 协商过滤（复用 RemoteExecutor 的过滤逻辑）。
 * 账密仅本次会话内存使用，不保存。
 */
object InteractiveTerminal {

    /** 会话协议 */
    enum class Protocol { SSH, TELNET }

    /** 交互式会话句柄 */
    interface Session {
        /** 发送一行命令（自动补换行） */
        fun send(command: String)

        /** 读取自上次调用以来新到的输出（增量），无新内容返回空串 */
        fun drainOutput(): String

        /** 会话是否仍连接 */
        fun isConnected(): Boolean

        /** 关闭会话（断开连接） */
        fun disconnect()
    }

    /**
     * 建立交互式会话。
     * @return 失败时返回错误文本；成功返回 null
     */
    fun connect(
        protocol: Protocol,
        host: String,
        port: Int,
        username: String,
        password: String
    ): Pair<Session?, String?> {
        return when (protocol) {
            Protocol.SSH -> connectSsh(host, port, username, password)
            Protocol.TELNET -> connectTelnet(host, port, username, password)
        }
    }

    // ===================== SSH（JSCH ChannelShell） =====================

    private fun connectSsh(
        host: String,
        port: Int,
        username: String,
        password: String
    ): Pair<Session?, String?> {
        var term: Session? = null
        var jschSession: JSchSession? = null
        return try {
            val jsch = JSch()
            jschSession = jsch.getSession(username, host, port)
            jschSession.setPassword(password)
            jschSession.setConfig("StrictHostKeyChecking", "no")
            jschSession.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            jschSession.connect(8_000)
            val channel = jschSession.openChannel("shell") as ChannelShell
            // 交互式 shell 必须开 pty（无 pty 时无提示符、命令输出易缓冲不刷新）
            channel.setPty(true)
            channel.setPtyType("vt100")
            // 输入管道：我们写 inWrite，channel 从 inRead 读（远程 stdin）
            val inWrite = PipedOutputStream()
            val inRead = PipedInputStream(inWrite)
            // 输出管道：channel 写 outWrite，我们从 outRead 读（远程 stdout/stderr）
            val outWrite = PipedOutputStream()
            val outRead = PipedInputStream(outWrite, 64 * 1024)
            channel.inputStream = inRead
            channel.setOutputStream(outWrite)
            channel.connect(8_000)
            val s = SshShellSession(jschSession, channel, inWrite, outRead)
            term = s
            // 等待欢迎 banner（1 秒后首次 drain 可拿到登录提示）
            Thread.sleep(800)
            s to null
        } catch (e: Exception) {
            try {
                jschSession?.disconnect()
            } catch (e2: Exception) {
            }
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    private class SshShellSession(
        private val session: JSchSession,
        private val channel: ChannelShell,
        private val inWrite: PipedOutputStream,
        private val outRead: PipedInputStream
    ) : Session {
        private val buffer = StringBuilder()

        override fun send(command: String) {
            try {
                inWrite.write("$command\n".toByteArray(Charsets.UTF_8))
                inWrite.flush()
            } catch (e: Exception) {
                // 会话已断开
            }
        }

        override fun drainOutput(): String {
            return try {
                val available = outRead.available()
                if (available > 0) {
                    val buf = ByteArray(available)
                    val read = outRead.read(buf)
                    if (read > 0) buffer.append(String(buf, 0, read, Charsets.UTF_8))
                }
                val text = buffer.toString()
                buffer.setLength(0)
                // 保留 ANSI 颜色码，UI 层负责渲染成彩色（剥离会丢失终端颜色信息）
                text
            } catch (e: Exception) {
                ""
            }
        }

        override fun isConnected(): Boolean = channel.isConnected

        override fun disconnect() {
            try {
                inWrite.close()
            } catch (e: Exception) {
            }
            try {
                channel.disconnect()
            } catch (e: Exception) {
            }
            try {
                session.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    // ===================== Telnet（裸 Socket 保持） =====================

    private fun connectTelnet(
        host: String,
        port: Int,
        username: String,
        password: String
    ): Pair<Session?, String?> {
        var session: TelnetSession? = null
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 300
            val s = TelnetSession(socket, socket.getInputStream(), socket.getOutputStream())
            session = s
            // Telnet 登录：发用户名/密码（部分设备需逐行等待提示，这里直接连续发送并稍等）
            if (username.isNotBlank()) s.send(username)
            if (password.isNotEmpty()) s.send(password)
            Thread.sleep(800)
            s to null
        } catch (e: Exception) {
            try {
                session?.disconnect()
            } catch (e2: Exception) {
            }
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    private class TelnetSession(
        private val socket: Socket,
        private val input: InputStream,
        private val output: OutputStream
    ) : Session {

        override fun send(command: String) {
            try {
                output.write("$command\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
            } catch (e: Exception) {
            }
        }

        override fun drainOutput(): String {
            // 短超时读 + IAC 过滤；保留 ANSI 颜色码（UI 渲染彩色）
            return try {
                readTelnetText(input, 4096)
            } catch (e: Exception) {
                ""
            }
        }

        override fun isConnected(): Boolean = socket.isConnected && !socket.isClosed

        override fun disconnect() {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    /** Telnet 流读取：过滤 IAC 协商字节（与 RemoteExecutor 同逻辑） */
    private fun readTelnetText(input: InputStream, maxBytes: Int): String {
        val sb = StringBuilder()
        var read = 0
        while (read < maxBytes) {
            val b = input.read()
            if (b == -1) break
            read++
            if (b == 0xFF) {
                val cmd = input.read()
                if (cmd == -1) break
                read++
                when (cmd) {
                    0xFF -> sb.append('\u00FF')
                    0xFA, 0xFB -> {
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
                        if (input.read() != -1) read++
                    }
                    else -> {
                    }
                }
            } else {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }
}
