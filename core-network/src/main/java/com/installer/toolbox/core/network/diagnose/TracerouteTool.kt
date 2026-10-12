package com.installer.toolbox.core.network.diagnose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

/**
 * TCP 变通 Traceroute 工具。
 *
 * 限制说明：无 root 的 Android 无法发送带 TTL 的原始 TCP 包，
 * 因此采用"逐跳 TCP connect"变通——每一跳对目标端口做一次 connect，
 * 成功即判定到达目标；中间路由 IP 无法获取，中间跳 IP 显示为空（UI 显示"未知"）。
 * 这是 PingTools 等应用在 Android 上的标准做法。
 */
object TracerouteTool {

    /** 一跳的结果 */
    data class TraceHop(
        /** 跳数（从 1 开始） */
        val hop: Int,
        /** 该跳 IP；中间跳无法获取时为空字符串 */
        val ip: String,
        /** 该跳耗时（毫秒） */
        val elapsedMs: Long,
        /** 是否已到达目标 */
        val reached: Boolean
    )

    /**
     * 执行变通 Traceroute。
     * @param target 目标 IP 或域名
     * @param port 探测端口（默认 80）
     * @param maxHops 最大跳数
     * @param timeoutMs 每跳连接超时（毫秒）
     * @param bindIp 本机源地址（如局域网接口 IP，非空时绑定该地址），
     *   让探测流量走指定网卡而非系统默认路由；null 保持默认行为
     */
    suspend fun trace(
        target: String,
        port: Int = 80,
        maxHops: Int = 15,
        timeoutMs: Long = 3000,
        bindIp: String? = null
    ): List<TraceHop> = withContext(Dispatchers.IO) {
        val hops = mutableListOf<TraceHop>()
        var reached = false
        for (hop in 1..maxHops) {
            val elapsed = measureTimeMillis {
                try {
                    Socket().use { socket ->
                        // 绑定本机源地址：让探测流量从指定接口发出（局域网无外网时不误走移动数据）
                        if (bindIp != null) {
                            socket.bind(InetSocketAddress(bindIp, 0))
                        }
                        socket.connect(InetSocketAddress(target, port), timeoutMs.toInt())
                    }
                    reached = true
                } catch (e: Exception) {
                    // 该跳未达，继续下一跳
                }
            }
            hops.add(
                TraceHop(
                    hop = hop,
                    ip = if (reached) target else "",
                    elapsedMs = elapsed,
                    reached = reached
                )
            )
            if (reached) break
            delay(100) // 每跳间隔，避免探测风暴
        }
        hops
    }
}
