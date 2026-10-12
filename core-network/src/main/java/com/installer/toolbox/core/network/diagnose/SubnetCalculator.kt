package com.installer.toolbox.core.network.diagnose

import com.installer.toolbox.core.network.util.IpUtil

/**
 * IPv4 子网计算器（IP 计算器核心逻辑，纯 JVM 无依赖）。
 *
 * 输入：IP 地址 + CIDR 前缀（0-32）或子网掩码（如 255.255.255.0）。
 * 输出：网络地址、广播地址、可用主机范围、主机数、掩码、通配符、
 * 私有/保留地址判断、IP 类别。
 */
object SubnetCalculator {

    /** 计算结果 */
    data class CalcResult(
        /** 输入的 IP */
        val ip: String,
        /** CIDR 前缀长度（0-32） */
        val prefix: Int,
        /** 子网掩码（点分十进制） */
        val mask: String,
        /** 网络地址 */
        val network: String,
        /** 广播地址 */
        val broadcast: String,
        /** 第一个可用主机（/31 /32 无可用主机时与 network 相同） */
        val firstHost: String,
        /** 最后一个可用主机 */
        val lastHost: String,
        /** 总主机数（2^(32-prefix)） */
        val totalHosts: Long,
        /** 可用主机数（/31 /32 特殊处理） */
        val usableHosts: Long,
        /** 通配符掩码 */
        val wildcard: String,
        /** 是否私有地址（RFC1918 等） */
        val isPrivate: Boolean,
        /** 是否保留/特殊地址（环回、链路本地、组播、广播等） */
        val isReserved: Boolean,
        /** IP 类别（A-E） */
        val ipClass: String
    )

    /**
     * 计算子网信息。
     * @param ipStr IPv4 地址（点分十进制）
     * @param prefixOrMask CIDR 前缀（如 "24"）或子网掩码（如 "255.255.255.0"）
     * @return 计算成功返回 [CalcResult]；输入非法返回 null
     */
    fun calculate(ipStr: String, prefixOrMask: String): CalcResult? {
        val ip = IpUtil.ipToInt(ipStr.trim()) ?: return null
        val prefix = maskToPrefix(prefixOrMask.trim()) ?: return null
        return calculate(ip, prefix, ipStr.trim())
    }

    private fun calculate(ip: Long, prefix: Int, ipDisplay: String): CalcResult {
        // 子网掩码：高 prefix 位为 1
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (0xFFFFFFFFL xor mask)

        // 主机数
        val totalHosts = if (prefix >= 31) {
            if (prefix == 32) 1L else 2L
        } else {
            1L shl (32 - prefix)
        }
        val usableHosts = when {
            prefix >= 31 -> 0L
            else -> totalHosts - 2
        }

        // 可用范围（/31 点对点无可用主机，/32 单主机）
        val firstHost = if (prefix >= 31) network else network + 1
        val lastHost = if (prefix >= 31) broadcast else broadcast - 1

        val ipClass = classify(ip)
        return CalcResult(
            ip = ipDisplay,
            prefix = prefix,
            mask = longToIp(mask),
            network = longToIp(network),
            broadcast = longToIp(broadcast),
            firstHost = longToIp(firstHost),
            lastHost = longToIp(lastHost),
            totalHosts = totalHosts,
            usableHosts = usableHosts,
            wildcard = longToIp(0xFFFFFFFFL xor mask),
            isPrivate = isPrivate(ip),
            isReserved = isReserved(ip),
            ipClass = ipClass
        )
    }

    /**
     * 把 CIDR 前缀或点分掩码转前缀长度。
     * @return 合法前缀 0-32；非法返回 null
     */
    fun maskToPrefix(input: String): Int? {
        // 纯数字 = CIDR 前缀
        val asInt = input.toIntOrNull()
        if (asInt != null) return if (asInt in 0..32) asInt else null
        // 点分掩码
        val v = IpUtil.ipToInt(input) ?: return null
        val bits = java.lang.Long.bitCount(v)
        // 校验掩码为连续 1（如 255.255.255.0 = 11111111.11111111.11111111.0）
        val expected = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return if (v == expected) bits else null
    }

    /** 前缀长度转点分掩码 */
    fun prefixToMask(prefix: Int): String {
        val p = prefix.coerceIn(0, 32)
        val mask = if (p == 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return longToIp(mask)
    }

    /** 32 位无符号 Long 转点分十进制 */
    private fun longToIp(v: Long): String {
        val x = v and 0xFFFFFFFFL
        return "${(x ushr 24) and 0xFF}.${(x ushr 16) and 0xFF}.${(x ushr 8) and 0xFF}.${x and 0xFF}"
    }

    /** RFC1918 私有地址 + 环回 + 链路本地 */
    private fun isPrivate(ip: Long): Boolean {
        // 10.0.0.0/8
        if ((ip and 0xFF000000L) == 0x0A000000L) return true
        // 172.16.0.0/12
        if ((ip and 0xFFF00000L) == 0xAC100000L) return true
        // 192.168.0.0/16
        if ((ip and 0xFFFF0000L) == 0xC0A80000L) return true
        // 169.254.0.0/16 链路本地
        if ((ip and 0xFFFF0000L) == 0xA9FE0000L) return true
        // 127.0.0.0/8 环回
        if ((ip and 0xFF000000L) == 0x7F000000L) return true
        return false
    }

    /** 保留/特殊地址：0.0.0.0/8、环回、链路本地、组播、保留 E、广播 */
    private fun isReserved(ip: Long): Boolean {
        // 0.0.0.0/8
        if ((ip and 0xFF000000L) == 0L) return true
        // 127.0.0.0/8
        if ((ip and 0xFF000000L) == 0x7F000000L) return true
        // 169.254.0.0/16
        if ((ip and 0xFFFF0000L) == 0xA9FE0000L) return true
        // 224.0.0.0/4 组播
        if ((ip and 0xF0000000L) == 0xE0000000L) return true
        // 240.0.0.0/4 保留
        if ((ip and 0xF0000000L) == 0xF0000000L) return true
        return false
    }

    /** IP 类别（按首字节） */
    private fun classify(ip: Long): String {
        val first = (ip ushr 24) and 0xFF
        return when {
            first < 128 -> "A"
            first < 192 -> "B"
            first < 224 -> "C"
            first < 240 -> "D（组播）"
            else -> "E（保留）"
        }
    }
}
