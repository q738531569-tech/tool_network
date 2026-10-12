package com.installer.toolbox.core.capture

import java.nio.ByteBuffer

/**
 * IPv4 包解析器（纯逻辑，无 Android 依赖，便于单元测试）。
 *
 * 注意：VpnService 的 tun 接口只能看到 L3 IP 包（看不到 ARP 等 L2 帧），
 * 因此这里只解析 IPv4；IPv6 / 非 IP 数据一律返回 null（不计入统计）。
 *
 * 解析要点（IPv4 头，RFC 791）：
 * - 字节 0：高 4 位 version（必须为 4），低 4 位 IHL（头长 = IHL * 4）；
 * - 字节 1：TOS（忽略）；
 * - 字节 2-3：totalLength（大端），整包长度；
 * - 字节 9：protocol（1=ICMP 6=TCP 17=UDP）；
 * - 字节 12-15：源 IP；字节 16-19：目的 IP。
 *
 * 容错：长度不足 / 畸形包直接返回 null，绝不抛异常。
 */
object IpPacketParser {

    const val PROTOCOL_ICMP = 1
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    private const val MIN_IP_HEADER_LENGTH = 20
    private const val IP_VERSION_V4 = 4

    /** 组播地址段 224.0.0.0/4（D 类）：目的首字节 224..239 */
    private const val MULTICAST_FIRST_MIN = 224
    private const val MULTICAST_FIRST_MAX = 239

    /** 有限广播地址 */
    private const val LIMITED_BROADCAST = "255.255.255.255"

    /** 空地址：部分探测/异常包以 0.0.0.0 为目的，按广播处理 */
    private const val ZERO_ADDRESS = "0.0.0.0"

    /** 单个包的解析结果 */
    data class PacketInfo(
        /** IP 协议号：1=ICMP 6=TCP 17=UDP，其余为其他协议 */
        val protocol: Int,
        val srcIp: String,
        val dstIp: String,
        val isBroadcast: Boolean,
        val isMulticast: Boolean,
        /** 包长：IPv4 totalLength 与可用长度取 min，防止越界 */
        val packetLength: Int
    )

    /** 从字节数组解析（从下标 0 开始，取前 length 字节）。畸形包返回 null。 */
    fun parse(data: ByteArray, length: Int): PacketInfo? {
        if (length < MIN_IP_HEADER_LENGTH) return null
        return try {
            parseInternal({ i -> data[i].toInt() and 0xFF }, length)
        } catch (e: Exception) {
            null
        }
    }

    /** 从 ByteBuffer 解析（从当前 position 开始，不移动 position）。畸形包返回 null。 */
    fun parse(buffer: ByteBuffer): PacketInfo? {
        if (buffer.remaining() < MIN_IP_HEADER_LENGTH) return null
        val base = buffer.position()
        return try {
            parseInternal({ i -> buffer.get(base + i).toInt() and 0xFF }, buffer.remaining())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 核心解析：readByte(i) 返回第 i 个字节的无符号值，length 为可用字节数。
     * 任何异常由调用方 try-catch 兜底返回 null。
     */
    private fun parseInternal(readByte: (Int) -> Int, length: Int): PacketInfo? {
        // 版本检查：仅支持 IPv4（tun 上可能混入 IPv6 包，直接忽略）
        val version = readByte(0) ushr 4
        if (version != IP_VERSION_V4) return null

        // 头长检查：IHL * 4，最小 20 字节
        val ihl = readByte(0) and 0x0F
        val headerLength = ihl * 4
        if (headerLength < MIN_IP_HEADER_LENGTH) return null

        // 长度检查：totalLength（大端）与可用长度取 min，防止越界
        val totalLength = (readByte(2) shl 8) or readByte(3)
        if (totalLength < headerLength) return null
        val packetLength = minOf(totalLength, length)

        val protocol = readByte(9)
        val srcIp = ipToString(readByte, 12)
        val dstIp = ipToString(readByte, 16)
        val isMulticast = readByte(16) in MULTICAST_FIRST_MIN..MULTICAST_FIRST_MAX
        val isBroadcast = isBroadcastAddress(srcIp, dstIp, readByte(19))

        return PacketInfo(protocol, srcIp, dstIp, isBroadcast, isMulticast, packetLength)
    }

    private fun ipToString(readByte: (Int) -> Int, offset: Int): String =
        "${readByte(offset)}.${readByte(offset + 1)}.${readByte(offset + 2)}.${readByte(offset + 3)}"

    /**
     * 广播判定（启发式，按 /24 简化，不依赖子网掩码）：
     * 1) 有限广播 255.255.255.255；
     * 2) 目的末字节为 255 且前三段与源 IP 相同（/24 子网广播）；
     * 3) 目的为 0.0.0.0（探测/异常包，按广播处理）。
     */
    private fun isBroadcastAddress(srcIp: String, dstIp: String, dstLastByte: Int): Boolean {
        if (dstIp == LIMITED_BROADCAST || dstIp == ZERO_ADDRESS) return true
        if (dstLastByte == 255) {
            val srcPrefix = srcIp.substringBeforeLast('.')
            val dstPrefix = dstIp.substringBeforeLast('.')
            if (srcPrefix == dstPrefix) return true
        }
        return false
    }
}
