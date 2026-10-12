package com.installer.toolbox.core.network.util

/**
 * 公共 `/proc/net/arp` 表解析工具（统一放宽 ARP 条目收录规则）。
 *
 * 背景：真机排查发现，被攻击过/未确认的 ARP 条目 flags 可能为 `0x0` 但**仍带真实 MAC**
 * （如 `192.168.100.110 0x1 0x0 42:08:93:59:51:0f * wlan0`）。旧逻辑各模块普遍要求
 * `Flags == 0x2`（reachable）才收录，导致这类条目的 MAC 丢失、PCDN 多 IP 特征识别不到。
 *
 * 统一规则：**只要 HWaddress 是合法 MAC 且非全零即收录**，不再要求 Flags 为 0x2。
 * 全零 MAC（00:00:00:00:00:00，即 INCOMPLETE 尚未解析）仍排除——它没有真实链路层地址。
 */
object ArpTable {

    /** 合法 MAC 格式 `xx:xx:xx:xx:xx:xx`（十六进制，大小写不限） */
    private val MAC_RE = Regex("""^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$""")

    /** 全零 MAC（INCOMPLETE 条目，未解析到真实地址） */
    private val ZERO_MAC = "00:00:00:00:00:00"

    /** IPv4 简单格式（用于跳过表头等非数据行） */
    private val IPV4_RE = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * 解析 `/proc/net/arp` 文本 → IP → MAC 映射。
     *
     * 行格式：`IP HWtype Flags HWaddress Mask Device`（按空白分割，列数可能因掩码列为 `*` 而变化）。
     * 收录规则：首行表头跳过；HWaddress（第 4 列）为合法 MAC 且非全零即收录，
     * **不再要求 Flags == 0x2**（放宽 0x0 未确认条目，它们同样带历史真实 MAC）。
     * 返回的 MAC 统一小写。
     */
    fun parse(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            // 至少需要 4 列（IP / HWtype / Flags / HWaddress），且首列必须是 IPv4（跳过表头行）
            if (fields.size < 4 || !IPV4_RE.matches(fields[0])) return@forEach
            val mac = fields[3]
            if (!isUsableMac(mac)) return@forEach
            map[fields[0]] = mac.lowercase()
        }
        return map
    }

    /** 从 `/proc/net/arp` 文本中查指定 IP 的 MAC（未收录返回 null） */
    fun macOf(arpText: String, ip: String): String? = parse(arpText)[ip]

    /**
     * 按 MAC 分组 IP 列表（"一 MAC 多 IP" 检测用），键为小写 MAC。
     * 二级路由/NAT 场景下，同一台设备的 MAC 会同时出现在多个 IP 条目中。
     */
    fun ipGroupsByMac(arpText: String): Map<String, List<String>> {
        val groups = mutableMapOf<String, MutableList<String>>()
        parse(arpText).forEach { (ip, mac) ->
            groups.getOrPut(mac) { mutableListOf() }.add(ip)
        }
        return groups
    }

    /** 是否可用的 MAC：合法格式且非全零（全零 = 未解析，排除） */
    private fun isUsableMac(mac: String): Boolean =
        MAC_RE.matches(mac) && !mac.equals(ZERO_MAC, ignoreCase = true)
}
