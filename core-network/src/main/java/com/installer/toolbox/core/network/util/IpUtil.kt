package com.installer.toolbox.core.network.util

/**
 * IP 校验与网段判断工具（所有 IP 输入统一走这里）。
 *
 * 设计（用户要求）：
 * - **格式硬校验**：输入必须是合法 IPv4（点分十进制，每段 0-255）；
 * - **网段软提示**：不硬禁止非本网段 IP——**一个局域网可能有两个网段**
 *   （如 192.168.1.x 与 192.168.10.x 经路由器互连同属一个 LAN），
 *   目标设备可能在另一网段（路由器可达）。故非本网段 IP 允许输入，
 *   但给出明确提示由使用者确认。
 */
object IpUtil {

    /** 严格 IPv4 正则（每段 0-255） */
    private val IPV4_RE = Regex(
        """((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"""
    )

    /** 校验是否为合法 IPv4（严格：整串匹配 + 四段） */
    fun isValidIp(ip: String): Boolean {
        val t = ip.trim()
        return IPV4_RE.matches(t) && t.split(".").size == 4
    }

    /** 判断 ip 是否在 subnet（如 "192.168.10.0/24"）网段内 */
    fun inSubnet(ip: String, subnet: String): Boolean {
        val ipInt = ipToInt(ip) ?: return false
        val (net, prefix) = parseSubnet(subnet) ?: return false
        val mask = if (prefix >= 32) -1L else ((1L shl prefix) - 1) shl (32 - prefix)
        return (ipInt and mask) == (net and mask)
    }

    /** IPv4 → Long（网络字节序数值）；非法返回 null */
    fun ipToInt(ip: String): Long? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    /** 解析子网 "192.168.10.0/24" → (网络号 Long, 前缀)；非法返回 null */
    fun parseSubnet(subnet: String): Pair<Long, Int>? {
        val s = subnet.trim()
        val slash = s.indexOf('/')
        if (slash <= 0) return null
        val ipPart = s.substring(0, slash)
        val prefix = s.substring(slash + 1).toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val net = ipToInt(ipPart) ?: return null
        return net to prefix
    }
}
