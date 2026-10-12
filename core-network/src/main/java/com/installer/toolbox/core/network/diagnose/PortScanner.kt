package com.installer.toolbox.core.network.diagnose

import com.installer.toolbox.core.network.discovery.PortServiceTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

/**
 * 单主机端口扫描器：TCP connect 探测，协程并发 + Semaphore 限流。
 */
object PortScanner {

    /** 扫描结果中的一个开放端口 */
    data class OpenPort(
        val port: Int,
        /** 常见端口服务名；未知为 null */
        val service: String?
    )

    /** 端口扫描结果 */
    data class PortScanResult(
        val target: String,
        val openPorts: List<OpenPort>,
        /** 已扫描端口数 */
        val scannedCount: Int,
        /** 总耗时（毫秒） */
        val elapsedMs: Long
    )

    /** 默认扫描起始端口 */
    const val DEFAULT_START_PORT = 1

    /** 默认扫描结束端口 */
    const val DEFAULT_END_PORT = 1024

    /** 常见高位端口（1-1024 之外的常用服务） */
    private val extraPorts = listOf(3306, 5432, 6379, 8080, 8443)

    /** 单端口连接超时（毫秒） */
    const val TIMEOUT_MS = 300

    /** 并发上限 */
    private const val CONCURRENCY = 80

    /**
     * 扫描指定主机的端口范围（默认 1-1024 + 常见高位端口）。
     * @param target 目标 IP 或域名
     * @param startPort 起始端口
     * @param endPort 结束端口
     * @param timeoutMs 单端口连接超时（毫秒）
     * @param bindIp 本机源地址（如局域网接口 IP，非空时绑定该地址），
     *   让探测流量走指定网卡而非系统默认路由；null 保持默认行为
     */
    suspend fun scan(
        target: String,
        startPort: Int = DEFAULT_START_PORT,
        endPort: Int = DEFAULT_END_PORT,
        timeoutMs: Int = TIMEOUT_MS,
        bindIp: String? = null
    ): PortScanResult = withContext(Dispatchers.IO) {
        val ports = (startPort..endPort).toList() + extraPorts.filter { it !in startPort..endPort }
        val semaphore = Semaphore(CONCURRENCY)

        var openPorts = emptyList<OpenPort>()
        val elapsed = measureTimeMillis {
            openPorts = coroutineScope {
                ports.map { port ->
                    async {
                        semaphore.withPermit {
                            if (isPortOpen(target, port, timeoutMs, bindIp)) {
                                OpenPort(port, PortServiceTable.nameOf(port))
                            } else {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull().sortedBy { it.port }
            }
        }

        PortScanResult(
            target = target,
            openPorts = openPorts,
            scannedCount = ports.size,
            elapsedMs = elapsed
        )
    }

    /** 单端口 TCP connect 探测 */
    private fun isPortOpen(host: String, port: Int, timeoutMs: Int, bindIp: String?): Boolean {
        return try {
            Socket().use { socket ->
                // 绑定本机源地址：让探测流量从指定接口发出（局域网无外网时不误走移动数据）
                if (bindIp != null) {
                    socket.bind(InetSocketAddress(bindIp, 0))
                }
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
