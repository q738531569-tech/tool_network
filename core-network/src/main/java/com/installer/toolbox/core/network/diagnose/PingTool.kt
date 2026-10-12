package com.installer.toolbox.core.network.diagnose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

/**
 * TCP Ping 工具：对目标 443 端口做 TCP connect 计时。
 * 无 root 无法发原生 ICMP，这是 Android 标准替代方案。
 */
object PingTool {

    /** Ping 结果统计 */
    data class PingResult(
        val target: String,
        /** 发送次数 */
        val sent: Int,
        /** 成功次数 */
        val received: Int,
        /** 丢包率（0-100，百分比） */
        val lossPercent: Int,
        /** 平均耗时（毫秒）；无成功样本为 null */
        val avgMs: Long?,
        /** 最小耗时（毫秒） */
        val minMs: Long?,
        /** 最大耗时（毫秒） */
        val maxMs: Long?
    )

    /**
     * 执行 TCP Ping。
     * @param target 目标 IP 或域名
     * @param count 探测次数
     * @param port 探测端口（默认 443）
     * @param timeoutMs 单次超时（毫秒），超时判定为丢包
     * @param bindIp 本机源地址（如局域网接口 IP，非空时绑定该地址），
     *   让诊断流量走指定网卡而非系统默认路由；null 保持默认行为
     */
    suspend fun ping(
        target: String,
        count: Int = 4,
        port: Int = 443,
        timeoutMs: Long = 2000,
        bindIp: String? = null
    ): PingResult = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Long?>()
        repeat(count) {
            val elapsed = try {
                withTimeout(timeoutMs) {
                    measureTimeMillis {
                        Socket().use { socket ->
                            // 绑定本机源地址：让 TCP connect 从指定接口发出（局域网无外网时诊断流量不误走移动数据）
                            if (bindIp != null) {
                                socket.bind(InetSocketAddress(bindIp, 0))
                            }
                            socket.connect(InetSocketAddress(target, port), timeoutMs.toInt())
                        }
                    }
                }
            } catch (e: Exception) {
                null // 超时或连接失败 → 丢包
            }
            samples.add(elapsed)
        }
        val ok = samples.filterNotNull()
        PingResult(
            target = target,
            sent = count,
            received = ok.size,
            lossPercent = if (count == 0) 100 else (count - ok.size) * 100 / count,
            avgMs = if (ok.isEmpty()) null else ok.sum() / ok.size,
            minMs = ok.minOrNull(),
            maxMs = ok.maxOrNull()
        )
    }
}
