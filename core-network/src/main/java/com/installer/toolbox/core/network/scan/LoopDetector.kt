package com.installer.toolbox.core.network.scan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 局域网环路检测器（root + tcpdump）。
 *
 * 环路（网线/交换机错误成环、双路由互连）的典型表现是**广播风暴**：
 * 交换机会反复转发广播帧，导致广播/组播包占比与速率暴涨，网络拥塞瘫痪。
 *
 * 检测方法：root 下 `tcpdump` 抓 N 秒链路层帧，统计：
 * - 广播帧（目标 ff:ff:ff:ff:ff:ff）与组播帧（01:00:5e/01:80:c2 等）占比；
 * - 每秒包速率（风暴时可达每秒数百上千）；
 * - **广播帧源 MAC TOP10**（风暴时源 MAC 最集中的设备即元凶，含伪装 MAC 异常设备）；
 * - 排除常驻组播（mDNS 5353 / UPnP 1900 / DHCP）避免误报。
 *
 * 判定（启发式）：广播+组播占比 >30% 且速率较高，或广播帧速率 >30/s → 疑似环路/风暴。
 * 参考：正常网络广播/组播占比通常 <10%。
 */
object LoopDetector {

    /** 检测结果 */
    data class LoopResult(
        val totalPackets: Int,
        val broadcastCount: Int,
        val multicastCount: Int,
        val packetsPerSec: Double,
        val broadcastRatio: Double,
        /** 是否疑似环路/广播风暴 */
        val loopLikely: Boolean,
        val note: String?,
        /** 广播帧源 MAC 排名（帧数降序，结构化；值守告警/定位元凶用） */
        val topBroadcastSrc: List<Pair<String, Int>> = emptyList()
    )

    /** 包行正则：tcpdump 行首时间戳 + 方向标记（LINUX_SLL：In/Out=单播，M=组播，B=广播）+ 源 MAC */
    private val PACKET_LINE = Regex(
        """^\d{2}:\d{2}:\d{2}\.\d+\s+(In|Out|B|M)\s+((?:[0-9a-f]{2}:){5}[0-9a-f]{2})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 环路检测。
     *
     * @param rootRun root 命令通道（如 RootShell::run）；null 时返回不可用提示
     * @param durationSec 抓包时长（秒），建议 8-15
     * @return 统计结果
     */
    suspend fun detect(
        rootRun: ((String) -> String)?,
        durationSec: Int = 8
    ): LoopResult = withContext(Dispatchers.IO) {
        val sec = durationSec.coerceIn(3, 120)
        if (rootRun == null) {
            return@withContext LoopResult(
                0, 0, 0, 0.0, 0.0, false,
                "未提供 root 通道：环路检测需要 root + tcpdump 抓包。可先观察网速/丢包，或登录交换机/路由器查看端口流量排查环路。"
            )
        }
        // -e 打印链路层头（LINUX_SLL 方向标记：B=广播/M=组播），-l 行缓冲，-n 不反解；
        // 排除常驻组播（mDNS/UPnP/DHCP），避免误报；
        // || true：无包被 timeout 杀时退出码 124 无输出，会被 RootShell 转成"命令退出码"提示污染结果
        val cmd = "timeout $sec /data/local/tmp/toolbox_tcpdump -l -n -e -i any " +
            "not port 5353 and not port 1900 and not port 67 and not port 68 2>/dev/null || true"
        val out = withTimeoutOrNull((sec + 5) * 1000L) {
            runCatching { rootRun(cmd) }.getOrNull()
        }.orEmpty()

        var total = 0
        var bcast = 0
        var mcast = 0
        // 广播帧源 MAC 计数：风暴时源 MAC 最集中的设备即元凶（伪装 MAC 的设备会频繁换 MAC 分散统计）
        val bcastSrcCount = HashMap<String, Int>()
        out.lineSequence().forEach { line ->
            val m = PACKET_LINE.find(line) ?: return@forEach
            total++
            when (m.groupValues[1]) {
                "B" -> {
                    bcast++
                    val src = m.groupValues[2]
                    bcastSrcCount[src] = (bcastSrcCount[src] ?: 0) + 1
                }
                "M" -> mcast++
                else -> { /* In/Out 单播仅计数 */ }
            }
        }
        // 广播源 MAC TOP10
        val topSrc = bcastSrcCount.entries.sortedByDescending { it.value }.take(10)
        // timeout 124 为正常到点（不视为失败）；输出为空说明无流量
        val ratio = if (total > 0) (bcast + mcast).toDouble() / total else 0.0
        val pps = total.toDouble() / sec
        val bps = bcast.toDouble() / sec

        val loopLikely = (ratio > 0.3 && pps > 50) || bps > 30
        val note = buildString {
            append("抓包 $sec 秒：共 $total 包（${"%.1f".format(pps)} 包/秒），广播 $bcast 个、组播 $mcast 个，广播+组播占比 ${"%.0f".format(ratio * 100)}%。")
            if (topSrc.isNotEmpty()) {
                append("\n广播帧源 MAC TOP${topSrc.size}：")
                topSrc.forEachIndexed { i, (mac, cnt) ->
                    append("\n  ${i + 1}. $mac × $cnt")
                }
                append("\n（源 MAC 最多的设备通常是风暴/环路的元凶；若同一设备频繁换 MAC 或列表中无对应真实设备，可能为伪装 MAC 的异常设备）")
            }
            if (loopLikely) {
                append("⚠ 广播/组播占比异常偏高——疑似环路或广播风暴（交换机/网线错误成环、双路由互连）。建议：检查交换机级联口是否成环、临时拔掉疑似环路网线观察是否恢复，或登录交换机看端口流量。")
            } else {
                append("广播/组播占比在正常范围（参考 <10%），未见明显环路/风暴特征。")
                if (total < 50) append("（本机流量较小，若怀疑环路可延长抓包时长重测）")
            }
        }
        LoopResult(total, bcast, mcast, pps, ratio, loopLikely, note)
    }
}
