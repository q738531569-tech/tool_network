package com.installer.toolbox.core.network.scan

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ARP/IPv6 中间人流量审计（高级版，需 root + 设备所有者授权）。
 *
 * 原理：用 arpspoof 向目标设备（与网关）双向发送伪造 ARP 应答，网络有 IPv6 时叠加
 * ndpspoof NDP 双向欺骗（[NdpSpoof]，与静默同款），把目标设备的 IPv4+IPv6 网关流量引导
 * 到本机（IPv4 转发开启后继续转发到真实网关），期间用 tcpdump 统计目标流量的上下行，
 * 按**综合评分**启发式判定是否疑似 PCDN 上行占用（多特征同时命中才判，防刷视频误判）。
 *
 * 内置工具优先：arpspoof / ndpspoof / tcpdump 均为 APK 内置（[BuiltinTools] 解压 +
 * root 复制到 /data/local/tmp），无需 Termux。
 *
 * 安全/合规要点：
 * - 这是对局域网其他设备的主动中间人操作，【必须】先取得设备/网络所有者授权，
 *   仅用于装维排查；返回的 note 强制携带授权提示。
 * - root 命令执行通道通过 [rootRun] 注入（如 core-agent 的 RootShell::run），
 *   本模块不直接依赖 core-agent，避免 core-network ← core-agent 反向依赖。
 * - 审计结束后自动清理 arpspoof/ndpspoof 并恢复 net.ipv4.ip_forward=0。
 *
 * 多网络适配：网关/接口解析走 [LanInterfaceResolver]（局域网接口优先）——PDA 有线底座与
 * "局域网无外网、默认路由在移动数据"场景都能正确审计局域网，不误操作移动网卡。
 */
object TrafficAudit {

    /** 上下行流量统计（包数与字节数，upRatio 为上行包占比） */
    data class TrafficStats(
        val sentPackets: Long,
        val sentBytes: Long,
        val recvPackets: Long,
        val recvBytes: Long,
        /** 上行包占比 0.0-1.0：sentPackets / (sentPackets + recvPackets) */
        val upRatio: Double,
        /** TCP 包数（目标相关） */
        val tcpPackets: Long = 0,
        /** UDP 包数（目标相关） */
        val udpPackets: Long = 0,
        /** DNS 请求数（目标发出的 53 端口查询） */
        val dnsRequests: Long = 0,
        /** 并发连接数（不同 4 元组数量，抓包窗口内） */
        val connections: Long = 0,
        /** NAT 打洞/STUN 类流量包数（目的端口 3478/3479/19302/5349） */
        val stunPackets: Long = 0,
        /** UPnP SSDP 包数（UDP 1900 / 组播 239.255.255.250） */
        val ssdpPackets: Long = 0,
        /** UPnP IGD 控制端口流量包数 */
        val upnpControlPackets: Long = 0,
        /** IPv6 目标相关包数（NDP 欺骗借道抓到的纯 IPv6 流量） */
        val ipv6Packets: Long = 0,
        /** IPv6 目标相关字节数 */
        val ipv6Bytes: Long = 0
    )

    /** 审计结果 */
    data class AuditResult(
        val targetIp: String,
        val gatewayIp: String?,
        val stats: TrafficStats?,
        /** 是否疑似 PCDN 上行占用（综合评分启发式） */
        val pcdnLikely: Boolean,
        val note: String?
    )

    /** Termux 中 MITM 工具目录（内置 arpspoof 不可用时的降级候选） */
    private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"

    /** ARP 中间人工具降级候选：arpspoof → ettercap → bettercap → nmap arp-spoof，按存在性选择 */
    private val TERMUX_MITM_CANDIDATES = listOf(
        MITMTool("arpspoof", "$TERMUX_BIN/arpspoof", "-i {iface} -t {target} {gateway}"),
        MITMTool("ettercap", "$TERMUX_BIN/ettercap", "-T -q -M arp:remote -i {iface} /{target}// /{gateway}//"),
        MITMTool("bettercap", "$TERMUX_BIN/bettercap", "-iface {iface} -eval \"set arp.spoof.targets {target}; arp.spoof on; net.sniff on\""),
        MITMTool("nmap", "$TERMUX_BIN/nmap", "--script arp-spoof -e {iface} {target}")
    )

    /** MITM 工具描述 */
    private data class MITMTool(val name: String, val path: String, val argsTemplate: String)

    /** 授权提示：强制拼入返回的 note */
    const val AUTH_NOTE = "流量审计使用 ARP/IPv6 中间人技术，请确认已取得设备/网络所有者授权；仅用于装维排查"

    /** 路由表 via 行（回退旧逻辑用）：`8.8.8.8 via 192.168.10.1 dev wlan0 ...` */
    private val DEFAULT_VIA_RE = Regex("""via (\d{1,3}(?:\.\d{1,3}){3}) dev (\S+)""")

    /** RootShell 失败前缀：`命令退出码 N（可能未在 Magisk 中授权本应用）`；124 为 timeout 正常到点 */
    private val EXIT_RE = Regex("""命令退出码 (\d+)""")

    /** tcpdump 执行是否真实失败（timeout 到点 124 属正常结束，不算失败） */
    private fun isTcpdumpUnavailable(out: String): Boolean {
        val exitCode = EXIT_RE.find(out)?.groupValues?.get(1)?.toIntOrNull()
        return out.contains("not found") || out.contains("No such file") ||
            out.contains("Permission denied") || out.startsWith("root 执行失败") ||
            (exitCode != null && exitCode != 124)
    }

