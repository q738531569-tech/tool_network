package com.installer.toolbox.core.network.cable

/**
 * 网线测试（PDA 有线网口直插测线，需求 2026-08-17）。
 *
 * 原理：root 读网卡 sysfs 状态——
 * - carrier：link 通断（0=完全不通：线断/严重短路）
 * - speed：协商速率（**1000→100 降级 = 8 芯只剩 2 对工作 = 断芯/短芯铁证**；100→10 更严重）
 * - duplex：全双工/半双工
 * - statistics：错误帧计数（rx_crc_errors/rx_frame_errors/rx_errors/tx_errors/rx_dropped）
 *   ——检测窗口内**增长 = 物理层不稳定**（短路/干扰/接触不良）
 * - **ping 对端（网关）丢包率/延迟/抖动**：窗口结束后 ping 一轮（12 包×0.5s），
 *   **丢包率高 = 线质量差铁证**（线材差/接触不良/受干扰），延迟大+抖动大 = 链路不稳
 *
 * 局限（如实）：本工具是**表现型推断**，不是万用表/线缆仪——
 * - **能测**：link 通断（线断/严重短路）、协商速率降级（1000→100=断芯短芯铁证）、
 *   双工模式、错误帧窗口内增长（短路/干扰/接触不良的特征）、
 *   **ping 对端丢包/延迟抖动（线质量差/丢包严重直接判断）**；
 * - **测不出**：具体哪对芯断/短、短路点位置（需 TDR/万用表）、
 *   轻微短路且链路照常协商时的异常；
 * - **前提**：被测线对端必须接通电设备（交换机/路由/光猫），否则 carrier 恒 0，无法协商；
 *   ping 丢包需网口拿到 IP（对端 DHCP）且对端设备自身正常（对端忙/坏会误判为线问题）。
 *
 * 2026-08-18 用户拍板：**不再自动拉起网口**（原对抗 PDA 3 秒自动关闭的自动拉起已去掉，
 * 网口由用户自己手动开启，工具只读不干预）。
 */
object CableProbe {

    /** 判定结果（含原始读数，UI/AI 直接展示） */
    data class CableResult(
        val interfaceName: String?,      // 检测的网口名（如 eth0）；null=无有线网口
        val carrier: Int?,               // 1=link up 0=down（null=读取失败）
        val speedMbps: Int?,             // 协商速率（1000/100/10；null=未知/未协商）
        val duplex: String?,             // full/half
        val operstate: String?,          // up/down/unknown
        val rxCrcErrors: Long?,          // 窗口末 CRC 错误帧
        val rxFrameErrors: Long?,        // 窗口末帧错误
        val rxErrors: Long?,             // 窗口末收错误总数
        val txErrors: Long?,             // 窗口末发错误
        val rxDropped: Long?,            // 窗口末丢弃
        val crcDelta: Long = 0,          // 窗口内 CRC 增长
        val lossPercent: Double? = null, // ping 对端（网关）丢包率 %（网口有 IP 才测）
        val avgMs: Double? = null,       // ping 平均延迟 ms
        val jitterMs: Double? = null,    // ping 抖动 mdev ms
        val verdict: String,             // 判定文本（中文，直接展示）
        val note: String? = null         // 附加说明
    )

    /** 常见有线网口名前缀（排除 wlan/lo/rmnet/virtual 等） */
    private val ETH_PREFIX = listOf("eth", "en")

    /**
     * 枚举有线网口（root 读 /sys/class/net，排除 wlan、lo、rmnet、ran0 等无线与虚拟口）。
     * @param rootRun root 命令执行器（读目录与文件）
     */
    fun findEthernetInterfaces(rootRun: (String) -> String): List<String> {
        val out = rootRun("ls /sys/class/net").trim()
        if (out.startsWith("root 执行失败") || out.startsWith("命令退出码") || out.isBlank()) return emptyList()
        return out.split(Regex("\\s+")).filter { name ->
            ETH_PREFIX.any { name.startsWith(it) } &&
                !name.startsWith("wlan") && name != "lo"
        }
    }

    /** 读 sysfs 单值（root）；异常/空返回 null */
    private fun readSys(rootRun: (String) -> String, path: String): String? {
        val v = rootRun("cat $path 2>/dev/null").trim()
        if (v.startsWith("root 执行失败") || v.startsWith("命令退出码") || v.isBlank() || v.contains("No such")) return null
        return v
    }

