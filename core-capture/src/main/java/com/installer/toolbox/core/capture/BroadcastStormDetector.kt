package com.installer.toolbox.core.capture

/**
 * 风暴状态（附中文说明文字，供 UI 直接展示）。
 */
enum class StormState(val description: String) {
    /** 流量正常 */
    NORMAL("流量正常"),

    /** 广播/组播流量偏高，需关注 */
    WARNING("广播/组播流量偏高"),

    /** 疑似广播风暴 */
    STORM("疑似广播风暴！")
}

/**
 * 广播/组播风暴检测器（启发式规则，参数可调）。
 *
 * 判定规则（启发式）：
 * - 广播包速率 =（广播包 + 组播包）/ 本周期时长（包/秒）；
 * - 广播占比 =（广播包 + 组播包）/ 总包数；
 * - 连续 [triggerPeriods] 个周期速率 > [rateThreshold] 且占比 > [ratioThreshold]
 *   → 判定 STORM；
 * - STORM 之后连续 [clearPeriods] 个周期回落 → 解除为 NORMAL
 *   （回落未满时保持 WARNING，避免状态抖动）；
 * - 超标但未达触发次数 → WARNING。
 *
 * 注意：VpnService 的 tun 接口只能看到 L3 IP 包（看不到 ARP 等 L2 帧），
 * 因此广播风暴检测基于 IP 层广播/组播包速率；完整的 L2 广播风暴
 * （如 ARP 风暴）检测需要 root 通道，将在后续里程碑实现。
 *
 * @param windowSize 环形缓冲容量：最近 N 个周期的广播包速率
 * @param rateThreshold 速率阈值（包/秒）
 * @param ratioThreshold 占比阈值（0..1）
 * @param triggerPeriods 连续超标多少个周期触发 STORM
 * @param clearPeriods 连续回落多少个周期解除 STORM
 */
class BroadcastStormDetector(
    private val windowSize: Int = 12,
    private val rateThreshold: Double = 800.0,
    private val ratioThreshold: Double = 0.2,
    private val triggerPeriods: Int = 3,
    private val clearPeriods: Int = 3
) {

    /** 最近 [windowSize] 个周期的广播+组播包速率环形缓冲（包/秒） */
    private val rateHistory = ArrayDeque<Double>(windowSize)

    /** 连续超标周期数 */
    private var highStreak = 0

    /** 连续回落周期数 */
    private var lowStreak = 0

    /** 是否曾进入 STORM（用于 STORM 的滞后解除） */
    private var wasStorm = false

    /**
     * 用新快照更新检测状态并返回当前风暴状态。
     */
    fun detect(snapshot: TrafficSnapshot): StormState {
        val rate = broadcastRateOf(snapshot)
        rateHistory.addLast(rate)
        while (rateHistory.size > windowSize) rateHistory.removeFirst()

        val isHigh = rate > rateThreshold && snapshot.broadcastRatio > ratioThreshold
        if (isHigh) {
            highStreak++
            lowStreak = 0
        } else {
            lowStreak++
            highStreak = 0
        }
        if (lowStreak >= clearPeriods) wasStorm = false

        val next = when {
            highStreak >= triggerPeriods -> StormState.STORM
            lowStreak >= clearPeriods -> StormState.NORMAL
            isHigh || wasStorm -> StormState.WARNING
            else -> StormState.NORMAL
        }
        if (next == StormState.STORM) wasStorm = true
        return next
    }

    /** 最近一个周期的广播+组播包速率（包/秒）；尚无数据时为 0 */
    fun currentBroadcastRate(): Double = rateHistory.lastOrNull() ?: 0.0

    private fun broadcastRateOf(snapshot: TrafficSnapshot): Double {
        if (snapshot.totalPackets <= 0L || snapshot.periodSeconds <= 0.0) return 0.0
        return (snapshot.broadcastPackets + snapshot.multicastPackets).toDouble() / snapshot.periodSeconds
    }
}
