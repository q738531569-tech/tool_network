package com.installer.toolbox.core.capture

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 抓包引擎（纯逻辑，不依赖 Android Service，便于测试）：
 * - 读线程：阻塞读取 tun fd（FileInputStream），逐包解析并累加周期计数；
 * - 定时线程：按固定间隔（默认 1 秒）产出 [TrafficSnapshot] 并重置周期计数。
 *
 * 注意：tun 是包设备，每次 read 返回一个完整 L3 IP 包；只能看到 IP 层
 * （IPv4/IPv6），ARP 等 L2 帧不可见（广播风暴检测基于 IP 层广播/组播速率）。
 *
 * @param fileDescriptor VpnService.establish() 返回的 tun fd
 * @param stormDetector 广播/组播风暴检测器
 * @param intervalMs 统计周期（毫秒），同时作为风暴检测的一个周期
 */
class CaptureEngine(
    private val fileDescriptor: FileDescriptor,
    private val stormDetector: BroadcastStormDetector = BroadcastStormDetector(),
    private val intervalMs: Long = 1000L
) {

    /** 单次 read 的最大字节数（IPv4 最大包长 65535） */
    private val readBuffer = ByteArray(65535)

    private val running = AtomicBoolean(false)
    private var readThread: Thread? = null
    private var tickThread: Thread? = null
    private var inputStream: FileInputStream? = null

    /** 周期计数器：读线程累加、定时线程读取并重置（synchronized 保护） */
    private data class PeriodCounters(
        var totalPackets: Long = 0L,
        var totalBytes: Long = 0L,
        var tcpPackets: Long = 0L,
        var udpPackets: Long = 0L,
        var icmpPackets: Long = 0L,
        var otherPackets: Long = 0L,
        var broadcastPackets: Long = 0L,
        var multicastPackets: Long = 0L
    ) {
        fun reset() {
            totalPackets = 0L
            totalBytes = 0L
            tcpPackets = 0L
            udpPackets = 0L
            icmpPackets = 0L
            otherPackets = 0L
            broadcastPackets = 0L
            multicastPackets = 0L
        }
    }

    private val counters = PeriodCounters()

    /**
     * 启动抓包（读线程 + 定时线程）。已在运行时重复调用无效。
     * @param onSnapshot 每个统计周期产出一个快照时回调（在定时线程上调用）
     */
    fun start(onSnapshot: (TrafficSnapshot) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        readThread = Thread({ readLoop() }, "capture-read").apply {
            isDaemon = true
            start()
        }
        tickThread = Thread({ tickLoop(onSnapshot) }, "capture-tick").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 停止抓包：置标志 + 关闭流（令阻塞中的 read 返回/抛错）+ 中断线程。
     * 关闭流会让阻塞在 tun fd 上的 read 立即返回 -1（或抛 IOException），
     * 从而退出读循环。
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            inputStream?.close()
        } catch (e: IOException) {
            // 忽略关闭异常
        }
        readThread?.interrupt()
        tickThread?.interrupt()
        readThread = null
        tickThread = null
    }

    /** 读循环：阻塞 read tun fd，逐包解析并累加。fd 关闭或异常时退出。 */
    private fun readLoop() {
        val stream = FileInputStream(fileDescriptor)
        inputStream = stream
        try {
            while (running.get()) {
                val read = stream.read(readBuffer)
                if (read <= 0) break // fd 已关闭
                // 畸形包 / 非 IPv4 包返回 null，直接忽略不计入统计
                val info = IpPacketParser.parse(readBuffer, read) ?: continue
                synchronized(counters) {
                    counters.totalPackets++
                    counters.totalBytes += info.packetLength
                    when (info.protocol) {
                        IpPacketParser.PROTOCOL_ICMP -> counters.icmpPackets++
                        IpPacketParser.PROTOCOL_TCP -> counters.tcpPackets++
                        IpPacketParser.PROTOCOL_UDP -> counters.udpPackets++
                        else -> counters.otherPackets++
                    }
                    if (info.isBroadcast) counters.broadcastPackets++
                    if (info.isMulticast) counters.multicastPackets++
                }
            }
        } catch (e: IOException) {
            // fd 被关闭（stop() 或 VPN 被撤销）：正常退出
        } finally {
            try {
                stream.close()
            } catch (e: IOException) {
                // 忽略关闭异常
            }
            inputStream = null
        }
    }

    /** 定时循环：每 intervalMs 产出一个快照并重置周期计数。 */
    private fun tickLoop(onSnapshot: (TrafficSnapshot) -> Unit) {
        while (running.get()) {
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                break
            }
            if (!running.get()) break
            try {
                emitSnapshot(onSnapshot)
            } catch (e: Exception) {
                // 单次快照产出失败不影响抓包继续
            }
        }
    }

    /** 读取并重置周期计数，构造快照，经风暴检测器判定后回调。 */
    private fun emitSnapshot(onSnapshot: (TrafficSnapshot) -> Unit) {
        val c = synchronized(counters) {
            val copy = counters.copy()
            counters.reset()
            copy
        }
        val periodSeconds = intervalMs / 1000.0
        val broadcastTotal = c.broadcastPackets + c.multicastPackets
        val ratio = if (c.totalPackets > 0) broadcastTotal.toDouble() / c.totalPackets else 0.0
        val snapshot = TrafficSnapshot(
            timestampMs = System.currentTimeMillis(),
            totalPackets = c.totalPackets,
            totalBytes = c.totalBytes,
            packetsPerSec = c.totalPackets / periodSeconds,
            bytesPerSec = c.totalBytes / periodSeconds,
            tcpPackets = c.tcpPackets,
            udpPackets = c.udpPackets,
            icmpPackets = c.icmpPackets,
            otherPackets = c.otherPackets,
            broadcastPackets = c.broadcastPackets,
            multicastPackets = c.multicastPackets,
            broadcastRatio = ratio,
            periodSeconds = periodSeconds
        )
        // 风暴状态并入快照，随同一 flow 推给 UI
        onSnapshot(snapshot.copy(stormState = stormDetector.detect(snapshot)))
    }
}
