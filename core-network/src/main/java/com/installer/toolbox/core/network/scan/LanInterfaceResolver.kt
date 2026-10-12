package com.installer.toolbox.core.network.scan

import com.installer.toolbox.core.network.util.IpUtil

/**
 * 局域网接口解析器（多网络架构适配）。
 *
 * 背景：装维 PDA 可以同时有 Wi-Fi（wlan0）、有线底座（br0/eth0）与移动数据（rmnet_data0）。
 * 旧逻辑用 `ip route get 8.8.8.8` 取网关/接口——**当局域网没有外网时，Android 的默认路由
 * 会切到移动数据**，此时解析出的是 rmnet 接口与移动网关，导致静默/审计/诊断等局域网工具
 * 失效甚至误操作移动网卡。本解析器改为**枚举真实接口 + 局域网优先**，不盲从默认路由。
 */
object LanInterfaceResolver {

    /** 局域网接口信息 */
    data class LanIface(
        /** 接口名（如 wlan0 / br0 / eth0） */
        val name: String,
        /** 本机在该接口的全局 IPv4 */
        val ip: String,
        /** 该接口所在子网（如 "192.168.1.0/24"） */
        val subnet: String,
        /** 该接口的默认网关（`ip route show dev X` 的 default via；没有则 null） */
        val gateway: String?
    )

    /** 明确排除的虚拟/移动接口（前缀匹配） */
    private val EXCLUDE_PREFIXES = listOf(
        "lo", "dummy", "ifb", "tunl", "gre", "erspan", "sit", "ip6", "ip_vti", "p2p",
        "wifi-aware", "rmnet", "r_rmnet", "miw_oem"
    )

    /** 接口优先级（默认路由接口总是最优先，此表为其余接口的排序；未列出的排最后） */
    private val NAME_PRIORITY = listOf("wlan", "eth", "br", "usb")

    /**
     * root 解析当前"局域网接口"（用于静默/审计/诊断绑定）。
     * 优先级：①**包含 targetIp 子网的接口**（PDA 常见 Wi-Fi+有线双网卡不同网段：
     * 审计/静默目标在哪张网卡就在哪张网卡执行，不再无脑选 wlan）；
     * ②恰好是默认路由出口的接口；③按 wlan > eth > br > usb > 其它 取第一个。
     * 任一命令失败或找不到真实接口返回 null（调用方自行回退旧逻辑）。
     *
     * @param targetIp 可选目标 IP：传入时按目标所在子网优先选接口（双网卡场景按目标走）
     */
    fun resolve(runner: (String) -> String, targetIp: String? = null): LanIface? {
        // 1. 默认路由接口（可能走移动数据，仅作为"优先候选"的参考，不决定结果）
        val defaultDev = runCatching {
            val out = runner("ip route get 8.8.8.8")
            if (out.startsWith("root 执行失败") || out.startsWith("命令退出码")) null
            else Regex("""dev (\S+)""").find(out)?.groupValues?.get(1)
        }.getOrNull()

        // 2. 枚举接口地址：优先 `ip -o addr show`（每行一个 inet），不支持则回退 `ip addr show` 状态机
        var out = runCatching { runner("ip -o addr show") }.getOrDefault("")
        var oneLine = !(out.startsWith("root 执行失败") || out.startsWith("命令退出码")) &&
            out.contains("inet ")
        if (!oneLine) {
            out = runCatching { runner("ip addr show") }.getOrDefault("")
            if (out.startsWith("root 执行失败") || out.startsWith("命令退出码")) return null
        }

        // 候选：(接口名, IPv4, 前缀长度)
        val cands = if (oneLine) parseOneLine(out) else parseIndented(out)
        if (cands.isEmpty()) return null

        // 3. 每候选解析网关（default via）。Android 多网络架构下默认路由在 per-network 表
        //    （如 table wlan0），`ip route show dev X` 的 main 表经常没有 default 行——
        //    逐级：① main 表 ② `ip route get 8.8.8.8 dev X` 强制经该接口查路由
        //    ③ `ip route show table X`（per-network 表名通常等于接口名）
        val withGateway = cands.map { c ->
            val gw = runCatching {
                var gw: String? = null
                for (cmd in listOf(
                    "ip route show dev ${c.first}",
                    "ip route get 8.8.8.8 dev ${c.first}",
                    "ip route show table ${c.first}"
                )) {
                    val r = runner(cmd)
                    if (r.startsWith("root 执行失败") || r.startsWith("命令退出码")) continue
                    gw = Regex("""via (\d{1,3}(?:\.\d{1,3}){3})""").find(r)?.groupValues?.get(1)
                    if (gw != null) break
                }
                gw
            }.getOrNull()
            c to gw
        }

        // 4. 优先级：目标所在子网的接口最优先（双网卡按目标走），其次默认路由接口，
        //    再按 NAME_PRIORITY 排序
        val chosen = withGateway
            .sortedWith(
                compareByDescending<Pair<Triple<String, String, Int>, String?>> { (c, _) ->
                    targetIp != null && IpUtil.inSubnet(targetIp, subnetOf(c.second, c.third))
                }
                    .thenByDescending { (c, _) -> c.first == defaultDev }
                    .thenBy { (c, _) -> NAME_PRIORITY.indexOfFirst { c.first.startsWith(it) }.let { if (it < 0) Int.MAX_VALUE else it } }
                    .thenBy { (c, _) -> c.first }
            )
            .firstOrNull() ?: return null
        val cand = chosen.first
        val gw = chosen.second

        // 5. 子网：ip & 前缀掩码
        return LanIface(cand.first, cand.second, subnetOf(cand.second, cand.third), gw)
    }

