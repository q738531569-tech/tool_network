package com.installer.toolbox.core.capture

/**
 * 周期统计快照：CaptureEngine 每个统计周期（默认 1 秒）产出一次，
 * 通过 [CaptureBus] 推送给 UI（ViewModel / Compose）。
 *
 * 注意：本快照中的计数均为"本周期内"的值（周期计数器在产出后被重置），
 * 不是累计值。
 *
 * @param timestampMs 快照产出时刻（System.currentTimeMillis()）
 * @param totalPackets 本周期抓到的 IP 包总数
 * @param totalBytes 本周期总字节数（按 IPv4 头 totalLength 字段计）
 * @param packetsPerSec 本周期包速率（包/秒）
 * @param bytesPerSec 本周期字节速率（字节/秒）
 * @param tcpPackets 本周期 TCP 包数（protocol=6）
 * @param udpPackets 本周期 UDP 包数（protocol=17）
 * @param icmpPackets 本周期 ICMP 包数（protocol=1）
 * @param otherPackets 本周期其他协议包数
 * @param broadcastPackets 本周期广播包数（255.255.255.255 / 子网广播 / 0.0.0.0）
 * @param multicastPackets 本周期组播包数（目的地址首字节 224..239）
 * @param broadcastRatio 广播+组播占总包比例（0..1），无包时为 0
 * @param stormState 本周期结束时的风暴检测状态（由 [BroadcastStormDetector] 判定）
 * @param periodSeconds 本周期实际时长（秒），用于速率换算
 */
data class TrafficSnapshot(
    val timestampMs: Long,
    val totalPackets: Long,
    val totalBytes: Long,
    val packetsPerSec: Double,
    val bytesPerSec: Double,
    val tcpPackets: Long,
    val udpPackets: Long,
    val icmpPackets: Long,
    val otherPackets: Long,
    val broadcastPackets: Long,
    val multicastPackets: Long,
    val broadcastRatio: Double,
    val stormState: StormState = StormState.NORMAL,
    val periodSeconds: Double = 1.0
)
