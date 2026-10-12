package com.installer.toolbox.core.network.scan

import android.content.Context
import android.net.wifi.WifiManager
import com.installer.toolbox.core.network.util.ArpTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ARP 欺骗检测器（需 root + tcpdump）。
 *
 * 原理：ARP 欺骗 = 攻击者向局域网发送伪造的 ARP 应答，把某个 IP（通常是网关）
 * 的 MAC 篡改成自己的网卡地址。检测方法：
 * 1. root 下用 `timeout N tcpdump -l -n -i any arp` 抓一段时间的 ARP 包，
 *    内置 tcpdump 优先（[BuiltinTools] 解压 + root 复制到 /data/local/tmp），
 *    不可用时走系统 PATH 的 tcpdump；
 *    统计 源IP → 源MAC 映射，同一 IP 出现 ≥2 个不同 MAC 即判定为冲突；
 * 2. 通过路由表拿网关 IP，再查 /proc/net/arp 得到网关真实 MAC 供人工核对；
 * 3. 无 root / 无 tcpdump 时降级：两次读取 /proc/net/arp（间隔 3 秒），
 *    同一 IP 的 MAC 发生变化则报"疑似 ARP 波动"（精度较低）。
 *
 * root 命令执行通道通过 [rootRun] 注入（如 core-agent 的 RootShell::run），
 * 本模块不直接依赖 core-agent，避免 core-network ← core-agent 反向依赖。
 */
object ArpSpoofDetector {

    /** IP-MAC 冲突：同一 IP 被抓包到 ≥2 个不同 MAC */
    data class ArpConflict(val ip: String, val macs: List<String>)

    /** 检测结果 */
    data class ArpDetectResult(
        val conflicts: List<ArpConflict>,
        val gatewayMac: String?,
        val note: String?
    )

    /** 请求包行首：`源MAC > 目标MAC`（tcpdump -i any 带链路层头） */
    private val REQ_RE = Regex(
        """([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\s*>\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})"""
    )

    /** 请求包中的发送者 IP：`ARP, Request who-has 1.2.3.4 tell 1.2.3.5` */
    private val TELL_RE = Regex("""tell\s+(\d{1,3}(?:\.\d{1,3}){3})""")

    /** 应答包：`ARP, Reply 1.2.3.4 is-at aa:bb:cc:dd:ee:ff` */
    private val REPLY_RE = Regex(
        """ARP, Reply\s+(\d{1,3}(?:\.\d{1,3}){3})\s+is-at\s+([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})"""
    )

    /** 默认路由：`default via 192.168.1.1 dev wlan0` */
    private val DEFAULT_VIA_RE = Regex("""default via (\d{1,3}(?:\.\d{1,3}){3})""")

    /**
     * 执行 ARP 欺骗检测（运行在 IO 线程）。
     *
     * @param context 用于读取 Wi-Fi DhcpInfo 拿网关 IP
     * @param durationMs 抓包时长（root 方案；降级方案固定两次采样、间隔 3 秒）
     * @param rootRun root 命令执行通道（如 RootShell::run）；null 或非 root 时走降级方案
     */
    suspend fun detect(
        context: Context,
        durationMs: Long = 8000,
        rootRun: ((String) -> String)? = null
    ): ArpDetectResult = withContext(Dispatchers.IO) {
        val gwIp = gatewayIp(context)
        val runner = rootRun
        if (runner != null && runner("id").contains("uid=0")) {
            // RootShell.run 没有超时参数：tcpdump 持续输出会一直阻塞，
            // 因此用 `timeout N tcpdump ...` 让抓包命令自动退出（busybox/coreutils timeout）。
            // -e 让 tcpdump 打印链路层头（源/目标 MAC），解析依赖 MAC > MAC 行。
            // 顺带用输出判断 tcpdump 是否可用。
            val captureSec = ((durationMs / 1000) + 1).coerceAtLeast(2L)
            // tcpdump：内置优先（assets 解压 + root 复制到 /data/local/tmp），
            // 内置不可用时返回 "tcpdump" 走系统 PATH（root 下部分手机自带）。
            val tcpdumpBin = BuiltinTools.tcpdump(context, runner) ?: "tcpdump"
            // || true：抓不到 ARP 包被 timeout 杀时退出码 124 无输出，避免"命令退出码"提示误判 tcpdump 不可用
            val out = runner("timeout $captureSec $tcpdumpBin -l -n -e -i any arp || true")
            val unavailable = out.contains("not found") || out.contains("No such file") ||
                out.contains("Permission denied") || out.startsWith("root 执行失败") ||
                out.startsWith("命令退出码")
            if (!unavailable) {
                val byIp = parseTcpdump(out)
                val conflicts = byIp
                    .filter { it.value.size >= 2 }
                    .map { (ip, macs) -> ArpConflict(ip, macs) }
                    .sortedBy { it.ip }
                val gwMac = gatewayMacOf(runner, gwIp)
                val note = buildString {
                    append("root 抓包 ${captureSec}s 统计 IP→MAC：同一 IP 出现 ≥2 个不同 MAC 判定为冲突；")
                    append("网关 MAC 突变是典型 ARP 欺骗特征，请与网关设备核对。")
                    append("限制：仅覆盖抓包窗口内的 ARP 流量，静默（不发包）的欺骗无法发现。")
                }
                return@withContext ArpDetectResult(conflicts, gwMac, note)
            }
        }
        // 无 root / 无 tcpdump / root 通道不可用 → 降级
        degradeByArpTable(gwIp)
    }