    /** 解析 `ip -o addr show`（每行：`N: iface    inet x.x.x.x/N brd ... scope global iface`） */
    private fun parseOneLine(out: String): List<Triple<String, String, Int>> {
        val result = mutableListOf<Triple<String, String, Int>>()
        val re = Regex("""^\d+:\s*(\S+?):?\s+inet\s+(\d{1,3}(?:\.\d{1,3}){3})/(\d{1,2})""")
        out.lineSequence().forEach { line ->
            val m = re.find(line) ?: return@forEach
            val name = m.groupValues[1].trimEnd(':')
            val ip = m.groupValues[2]
            val prefix = m.groupValues[3].toIntOrNull() ?: return@forEach
            if (!isRealIface(name) || !isGlobalIp(ip)) return@forEach
            result.add(Triple(name, ip, prefix))
        }
        return result
    }

    /** 解析 `ip addr show`（接口头行 + 缩进 inet 行） */
    private fun parseIndented(out: String): List<Triple<String, String, Int>> {
        val result = mutableListOf<Triple<String, String, Int>>()
        var curIface: String? = null
        out.lineSequence().forEach { line ->
            val head = Regex("""^\d+:\s*(\S+?):\s*<""").find(line)
            if (head != null) {
                curIface = head.groupValues[1]
            } else if (curIface != null && line.trimStart().startsWith("inet ")) {
                val m = Regex("""inet\s+(\d{1,3}(?:\.\d{1,3}){3})/(\d{1,2})""").find(line)
                if (m != null) {
                    val ip = m.groupValues[1]
                    val prefix = m.groupValues[2].toIntOrNull() ?: 24
                    if (isRealIface(curIface!!) && isGlobalIp(ip)) {
                        result.add(Triple(curIface!!, ip, prefix))
                    }
                }
            }
        }
        return result
    }

    /** 是否真实接口（排除虚拟/移动接口） */
    private fun isRealIface(name: String): Boolean =
        EXCLUDE_PREFIXES.none { name.startsWith(it) } && name.length >= 2

    /** 是否全局 IPv4（排除回环/链路本地等） */
    private fun isGlobalIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val n = parts.mapNotNull { it.toIntOrNull() }
        if (n.size != 4 || n.any { it !in 0..255 }) return false
        return !(n[0] == 0 || n[0] == 127 || (n[0] == 169 && n[1] == 254))
    }

    /** 由 IP 与前缀计算子网号（如 192.168.1.5/24 → 192.168.1.0/24） */
    private fun subnetOf(ip: String, prefix: Int): String {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return "$ip/24"
        val p = prefix.coerceIn(0, 32)
        val mask = if (p == 0) 0 else (0xFFFFFFFFL.toInt() shl (32 - p))
        val net = ((parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]) and mask
        return "${(net ushr 24) and 0xFF}.${(net ushr 16) and 0xFF}.${(net ushr 8) and 0xFF}.${net and 0xFF}/$p"
    }
}