    /** 读单个接口当前状态快照（不检测窗口） */
    fun readStatus(iface: String, rootRun: (String) -> String): CableResult {
        val carrier = readSys(rootRun, "/sys/class/net/$iface/carrier")?.toIntOrNull()
        val speed = readSys(rootRun, "/sys/class/net/$iface/speed")?.toIntOrNull()
        val duplex = readSys(rootRun, "/sys/class/net/$iface/duplex")
        val operstate = readSys(rootRun, "/sys/class/net/$iface/operstate")
        val crc = readSys(rootRun, "/sys/class/net/$iface/statistics/rx_crc_errors")?.toLongOrNull()
        val frame = readSys(rootRun, "/sys/class/net/$iface/statistics/rx_frame_errors")?.toLongOrNull()
        val rxErr = readSys(rootRun, "/sys/class/net/$iface/statistics/rx_errors")?.toLongOrNull()
        val txErr = readSys(rootRun, "/sys/class/net/$iface/statistics/tx_errors")?.toLongOrNull()
        val dropped = readSys(rootRun, "/sys/class/net/$iface/statistics/rx_dropped")?.toLongOrNull()
        return CableResult(
            interfaceName = iface, carrier = carrier, speedMbps = speed, duplex = duplex,
            operstate = operstate, rxCrcErrors = crc, rxFrameErrors = frame,
            rxErrors = rxErr, txErrors = txErr, rxDropped = dropped,
            verdict = "待检测"
        )
    }

    /**
     * 网线测试主流程（**不再自动拉起网口**——2026-08-18 用户拍板：网口由用户自己手动开，
     * 工具只读不干预）：
     * 1. 读起始错误帧计数
     * 2. 等待窗口（默认 10 秒，持续采集 carrier/speed 变化）
     * 3. 读结束计数 → 窗口后 ping 对端（网关）统计丢包/延迟/抖动 → 判定
     *
     * @param durationSec 检测窗口秒数（默认 10，范围 5-30）
     * @param onSample 窗口内每次采样回调（UI 实时显示 carrier/speed/错误增长）
     */
    suspend fun test(
        iface: String,
        rootRun: (String) -> String,
        durationSec: Int = 10,
        onSample: (CableResult) -> Unit = {}
    ): CableResult {
        val sec = durationSec.coerceIn(5, 30)
        // 1. 起始读数
        val start = readStatus(iface, rootRun)
        val startCrc = start.rxCrcErrors ?: 0L
        var last = start

        // 2. 窗口内采样（每 2 秒读状态；网口状态由用户手动控制，不干预）
        val steps = (sec / 2).coerceAtLeast(1)
        repeat(steps) {
            kotlinx.coroutines.delay(2000)
            val s = readStatus(iface, rootRun)
            onSample(s.copy(crcDelta = (s.rxCrcErrors ?: 0L) - startCrc))
            last = s
        }

        // 3. 判定：错误帧增长 + 窗口后 ping 对端（网关）丢包/延迟统计
        val crcDelta = (last.rxCrcErrors ?: 0L) - startCrc
        val frameDelta = (last.rxFrameErrors ?: 0L) - (start.rxFrameErrors ?: 0L)
        val ping = if (last.carrier == 1) measureLoss(iface, rootRun) else null
        val verdict = buildVerdict(last, crcDelta, frameDelta, ping)
        return last.copy(
            crcDelta = crcDelta,
            lossPercent = ping?.lossPercent, avgMs = ping?.avgMs, jitterMs = ping?.jitterMs,
            verdict = verdict.first, note = verdict.second
        )
    }

    /** ping 统计结果 */
    data class PingStat(
        val lossPercent: Double?,   // 丢包率 %
        val avgMs: Double?,         // 平均延迟 ms
        val jitterMs: Double?       // 抖动（mdev）ms
    )

    /** 小端十六进制 IP（/proc/net/route 网关列）→ 点分十进制 */
    private fun parseHexIp(hex: String): String? {
        val h = hex.toLongOrNull(16) ?: return null
        return "${h and 0xFF}.${(h shr 8) and 0xFF}.${(h shr 16) and 0xFF}.${(h shr 24) and 0xFF}"
    }