    /** tcpdump 带端口行：`IP 192.168.1.5.443 > 192.168.1.1.80: Flags [P.], seq ..., length 100` */
    private val IP_PORT_RE = Regex(
        """IP (\d{1,3}(?:\.\d{1,3}){3})\.(\d+) > (\d{1,3}(?:\.\d{1,3}){3})\.(\d+):"""
    )

    /** tcpdump 无端口行（ICMP 等）：`IP 192.168.1.5 > 192.168.1.1: ICMP echo request` */
    private val IP_ONLY_RE = Regex(
        """IP (\d{1,3}(?:\.\d{1,3}){3}) > (\d{1,3}(?:\.\d{1,3}){3}):"""
    )

    /** tcpdump IPv6 行：`IP6 fe80::1.5353 > ff02::fb.5353: UDP, length 100`（端口可选） */
    private val IP6_RE = Regex(
        """IP6 ([0-9a-fA-F:]+)(?:\.(\d+))? > ([0-9a-fA-F:]+)(?:\.(\d+))?:"""
    )

    /** IPv4 行末长度字段：`length 100` */
    private val LENGTH_RE = Regex("""length (\d+)""")

    /**
     * 对目标设备执行 ARP+IPv6 中间人流量审计（运行在 IO 线程）。
     *
     * @param context 用于解压内置 arpspoof / ndpspoof / tcpdump（assets → filesDir）
     * @param targetIp 目标设备 IP，必填
     * @param durationMs 抓包时长（毫秒），默认 15 秒
     * @param rootRun root 命令执行通道（如 RootShell::run）；null 或非 root 时返回降级提示
     */
    suspend fun audit(
        context: Context,
        targetIp: String,
        durationMs: Long = 15000,
        rootRun: ((String) -> String)? = null
    ): AuditResult = withContext(Dispatchers.IO) {
        if (targetIp.isBlank()) {
            return@withContext AuditResult(targetIp, null, null, false, "参数错误：缺少目标 IP")
        }
        val runner = rootRun
        // 1. root 检查（不依赖 core-agent，直接用 id 判断）
        if (runner == null || !runner("id").contains("uid=0")) {
            return@withContext AuditResult(
                targetIp, null, null, false,
                "$AUTH_NOTE；需要 root 才能做流量审计（内置 arpspoof/ndpspoof/tcpdump，无需 Termux）"
            )
        }
        // 2. 局域网接口优先解析网关/接口（多网络适配：局域网无外网时默认路由在移动数据，
        //    不能盲从 ip route get；解析不到局域网接口才回退旧逻辑）
        val lan = try { LanInterfaceResolver.resolve(runner, targetIp) } catch (e: Exception) { null }
        val gatewayIp: String
        val iface: String
        if (lan != null && lan.gateway != null) {
            gatewayIp = lan.gateway
            iface = lan.name
        } else {
            val route = runner("ip route get 8.8.8.8")
            if (route.startsWith("root 执行失败") || route.startsWith("命令退出码")) {
                return@withContext AuditResult(
                    targetIp, null, null, false,
                    "$AUTH_NOTE；读取路由表失败：${route.take(200)}"
                )
            }
            val via = DEFAULT_VIA_RE.find(route)
            if (via == null) {
                return@withContext AuditResult(
                    targetIp, null, null, false,
                    "$AUTH_NOTE；未从路由表解析到网关（ip route get 未返回 via 行），无法定位网关"
                )
            }
            gatewayIp = via.groupValues[1]
            iface = via.groupValues[2]
        }

        // 3. 开启 IP 转发 + 关闭反向路径过滤（审计结束恢复）
        //    关键：Android 内核默认 rp_filter=1，会丢弃"源 IP 非本机"的引流包——
        //    只开 ip_forward 不关 rp_filter，目标流量引到手机后转发不出去，目标会断网！
        runner("sysctl -w net.ipv4.ip_forward=1")
        runner("sysctl -w net.ipv4.conf.all.rp_filter=0")
        runner("sysctl -w net.ipv4.conf.default.rp_filter=0")
        runner("sysctl -w net.ipv4.conf.$iface.rp_filter=0")
        // IPv6 转发：NDP 双向欺骗把 v6 流量引到手机后必须转发出去，否则目标 IPv6 断网
        runner("sysctl -w net.ipv6.conf.all.forwarding=1")
        runner("sysctl -w net.ipv6.conf.default.forwarding=1")
        runner("sysctl -w net.ipv6.conf.$iface.forwarding=1")
        // 验证转发确实开启（部分 ROM SELinux 限制 sysctl 写失败，静默失败会害目标断网还不自知）
        val fwdOk = runner("cat /proc/sys/net/ipv4/ip_forward").trim() == "1"
        if (!fwdOk) {
            return@withContext AuditResult(
                targetIp, gatewayIp, null, false,
                "$AUTH_NOTE；IP 转发开启失败（内核拒绝 sysctl，目标流量转发不出去会断网，已中止审计）。"
            )
        }
        // 网关 IPv6（转发路由用；纯 IPv4 网络为 null）；目标 IPv6 集合（iptables 放行用）
        var gwV6: String? = null
        var targetV6s: Set<String> = emptySet()
        // 防休眠锁：审计期间持有 PARTIAL_WAKE_LOCK（灭屏/Doze 会暂停 CPU 与网络，
        // 导致 tcpdump 抓包中断、ARP 欺骗停发；60s 长时审计尤其需要）。
        // acquire 带超时（时长+10s）自动释放兜底，主流程 finally 成对释放。
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toolbox:traffic_audit")
        try {
            wakeLock?.acquire(durationMs + 10_000L)
        } catch (_: Exception) {
        }
        try {
            // 3.5 转发路由（**目标断网根因修复**）：Android 策略路由下，转发的包
            //    （fwmark=0、入接口 wlan0）不匹配 "iif lo lookup wlan0" 规则、主表也无默认路由，
            //    最终命中 32000 unreachable 被丢弃 → 目标断网。
            //    local_network 表必须同时有：
            //    ①**局域网直连路由**（回包直发目标——缺它回包会命中 default 被送回网关形成环路，
            //      目标收不到回包=断网）；②默认路由（转发目标外网流量）。
            //    双保险：显式加 "iif 局域网口 → 查 local_network 表" 的 ip rule（pref 15000），
            //    魔改 ROM 规则顺序不同也能命中。审计结束全部删除。v4/v6 都补。
            val lanSubnet = lan?.subnet ?: resolveSubnet(runner, iface)
            if (lanSubnet != null) {
                runner("ip route add $lanSubnet dev $iface table local_network 2>/dev/null || true")
            }
            runner("ip route add default via $gatewayIp dev $iface table local_network 2>/dev/null || true")
            runner("ip rule add iif $iface lookup local_network pref 15000 2>/dev/null || true")
            gwV6 = resolveGatewayV6(runner)
            if (gwV6 != null) {
                val v6Prefix = resolveV6Prefix(runner, iface)
                if (v6Prefix != null) {
                    runner("ip -6 route add $v6Prefix dev $iface table local_network 2>/dev/null || true")
                }
                runner("ip -6 route add default via $gwV6 dev $iface table local_network 2>/dev/null || true")
                runner("ip -6 rule add iif $iface lookup local_network pref 15000 2>/dev/null || true")
            }
            // 3.6 放行转发（**目标断网第二根因，PDA 实测确认**）：Android netd 的
            //     tetherctrl_FORWARD 链默认 "-j DROP" 丢弃一切转发（只有手机开热点时 netd 才放行）——
            //     只配路由不配 iptables，转发的包全死在这。审计期间精确放行目标 IP，结束删除。
            runner("iptables -I tetherctrl_FORWARD -s $targetIp -j ACCEPT 2>/dev/null || true")
            runner("iptables -I tetherctrl_FORWARD -d $targetIp -j ACCEPT 2>/dev/null || true")
            // 4. 选择 MITM 工具：内置 arpspoof 优先，否则 Termux 候选
            val builtinArpspoof = BuiltinTools.arpspoof(context, runner)
            val mitm: MITMTool? = builtinArpspoof?.let {
                MITMTool("内置 arpspoof", it, "{iface} {target} {gateway}")
            } ?: TERMUX_MITM_CANDIDATES.firstOrNull {
                runner("sh -c 'test -x ${it.path} && echo MITM_OK'").contains("MITM_OK")
            }
            if (mitm == null) {
                return@withContext AuditResult(
                    targetIp, gatewayIp, null, false,
                    "$AUTH_NOTE；内置 arpspoof 初始化失败且未检测到 Termux 的 arpspoof/ettercap/bettercap，无法进行 ARP 中间人审计"
                )
            }
            // 5. 后台启动 MITM **双向** ARP 欺骗：
            //    正向（骗目标"网关 MAC 是本机"）：目标上行流量引到手机；
            //    反向（骗网关"目标 MAC 是本机"）：网关回包也引到手机——否则下行直连目标抓不到，
            //    且上行占比计算偏高（误判源）。两条进程独立持续发包。
            val mitmArgs = mitm.argsTemplate
                .replace("{iface}", iface)
                .replace("{target}", targetIp)
                .replace("{gateway}", gatewayIp)
            runner(
                "sh -c '${mitm.path} $mitmArgs >/dev/null 2>&1 & echo \$!'"
            )
            // 反向欺骗：victim=网关，宣称"target 的 MAC 是本机"
            runner(
                "sh -c '${mitm.path} $iface $gatewayIp $targetIp >/dev/null 2>&1 & echo \$!'"
            )
            // 5.5 IPv6 NDP 双向欺骗（借道审计覆盖纯 IPv6 流量；纯 IPv4 网络自动跳过）
            //     进程 PID 不单独跟踪，审计结束由 stopAudit 的 pkill 兜底清理
            val ndpspoofBin = BuiltinTools.ndpspoof(context, runner)
            if (ndpspoofBin != null) {
                try {
                    NdpSpoof.spoofTarget(runner, ndpspoofBin, iface, gatewayIp, targetIp, 500, mutableListOf())
                } catch (e: Exception) {
                    // IPv6 欺骗启动失败不影响 IPv4 审计
                }
            }
            // 目标 MAC 与 IPv6 地址集合（IPv6 流量归属判定 + ip6tables 放行用）
            val targetMac = try { NdpSpoof.victimMac(runner, targetIp) } catch (e: Exception) { null }
            targetV6s = if (targetMac != null) {
                try { NdpSpoof.victimV6Addresses(runner, iface, targetMac).toSet() } catch (e: Exception) { emptySet() }
            } else emptySet()
            // IPv6 转发放行（与 v4 同根因：tetherctrl_FORWARD 默认 DROP）
            targetV6s.forEach { v6 ->
                runner("ip6tables -I tetherctrl_FORWARD -s $v6 -j ACCEPT 2>/dev/null || true")
                runner("ip6tables -I tetherctrl_FORWARD -d $v6 -j ACCEPT 2>/dev/null || true")
            }
            // 6. tcpdump 统计目标流量（-i any "host X" 同时匹配 IPv4/IPv6；不重定向 stderr）
            val tcpdumpBin = BuiltinTools.tcpdump(context, runner) ?: "tcpdump"
            val captureSec = (durationMs / 1000 + 1).coerceAtLeast(2L)
            // 6.0 DNS 域名特征并行抓包（后台进程，与主抓包同一时间窗）：PCDN 盒子周期性
            //     解析平台调度域名（xunlei/onething/walkcloud 等），域名自报家门；
            //     藏在二级路由 NAT 后借道审计同样能抓到。输出到临时文件，
            //     主抓包结束后统一读取，不额外拉长审计总时长。
            val dnsSec = (captureSec - 1).coerceAtLeast(3)
            runner(
                "sh -c 'nohup timeout $dnsSec $tcpdumpBin -nn -l -i any " +
                    "\"udp port 53 and host $targetIp\" > /data/local/tmp/toolbox_dns.txt 2>/dev/null &'"
            )
            // timeout 到点杀 tcpdump 返回 124：目标无流量时输出为空 → RootShell 报"命令退出码 124"
            // 污染结果；|| true 兜底（正常退出 0 不受影响，tcpdump 自身报错仍有 stderr 输出供诊断）
            var out = runner("timeout $captureSec $tcpdumpBin -l -n -i any \"host $targetIp\" || true")
            var unavailable = isTcpdumpUnavailable(out)
            if (unavailable) {
                out = runner("timeout $captureSec $tcpdumpBin -l -n -i any \"host $targetIp\" || true")
                unavailable = isTcpdumpUnavailable(out)
            }
            if (unavailable) {
                // 7. 降级：cat /proc/net/arp 仅确认目标在网，无法统计流量
                val arp = runner("cat /proc/net/arp")
                val inArp = arp.lineSequence().any { line ->
                    line.trim().startsWith(targetIp) && line.contains("0x1")
                }
                return@withContext AuditResult(
                    targetIp, gatewayIp, null, false,
                    "$AUTH_NOTE；tcpdump 不可用（$tcpdumpBin）[out=${out.take(200)}]，已降级：目标${if (inArp) "在" else "不在"} ARP 表中，未统计流量。内置 tcpdump 初始化失败且系统无 tcpdump，请重装 APK 后重试"
                )
            }
            // 8. 解析统计 + IDC 目标检测 + 综合评分判定
            // DNS 文件不存在时 cat 报错（2>/dev/null 吞输出 → 无输出退出码 1，被 RootShell 转成
            // "命令退出码 1" 提示混进审计结果）；|| true 兜底：不存在就当无 DNS 记录
            val dnsPcdn = parseDnsPcdnDomains(runner("cat /data/local/tmp/toolbox_dns.txt 2>/dev/null || true"))
            runner("rm -f /data/local/tmp/toolbox_dns.txt")
            val stats = parseTcpdump(out, targetIp, targetV6s)
            // NAT 借道聚类：按源端口还原二级路由 NAT 后下挂设备（借道审计核心分析）
            val natClusters = parseNatPortClusters(out, targetIp)
            val externalIps = collectExternalDstIps(out, targetIp)
            val idcHits = if (externalIps.isEmpty()) emptyList() else IdcTargetDetector.classify(externalIps)
                .filter { it.isIdc }
            val protoTotal = stats.tcpPackets + stats.udpPackets
            val udpRatio = if (protoTotal > 0) stats.udpPackets.toDouble() / protoTotal else 0.0
            val uniqueDst = externalIps.size
            // 样本量门槛：阈值与审计时长成正比（15s 为基准等比缩放）
            val factor = captureSec / 15.0
            val totalPackets = stats.sentPackets + stats.recvPackets
            val minPackets = (20 * factor).toLong()
            val minBytes = (1024L * factor).toLong()
            val enoughTraffic = totalPackets >= minPackets &&
                (stats.sentBytes + stats.recvBytes) >= minBytes

            // ---- 综合评分判定（检出从严：漏报优先于误报，宁错杀不放过）----
            // 总分 ≥ 4 且至少 1 个特征命中即判疑似；结果仅供参考需人工复核——
            // 刷抖音等正常高流量用户可能被标疑似，但装维可结合设备类型/网页确认排除
            var score = 0
            var dims = 0
            fun addDim(s: Int) {
                score += s
                dims++
            }
            // 上行占比>70% 且上行≥10KB：+3（PCDN 典型）
            if (stats.upRatio > 0.7 && stats.sentBytes >= (10L * 1024L * factor)) addDim(3)
            // 上行字节≥1MB 且 >3× 下行：+4（疯狂上行强特征）
            if (stats.sentBytes >= (1024L * 1024L * factor) && stats.sentBytes > stats.recvBytes * 3L) addDim(4)
            // 非 DNS UDP 占比>85%：+1（QUIC 视频类 App 常 50-80%，85% 以上更接近纯 UDP 上传；单维度不触发）
            if (udpRatio > 0.85) addDim(1)
            // 上行分布式目的 IP≥15：+2
            if (uniqueDst >= (15 * factor).toInt()) addDim(2)
            // 上行 IDC 目标≥3：+3
            if (idcHits.size >= (3 * factor).toInt()) addDim(3)
            // 并发连接≥500：+1（PCDN 分发节点连接数远大于刷视频——窗口内 PCDN 可达数百上千，
            // 抖音 15s 窗口也能攒到 200+，故阈值从 200 提到 500 保留区分度；单维度不触发）
            if (stats.connections >= (500 * factor).toLong()) addDim(1)
            // STUN≥10：+2
            if (stats.stunPackets >= (10 * factor).toLong()) addDim(2)
            // UPnP 映射≥2：+3
            if (stats.upnpControlPackets >= (2 * factor).toLong()) addDim(3)
            // DNS 域名含 PCDN 平台关键词：+3（域名自报家门，最硬的特征之一）
            if (dnsPcdn.isNotEmpty()) addDim(3)
            // NAT 借道还原：单一源端口（映射的下挂设备）上行 ≥1MB 且外部 IP ≥15：+2
            val natHeavy = natClusters.firstOrNull {
                it.sentBytes >= (1024L * 1024L * factor) && it.dstIps.size >= (15 * factor).toInt()
            }
            if (natHeavy != null) addDim(2)

            // 硬特征：命中任一即视为有实锤（弱维度凑分不算）
            val hardHit = (stats.upRatio > 0.7 && stats.sentBytes >= (10L * 1024L * factor)) ||
                (stats.sentBytes >= (1024L * 1024L * factor) && stats.sentBytes > stats.recvBytes * 3L) ||
                stats.upnpControlPackets >= (2 * factor).toLong() ||
                dnsPcdn.isNotEmpty() ||
                natHeavy != null
            // 疑似判定：总分达标 + 至少 1 维 + **上行量门槛**——
            // 抖音/微信自带 P2P 上传（STUN/UDP/多 IP 弱维度凑分）上行很小，不误判；
            // 真 PCDN 盒子持续大上传，必过门槛。
            val pcdnLikely = enoughTraffic && score >= 4 && dims >= 1 &&
                (stats.sentBytes >= (256L * 1024L * factor) || hardHit)
            val quiet = totalPackets == 0L && stats.ipv6Packets == 0L
            val note = buildString {
                append("$AUTH_NOTE；")
                append("统计 ${captureSec}s 内目标流量，综合评分判定：总分≥4 且至少 1 个特征命中、且上行量达标（≥256KB 或命中硬特征）才判疑似（检出从严：漏报优先于误报，结果仅供参考需人工复核）。特征：上行占比>70% 且上行≥10KB(+3)、上行≥1MB 且>3×下行(+4)、非DNS UDP 占比>85%(+1)、上行分布式目的 IP≥15(+2)、上行 IDC≥3(+3)、并发连接≥500(+1)、STUN≥10(+2)、UPnP≥2(+3)、DNS 域名含 PCDN 平台关键词(+3)、NAT 借道还原(+2)，阈值随时长等比放大（15s 基准）。")
                append("本次得分 $score（命中 $dims 维）。UDP 占比 ${(udpRatio * 100).toInt()}%、连接 ${stats.connections} 个、外部 IP ${uniqueDst} 个、DNS 请求 ${stats.dnsRequests} 次、STUN 包 ${stats.stunPackets}、SSDP 包 ${stats.ssdpPackets}、UPnP 映射请求 ${stats.upnpControlPackets}、IPv6 包 ${stats.ipv6Packets}；")
                append("本窗口上行占比 ${(stats.upRatio * 100).toInt()}%（上行 ${bytesText(stats.sentBytes)} / 下行 ${bytesText(stats.recvBytes)}）。")
                append("【运营商口径对照】电信啄木鸟等系统按账号单日聚合：PCDN流量占比 =（P2P上行 + PCDN伪装流量）÷ 总上行。本审计是 15-60 秒采样，短窗口占比噪声大，看三要素对应：上行占比 + IDC 目标 IP（$uniqueDst 个） + PCDN 平台域名命中${if (dnsPcdn.isEmpty()) "（本窗口无）" else "（${dnsPcdn.size} 个）"}。要与运营商口径对齐建议用 60 秒长时审计。")
                if (dnsPcdn.isNotEmpty()) {
                    append("【DNS 域名】目标解析过 PCDN 平台域名：${dnsPcdn.take(3).joinToString("、")}…… 域名自报家门为强特征。")
                }
                if (natClusters.isNotEmpty()) {
                    append("【NAT 借道还原】目标疑似二级路由器，WAN 侧流量按源端口还原出 ${natClusters.size} 个活跃连接簇（可能对应下挂设备）：")
                    append(natClusters.joinToString("；") { "端口 ${it.srcPort} 上行 ${bytesText(it.sentBytes)}、目的 IP ${it.dstIps.size} 个" })
                    append("——PCDN 盒子在 NAT 后也照常上传：某簇上行大且目的 IP 分散/含 IDC 即为下挂 PCDN 特征。")
                }
                if (idcHits.isNotEmpty()) {
                    append("【IDC 目标检测】命中 IDC/数据中心 ${idcHits.size} 个：")
                    append(idcHits.take(5).joinToString("、") { "${it.ip}(${it.org ?: it.type})" })
                    append("…… 上行发往 IDC 是 PCDN 节点流量典型特征。")
                }
                if (quiet) {
                    append("抓包窗口内未捕获到目标流量：目标空闲/非本网段/ARP 欺骗未生效，或设备有 ARP 防护/静态绑定、网络开 AP 隔离。")
                    append("若目标是二级路由器（一 MAC 多 IP/NAT 设备），PCDN 可能在其内网侧，需登录该路由器查 DHCP 客户端表/流量，或把手机接入其子网再审计。")
                    append("建议：①登录路由器查看 ②特征端口扫描 ③关 AP 隔离重试。")
                } else if (!enoughTraffic) {
                    append("窗口内流量过少（$totalPackets 个包，共 ${bytesText(stats.sentBytes + stats.recvBytes)}），样本不足未做 PCDN 判定。")
                    append("可能原因：①目标空闲（PCDN 盒子不跑任务时流量很小）——把审计时长调到 30-60 秒重试，或趁目标跑任务时测；")
                    append("②目标是二级路由——同网段审计抓不到其 WAN 口流量属正常，请用『借道审计（二级路由）』模式；")
                    append("③抓包未生效（AP 隔离/目标有 ARP 防护）——用正在上网的手机当目标验证，若也抓不到需现场排查。")
                }
                if (stats.ipv6Packets > 0) {
                    append("目标存在 IPv6 流量（${stats.ipv6Packets} 包）：若其上行主要走 IPv6，现有特征判定仅覆盖 IPv4，建议在路由器查 IPv6 邻居表/流量进一步确认。")
                }
            }
            AuditResult(targetIp, gatewayIp, stats, pcdnLikely, note)
        } finally {
            // 9. 无论成功失败都清理：iptables 放行规则 → 转发路由与规则 → 杀 MITM 进程 → 恢复系统参数
            runner("iptables -D tetherctrl_FORWARD -s $targetIp -j ACCEPT 2>/dev/null || true")
            runner("iptables -D tetherctrl_FORWARD -d $targetIp -j ACCEPT 2>/dev/null || true")
            targetV6s.forEach { v6 ->
                runner("ip6tables -D tetherctrl_FORWARD -s $v6 -j ACCEPT 2>/dev/null || true")
                runner("ip6tables -D tetherctrl_FORWARD -d $v6 -j ACCEPT 2>/dev/null || true")
            }
            runner("ip rule del iif $iface lookup local_network pref 15000 2>/dev/null || true")
            runner("ip route del default via $gatewayIp dev $iface table local_network 2>/dev/null || true")
            gwV6?.let {
                runner("ip -6 rule del iif $iface lookup local_network pref 15000 2>/dev/null || true")
                runner("ip -6 route del default via $it dev $iface table local_network 2>/dev/null || true")
            }
            stopAudit(runner)
            // 释放防休眠锁（成对；超时兜底由 acquire 的 timeout 参数保证）
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 停止审计：杀掉 arpspoof / ndpspoof 等 MITM 进程并恢复 IP 转发。
     * 无 root 通道时静默返回。
     */
    fun stopAudit(rootRun: ((String) -> String)?) {
        val runner = rootRun ?: return
        // 清理全部候选 MITM 工具进程（内置 /data/local/tmp/toolbox_arpspoof 含 "arpspoof"，
        // pkill -f 可命中）+ 内置 ndpspoof + 恢复转发与反向路径过滤。
        // 【关键坑】pkill -f "arpspoof" 会匹配到 su 自身命令行（su -c 的命令文本里含 "arpspoof"），
        // 把自己的 shell 杀了 → 退出码 143；用 "[a]rpspoof" 正则写法：目标进程命中、
        // 自身命令行是字面 "[a]rpspoof" 不命中。|| true 兜底"无进程可杀"的退出码 1。
        listOf("arpspoof", "ettercap", "bettercap", "nmap").forEach {
            runner("pkill -f \"[${it[0]}]${it.substring(1)}\" || true")
        }
        runner("pkill -f \"[n]dpspoof\" || true")
        runner("sysctl -w net.ipv4.ip_forward=0")
        runner("sysctl -w net.ipv4.conf.all.rp_filter=1")
        runner("sysctl -w net.ipv4.conf.default.rp_filter=1")
        // 恢复 IPv6 转发（审计结束目标恢复正常直连网关）
        runner("sysctl -w net.ipv6.conf.all.forwarding=0")
        runner("sysctl -w net.ipv6.conf.default.forwarding=0")
    }

    // ---- tcpdump 解析（尽力而为） ----

    /**
     * 解析 tcpdump 输出：
     * 源 == target 的行计入上行（sent），目的 == target 的行计入下行（recv）；
     * IPv6 行按目标已知 v6 地址集合判定归属（计入 ipv6Packets/ipv6Bytes）。
     */
    private fun parseTcpdump(out: String, targetIp: String, targetV6s: Set<String>): TrafficStats {
        var sentPackets = 0L
        var sentBytes = 0L
        var recvPackets = 0L
        var recvBytes = 0L
        var tcpPackets = 0L
        var udpPackets = 0L
        var dnsRequests = 0L
        var stunPackets = 0L
        var ssdpPackets = 0L
        var upnpControlPackets = 0L
        var ipv6Packets = 0L
        var ipv6Bytes = 0L
        // 并发连接数：不同 (srcIp:srcPort->dstIp:dstPort) 四元组
        val connections = LinkedHashSet<String>()
        out.lineSequence().forEach { line ->
            if (!line.contains("IP")) return@forEach
            var src: String? = null
            var dst: String? = null
            var srcPort: String? = null
            var dstPort: String? = null
            val withPort = IP_PORT_RE.find(line)
            if (withPort != null) {
                src = withPort.groupValues[1]
                dst = withPort.groupValues[3]
                srcPort = withPort.groupValues[2]
                dstPort = withPort.groupValues[4]
            } else {
                val bare = IP_ONLY_RE.find(line)
                if (bare != null) {
                    src = bare.groupValues[1]
                    dst = bare.groupValues[2]
                } else {
                    // IPv6 行
                    val m6 = IP6_RE.find(line)
                    if (m6 != null) {
                        val src6 = m6.groupValues[1]
                        val dst6 = m6.groupValues[3]
                        val involvesV6 = (targetV6s.isNotEmpty() && (src6 in targetV6s || dst6 in targetV6s))
                        if (involvesV6) {
                            val length = LENGTH_RE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                            ipv6Packets++
                            ipv6Bytes += length
                        }
                        return@forEach
                    }
                }
            }
            if (src == null || dst == null) return@forEach
            val length = LENGTH_RE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val involvesTarget = src == targetIp || dst == targetIp
            when {
                src == targetIp -> {
                    sentPackets++
                    sentBytes += length
                }
                dst == targetIp -> {
                    recvPackets++
                    recvBytes += length
                }
            }
            // 协议 / DNS / STUN / 连接数统计（仅目标相关流量）
            if (involvesTarget) {
                // UDP 占比排除 DNS（53）流量，避免 DNS 查询多导致 UDP 占比虚高误判
                if (line.contains("UDP,") && srcPort != "53" && dstPort != "53") udpPackets++
                else if (line.contains("Flags")) tcpPackets++
                if (src == targetIp && dstPort == "53") dnsRequests++
                if (dstPort in STUN_PORTS) stunPackets++
                // UPnP SSDP：UDP 1900 或组播 239.255.255.250
                if (dstPort == "1900" || dst == "239.255.255.250") ssdpPackets++
                // UPnP IGD 控制端口（目标→网关的 SOAP/HTTP 映射请求）
                val dp = dstPort?.toIntOrNull()
                if (src == targetIp && dp != null && dp in UPnP_CONTROL_PORTS) upnpControlPackets++
                if (srcPort != null && dstPort != null) {
                    connections.add("$src:$srcPort->$dst:$dstPort")
                }
            }
        }
        val total = sentPackets + recvPackets
        val upRatio = if (total > 0) sentPackets.toDouble() / total else 0.0
        return TrafficStats(
            sentPackets, sentBytes, recvPackets, recvBytes, upRatio,
            tcpPackets, udpPackets, dnsRequests, connections.size.toLong(),
            stunPackets, ssdpPackets, upnpControlPackets, ipv6Packets, ipv6Bytes
        )
    }

    /** NAT 打洞 / STUN / TURN 常用端口（P2P 穿透特征） */
    private val STUN_PORTS = setOf("3478", "3479", "19302", "5349")    /** UPnP IGD 控制端口（SOAP AddPortMapping，常见 49152-49155 / 华硕 5000） */
    private val UPnP_CONTROL_PORTS = (49152..49155).toSet() + 5000

    /** NAT 借道聚类条目：源端口 → 上行字节/外部 IP 集合（还原 NAT 后下挂设备） */
    private class NatPortStat(val srcPort: Int) {
        var sentBytes = 0L
        val dstIps = LinkedHashSet<String>()
        val dstPorts = LinkedHashSet<String>()
    }

    /**
     * 借道审计核心分析：对目标（疑似二级路由）的 WAN 侧流量按"源端口"聚类。
     * NAT 路由器会把下挂设备的连接映射为不同源端口，按源端口分组即可还原
     * NAT 后有多少台设备在上传、各自上传多少、发往哪些外部 IP——
     * PCDN 盒子藏在 NAT 后也照常上传，其簇特征（大上行 + 目的 IP 分散/IDC）可见。
     */
    private fun parseNatPortClusters(out: String, targetIp: String, topN: Int = 5): List<NatPortStat> {
        val map = mutableMapOf<Int, NatPortStat>()
        out.lineSequence().forEach { line ->
            val m = IP_PORT_RE.find(line) ?: return@forEach
            val src = m.groupValues[1]
            if (src != targetIp) return@forEach // 只统计目标发出（上行）
            val srcPort = m.groupValues[2].toIntOrNull() ?: return@forEach
            val dst = m.groupValues[3]
            val dstPort = m.groupValues[4]
            val length = LENGTH_RE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val st = map.getOrPut(srcPort) { NatPortStat(srcPort) }
            st.sentBytes += length
            st.dstIps.add(dst)
            st.dstPorts.add(dstPort)
        }
        return map.values.sortedByDescending { it.sentBytes }.take(topN)
    }

    /** 字节数人性化显示（note 输出用，UI 有同款 formatBytes） */
    private fun bytesText(v: Long): String = when {
        v >= 1024L * 1024L -> String.format("%.1fMB", v / 1024.0 / 1024.0)
        v >= 1024L -> String.format("%.0fKB", v / 1024.0)
        else -> "${v}B"
    }

    /**
     * DNS 查询域名里的 PCDN 平台关键词（域名自报家门，小写匹配）。
     * 扩充原则：只加**平台专属词**（玩客云/甜糖/京东无线宝/点心云/星域云/爱快繁星等），
     * 泛化大厂词（ucloud/baidu/aliyun 等）会误伤正常 App，坚决不加；
     * "pcdn/dcdn" 通用兜底覆盖运营商 DPI 代号（如"PCDN_合一"）对应的平台流量。
     */
    private val DNS_PCDN_KEYWORDS = listOf(
        // 通用
        "pcdn", "dcdn",
        // 迅雷/网心云系
        "xunlei", "thunder", "onething", "walkcloud", "wky", "onecloud",
        // 甜糖
        "tiantang", "tiptime",
        // 点心云
        "dianxin", "dxpool",
        // 京东云无线宝
        "jdcloud", "joylink", "wuxianbao",
        // 星域云/网宿
        "yxcloud", "xycdn", "chinanetcenter",
        // 金山云 PCDN
        "kscdn", "ksyun-cdn",
        // 爱快繁星
        "fanxing",
        // 其他小平台
        "xzb", "zqb"
    )

    /** tcpdump DNS 行域名提取：`12345+ A? xunlei-cdn.example.com. (28)` */
    private val DNS_QNAME_RE = Regex(
        """\b(?:A|AAAA|HTTPS|TXT|SRV|PTR|MX|CNAME|NS)\?\s+([0-9a-zA-Z][0-9a-zA-Z._-]*)\.\s*\("""
    )

    /** 从 tcpdump DNS 抓包输出中提取含 PCDN 平台关键词的域名（小写去重） */
    private fun parseDnsPcdnDomains(out: String): List<String> {
        val domains = DNS_QNAME_RE.findAll(out).map { it.groupValues[1].lowercase() }.toList()
        return domains.filter { d -> DNS_PCDN_KEYWORDS.any { d.contains(it) } }.distinct()
    }

    /**
     * 从 tcpdump 输出收集目标流量涉及的外部目的 IP（排除目标自身与局域网），
     * 供 IDC 归属检测使用。
     */
    private fun collectExternalDstIps(out: String, targetIp: String): List<String> {
        val result = LinkedHashSet<String>()
        // 只统计目标设备发出（上行）的外部目的 IP：PCDN 特征是上行分发给大量节点
        val upLine = Regex("""IP ${Regex.escape(targetIp)}\.\d+ > (\d{1,3}(?:\.\d{1,3}){3})\.\d+""")
        for (line in out.lineSequence()) {
            val m = upLine.find(line) ?: continue
            val ip = m.groupValues[1]
            if (!isPrivateIp(ip)) result.add(ip)
        }
        return result.toList()
    }

    /**
     * 解析接口子网（CIDR，如 192.168.10.0/24）——local_network 表直连路由用
     * （fallback：LanInterfaceResolver 拿不到时从接口地址算）。失败返回 null。
     */
    private fun resolveSubnet(runner: (String) -> String, iface: String): String? {
        val out = try { runner("ip -o addr show dev $iface") } catch (e: Exception) { "" }
        val m = Regex("""inet\s+(\d{1,3}(?:\.\d{1,3}){3})/(\d{1,2})""").find(out) ?: return null
        val ip = m.groupValues[1]
        val prefix = m.groupValues[2].toIntOrNull() ?: return null
        val n = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (n.size != 4) return null
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = ((n[0].toLong() shl 24) or (n[1].toLong() shl 16) or (n[2].toLong() shl 8) or n[3].toLong()) and mask
        return listOf(
            (net shr 24) and 0xFF, (net shr 16) and 0xFF, (net shr 8) and 0xFF, net and 0xFF
        ).joinToString(".") + "/$prefix"
    }

    /** 解析接口全局 IPv6 前缀（如 240e:xxx::/64）——local_network 表 v6 直连路由用；失败返回 null */
    private fun resolveV6Prefix(runner: (String) -> String, iface: String): String? {
        val out = try { runner("ip -6 addr show dev $iface scope global") } catch (e: Exception) { "" }
        return Regex("""inet6\s+([0-9a-fA-F:]+)/(\d{1,3})""").find(out)?.let {
            val raw = it.groupValues[1]
            val prefix = it.groupValues[2].toIntOrNull() ?: 64
            // 压缩格式展开并截断到 /prefix：直接用 ::/0 兜底不现实，取前 prefix 位——简化处理：
            // Android 现场 v6 多为 /64，直接用网段号 + ::/64（低位清 0 依赖地址本身规范）
            if (raw.contains("::")) {
                // 展开 :: 为足够多的 0 组
                val head = raw.substringBefore("::").split(":").filter { it.isNotEmpty() }
                val tail = raw.substringAfter("::").split(":").filter { it.isNotEmpty() }
                val zeroCount = 8 - head.size - tail.size
                val groups = (head + List(zeroCount) { "0" } + tail).map {
                    it.toLongOrNull(16) ?: 0L
                }
                val keep = prefix / 16
                val outGroups = groups.mapIndexed { i, g ->
                    if (i < keep) String.format("%x", g) else if (i == keep && prefix % 16 != 0) {
                        String.format("%x", g and (0xFFFFL shl (16 - prefix % 16)))
                    } else "0"
                }
                outGroups.joinToString(":") + "::/" + prefix
            } else null
        }
    }

    /**
     * 解析网关 IPv6 地址（link-local 或全局）：逐级尝试 `ip -6 route get` / `ip route get`
     * / `ip -6 route show`（Android toybox 对 -6 选项支持不一，逐级兜底）。
     * 纯 IPv4 网络返回 null（转发路由只补 IPv4）。
     */
    private fun resolveGatewayV6(runner: (String) -> String): String? {
        for (cmd in listOf(
            "ip -6 route get 2400:3200::1",
            "ip route get 2400:3200::1",
            "ip -6 route show"
        )) {
            val route = try { runner(cmd) } catch (e: Exception) { continue }
            Regex("""via\s+(fe80::[0-9a-f:]+|240[0-9a-f]:[0-9a-f:]+)""").find(route)?.let {
                return it.groupValues[1]
            }
        }
        return null
    }

    /** 判断是否为内网/保留 IP（与 IdcTargetDetector 同口径，避免跨包引用私有方法） */
    private fun isPrivateIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return true
        val n = parts.mapNotNull { it.toIntOrNull() }
        if (n.size != 4) return true
        val first = n[0]
        return when {
            first == 0 || first == 10 || first == 127 -> true
            first == 169 && n[1] == 254 -> true
            first == 172 && n[1] in 16..31 -> true
            first == 192 && n[1] == 168 -> true
            first >= 224 -> true
            else -> false
        }
    }
}
