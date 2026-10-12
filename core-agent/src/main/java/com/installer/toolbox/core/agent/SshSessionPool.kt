package com.installer.toolbox.core.agent

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 交互式远程会话池（SSH / Telnet，供 ssh_open / ssh_send / ssh_close 工具使用）。
 *
 * 背景：部分设备（光猫/老交换机/设备 CLI）登录后需要**多轮交互**才能完成操作
 * （登录后二次确认、菜单选择、逐条命令问答）——单次 ssh_exec 做不到。
 * AI 通过 ssh_open 建会话 → 多轮 ssh_send 交互 → ssh_close 关闭。
 *
 * - 复用 [InteractiveTerminal] 的 ChannelShell/裸 Socket 能力（pty + PipedStream）；
 * - 会话上限 8 个、5 分钟不活跃自动清理（惰性回收，不额外起线程）；
 * - 账密仅内存使用不落盘（与 ssh_exec 一致）。
 */
object SshSessionPool {

    /** 会话句柄：交互终端 + 最后活跃时间戳 */
    private data class Entry(val session: InteractiveTerminal.Session, val lastActive: Long)

    /** 会话上限（超出时清最旧） */
    private const val MAX_SESSIONS = 8

    /** 不活跃超时（毫秒，惰性清理） */
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

    private val sessions = ConcurrentHashMap<String, Entry>()

    /** 建立交互式会话：返回 sessionId；失败抛异常（消息为中文原因） */
    @Synchronized
    fun open(
        protocol: InteractiveTerminal.Protocol,
        host: String,
        port: Int,
        username: String,
        password: String
    ): Pair<String, String> {
        cleanupIdle()
        val (session, err) = InteractiveTerminal.connect(protocol, host, port, username, password)
        if (session == null) throw IllegalStateException(err ?: "连接失败")
        // 超上限：清掉最旧会话
        if (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.lastActive }
            oldest?.let {
                runCatching { it.value.session.disconnect() }
                sessions.remove(it.key)
            }
        }
        val id = UUID.randomUUID().toString().take(8)
        sessions[id] = Entry(session, System.currentTimeMillis())
        val banner = runCatching { session.drainOutput() }.getOrDefault("")
        return id to banner
    }

    /** 发送输入并返回新到输出（等 1.2 秒收集响应，交互式设备响应较慢） */
    fun send(sessionId: String, input: String): String {
        val entry = sessions[sessionId] ?: return "会话不存在或已过期（超过 5 分钟未使用），请重新 ssh_open"
        val session = entry.session
        if (!session.isConnected()) {
            runCatching { session.disconnect() }
            sessions.remove(sessionId)
            return "会话已断开，请重新 ssh_open"
        }
        session.send(input)
        // 等响应：交互式设备（光猫菜单等）响应通常 0.5-1.5 秒
        Thread.sleep(1200)
        val output = runCatching { session.drainOutput() }.getOrDefault("")
        sessions[sessionId] = Entry(session, System.currentTimeMillis())
        return output.ifBlank { "（无新输出，设备可能仍在处理或未响应）" }
    }

    /** 关闭会话 */
    fun close(sessionId: String) {
        sessions.remove(sessionId)?.let {
            runCatching { it.session.disconnect() }
        }
    }

    /** 惰性清理不活跃会话 */
    private fun cleanupIdle() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, e) ->
            if (now - e.lastActive > IDLE_TIMEOUT_MS) {
                runCatching { e.session.disconnect() }
                true
            } else false
        }
    }
}