    /**
     * 网口对端（网关）IP，按可靠性依次：
     * 1. /proc/net/route 该接口的 default 路由网关（小端十六进制；Android 默认网络切到有线时存在）
     * 2. /proc/net/arp 该接口已解析的邻居（DHCP 与对端交换时一定留下 ARP 记录——光猫/交换机必中）
     * 3. ip route show 兜底（toybox 显示不完整，仅备用）
     */
    private fun gatewayFor(iface: String, rootRun: (String) -> String): String? {
        // 1. /proc/net/route：Default 列 00000000 且 Gateway 非 0 → 网关
        val proc = rootRun("cat /proc/net/route 2>/dev/null").trim()
        if (!proc.startsWith("root 执行失败") && !proc.startsWith("命令退出码") && proc.isNotBlank()) {
            for (l in proc.lines().drop(1)) {
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[0] == iface && p[1] == "00000000" && p[2] != "00000000") {
                    parseHexIp(p[2])?.let { return it }
                }
            }
        }
        // 2. /proc/net/arp：该接口已解析邻居（对端 IP；Flags 非 0 = 有响应）
        val arp = rootRun("cat /proc/net/arp 2>/dev/null").trim()
        if (!arp.startsWith("root 执行失败") && !arp.startsWith("命令退出码") && arp.isNotBlank()) {
            for (l in arp.lines().drop(1)) {
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 6 && p[5] == iface && p[0].contains(".")) {
                    return p[0]   // 第一个解析到的邻居即对端
                }
            }
        }
        // 3. 兜底：ip route show 里该接口带 via 的路由
        val out = rootRun("ip route show").trim()
        if (out.startsWith("root 执行失败") || out.startsWith("命令退出码") || out.isBlank()) return null
        for (l in out.lines()) {
            if (l.contains("dev $iface")) {
                Regex("via\\s+([\\d.]+)").find(l)?.groupValues?.get(1)?.let { return it }
            }
        }
        return null
    }

    /**
     * ping 对端（网关）统计丢包/延迟/抖动（线质量直接体现：**丢包率高 = 线质量差**）。
     * @return null=网口无网关（没拿 IP）或 ping 不可用（此时跳过丢包判定）
     */
    fun measureLoss(iface: String, rootRun: (String) -> String, count: Int = 12): PingStat? {
        val gw = gatewayFor(iface, rootRun) ?: return null
        // -c 发包数 -i 0.5 间隔 0.5s（12 包 ≈ 6 秒） -W 1 单包超时 1s
        val out = rootRun("ping -c $count -i 0.5 -W 1 $gw 2>/dev/null").trim()
        if (out.startsWith("root 执行失败") || out.startsWith("命令退出码") || out.isBlank()) return null
        val loss = Regex("([\\d.]+)% packet loss").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
        val rtt = Regex("rtt min/avg/max/mdev = [\\d.]+/([\\d.]+)/[\\d.]+/([\\d.]+)").find(out)
        val avg = rtt?.groupValues?.get(1)?.toDoubleOrNull()
        val jitter = rtt?.groupValues?.get(2)?.toDoubleOrNull()
        return PingStat(loss, avg, jitter)
    }

    /** 判定逻辑（可测出的结果列全；丢包率高 = 线质量差铁证） */
    private fun buildVerdict(
        r: CableResult, crcDelta: Long, frameDelta: Long, ping: PingStat?
    ): Pair<String, String?> {
        val carrier = r.carrier
        val speed = r.speedMbps
        val loss = ping?.lossPercent
        val avg = ping?.avgMs?.let { "%.1f".format(it) }
        val jitter = ping?.jitterMs?.let { "%.1f".format(it) }
        return when {
            carrier == null && r.operstate == null ->
                "读取失败：网口状态不可读（可能已被系统关闭或无此接口）" to "检查网口是否已手动开启，或确认接口名"
            carrier == 0 || r.operstate == "down" ->
                "网口无法建立连接（link down）：线缆可能完全断开或严重短路" to
                    "确认网口已手动开启、对端设备通电且网线两端都插好；若都正常仍无 link，多为线缆物理问题（线断/芯线短路）"
            speed == 100 ->
                "⚠ 疑似断芯/短芯：协商速率降为 100M（千兆线只剩 2 对线工作，8 芯中部分芯断/短）" to
                    "检查水晶头压接与芯线通断（重点 1/2/3/6 线对）"
            speed == 10 ->
                "⚠ 严重降速（10M）：线缆问题严重（多对线断/短），仅剩单对线勉强通信" to "建议更换网线或重做水晶头"
            crcDelta > 50 || frameDelta > 50 ->
                "⚠ 物理层异常：错误帧窗口内增长（CRC ${crcDelta} / 帧错误 ${frameDelta}）：短路/干扰/接触不良" to
                    "芯线短接或接触不良会产生大量错误帧，建议查水晶头与线缆"
            loss != null && loss >= 10 ->
                "⚠ 线质量差：ping 对端丢包 ${loss}%（延迟 ${avg ?: "?"}ms / 抖动 ${jitter ?: "?"}ms），对端设备正常时多为线缆质量/接触不良" to
                    "建议更换网线或重做水晶头，检查线体是否被压/老化"
            loss != null && loss >= 2 ->
                "⚠ 有丢包 ${loss}%（延迟 ${avg ?: "?"}ms / 抖动 ${jitter ?: "?"}ms）：线质量欠佳或受干扰" to
                    "线缆质量下降，建议查水晶头与线体；可加长检测时长复核"
            carrier == 1 && speed == 1000 && (loss == null || loss == 0.0) ->
                "✅ 正常：1000M 全双工，窗口内无错误帧增长、ping 对端无丢包" to "线缆 8 芯工作正常"
            else ->
                "✅ 基本正常：link 正常（速率 ${speed ?: "?"}M），错误帧无明显增长${loss?.let { "，ping 丢包 $it%" } ?: ""}" to "如仍有异常可加长检测时长"
        }
    }
}