    // ---- 主方案：tcpdump 抓包解析 ----

    /**
     * 从 tcpdump 输出提取 (源IP, 源MAC) 列表并按 IP 聚合。
     * 兼容两种行格式：
     * - 请求：`aa:bb:... > cc:dd:..., ARP, Request who-has X tell 192.168.1.5`
     * - 应答：`ARP, Reply 192.168.1.5 is-at aa:bb:...`
     */
    private fun parseTcpdump(out: String): Map<String, List<String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        out.lineSequence().forEach { line ->
            if (!line.contains("ARP")) return@forEach
            val reply = REPLY_RE.find(line)
            if (reply != null) {
                pairs.add(reply.groupValues[1] to reply.groupValues[2].lowercase())
                return@forEach
            }
            val srcMac = REQ_RE.find(line)?.groupValues?.get(1)?.lowercase() ?: return@forEach
            val srcIp = TELL_RE.find(line)?.groupValues?.get(1) ?: return@forEach
            pairs.add(srcIp to srcMac)
        }
        return pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
    }

    /** 网关 MAC：路由表拿网关 IP（必要时用 DhcpInfo 兜底），再查 /proc/net/arp */
    private fun gatewayMacOf(runner: (String) -> String, gatewayIp: String?): String? {
        var gw = gatewayIp
        if (gw == null) {
            val route = runner("ip route | grep default")
            gw = DEFAULT_VIA_RE.find(route)?.groupValues?.get(1)
        }
        if (gw == null) return null
        return macOfIp(runner("cat /proc/net/arp"), gw)
    }

    // ---- 降级方案：/proc/net/arp 两次采样对比 ----

    /** 无 root / 无 tcpdump 时：两次读取 ARP 表（间隔 3 秒），对比同一 IP 的 MAC 是否变化 */
    private suspend fun degradeByArpTable(gatewayIp: String?): ArpDetectResult {
        val first = readArpTable()
        delay(3000)
        val second = readArpTable()
        if (first == null || second == null) {
            return ArpDetectResult(
                conflicts = emptyList(),
                gatewayMac = first?.get(gatewayIp) ?: second?.get(gatewayIp),
                note = "需要 root 才能抓 ARP 包，降级为 ARP 表对比；且无法读取 /proc/net/arp（被系统限制），检测不可用。"
            )
        }
        val conflicts = mutableListOf<ArpConflict>()
        (first.keys + second.keys).distinct().forEach { ip ->
            val mac1 = first[ip]
            val mac2 = second[ip]
            if (mac1 != null && mac2 != null && mac1 != mac2) {
                conflicts.add(ArpConflict(ip, listOf(mac1, mac2)))
            }
        }
        val gwMac = second[gatewayIp] ?: first[gatewayIp]
        val note = buildString {
            append("需要 root 才能抓 ARP 包，降级为 ARP 表对比（间隔 3 秒两次采样，精度较低）：")
            if (conflicts.isEmpty()) append("本次未发现 IP 的 MAC 变化。")
            else append("检测到 ${conflicts.size} 个 IP 的 MAC 发生变化，疑似 ARP 波动！")
        }
        return ArpDetectResult(conflicts.sortedBy { it.ip }, gwMac, note)
    }

    /** 读取 /proc/net/arp → IP → MAC 映射；统一走 [ArpTable] 放宽规则，读取失败返回 null */
    private fun readArpTable(): Map<String, String>? = try {
        ArpTable.parse(File("/proc/net/arp").readText())
    } catch (e: Exception) {
        null
    }

    /** 从 /proc/net/arp 文本中查指定 IP 的 MAC（统一走 [ArpTable] 放宽规则） */
    private fun macOfIp(arpTable: String, ip: String): String? = ArpTable.macOf(arpTable, ip)

    // ---- 辅助 ----

    /** 通过 Wi-Fi DhcpInfo 拿网关 IP（无 Wi-Fi 时返回 null） */
    private fun gatewayIp(context: Context): String? = try {
        val dhcp = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo
        if (dhcp == null || dhcp.gateway == 0) null else intToIp(dhcp.gateway)
    } catch (e: Exception) {
        null
    }

    /** DhcpInfo 的小端 int IP 转点分十进制 */
    private fun intToIp(v: Int): String {
        val n = Integer.reverseBytes(v)
        return "${(n ushr 24) and 0xFF}.${(n ushr 16) and 0xFF}.${(n ushr 8) and 0xFF}.${n and 0xFF}"
    }
}
