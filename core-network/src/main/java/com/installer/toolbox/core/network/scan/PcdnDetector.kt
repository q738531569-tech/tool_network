package com.installer.toolbox.core.network.scan

import android.content.Context
import com.installer.toolbox.core.network.discovery.DeviceScanner
import com.installer.toolbox.core.network.discovery.HostInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * PCDN 设备特征检测器（阶段1，安全版，无需 MITM / root）。
 *
 * 检出方针（用户拍板）：漏报优先级高于误报——宁错杀，不放过。
 * 流程漏斗式分层：先宽进列出全部可疑设备，再逐步详细分析排除误报。
 *
 * L1 宽进（全部在线设备，低成本，把可疑设备全部列出）：
 *   - TCP 特征端口（迅雷/网心系常见端口，300ms/端口；≥2 高、1 个中）
 *   - 主机名 PCDN 关键词（中）
 *   - 一 MAC 多 IP（二级路由/NAT 特征；无摄像头证据 → 中）
 *
 * L2 详细分析（排除误报 + 增强确认）：
 *   - DHT 协议 ping（全部在线设备，独立兜底信号）：回 pong 即 P2P 节点 → 高
 *   - UPnP 映射表核对：描述含 PCDN 关键词 → 高；≥5 条 → 中；3-4 条 → 低
 *     （路由器默认关 UPnP 时跳过，无证据零影响）
 *   - 多 IP 候选设备网页身份识别：摄像头页 → 排除判正常（多镜头设备）；
 *     路由器管理页 → 二级路由嫌疑增强；协议级证据（DHT/UPnP 描述/特征端口）优先于排除
 *
 * L3 流量审计（阶段2，TrafficAudit 综合评分）：唯一给出"疑似 PCDN"结论的层；
 * 特征层"高/中/低"均为可疑提示，需流量审计确认（避免特征误报直接定性）。
 *
 * 局限：PCDN 盒子通常只开 1~3 个 TCP 端口且常为随机/可配置端口，主数据面是 UDP
 * （TCP connect 探测不到）；藏在二级路由 NAT 后面的盒子在主路由侧不可见；
 * 完全无信号设备（端口随机且 DHT 端口不在默认段/单 IP/无 UPnP）特征层发现不了，
 * 只能靠流量审计兜底。特征层天然漏检率高，实锤靠流量审计。
 */
object PcdnDetector {

    /** 单个可疑设备特征结果 */
    data class PcdnDevice(
        val ip: String,
        val mac: String?,
        val vendor: String?,
        val hostname: String?,
        val openPorts: List<Int>,
        /** 置信度：高（协议级证据/特征端口≥2）/ 中 / 低 / 正常（摄像头等多网卡排除） */
        val confidence: String,
        /** 判定依据列表（正常设备为空，摄像头/多网卡排除说明除外） */
        val reasons: List<String>,
        /** 是否可疑（true=高/中/低，false=正常在线设备） */
        val suspicious: Boolean,
        /** 主路由 UPnP 表里该 IP 的映射条目数（0=无映射或路由器未开 UPnP） */
        val upnpMappingCount: Int = 0,
        /**
         * 疑似二级路由（**综合判定，命中 1 个特征即 true**，用户拍板）：
         * 一 MAC 多 IP / 一 IP 多 MAC / 开放 53 端口（DNS 转发）/ 网页呈路由器管理页 /
         * 主机名含路由器关键词。用于 UI 引导『借道审计』，不改变 PCDN 置信度体系。
         */
        val routerLike: Boolean = false
    )

    /** 检测结果 */
    data class PcdnDetectResult(
        /** 全部在线设备（含正常设备，suspicious=false），可疑设备在前 */
        val devices: List<PcdnDevice>,
        /** 在线主机数（已扫描数） */
        val scanned: Int,
        val note: String?
    )

    /**
     * PCDN 特征 TCP 端口集：迅雷/网心云/玩客云/京东云无线宝等客户端常见服务端口，
     * 6881-6889 为 BT DHT/传输常用段。UDP 侧无法用 TCP connect 探测，属已知局限。
     */
    val PCDN_FEATURE_PORTS: Set<Int> = setOf(
        10881, 10999, 27015, 27016, 27017, 27018, 27019, 28015, 8800, 22222, 51413
    ) + (6881..6889)

    /** DHT 协议 ping 探测端口（UDP）：BT DHT 默认段 + Transmission 51413 */
    private val DHT_UDP_PORTS = (6881..6889).toList() + 51413

    /** 单端口 connect / DHT ping 超时（毫秒） */
    private const val PORT_TIMEOUT_MS = 300

    /** 特征端口/DHT 补扫并发上限 */
    private const val CONCURRENCY = 20

    /** TTL 探测预算（候选设备并发 ping，每台 1s 超时） */
    private const val TTL_BUDGET_MS = 8_000L

    /** SSDP 广播探测预算（一次广播 + 2.5 秒收响应） */
    private const val SSDP_BUDGET_MS = 5_000L

    /** UPnP 映射表查询预算（毫秒）；路由器默认关 UPnP，无响应即跳过，零影响 */
    private const val UPNP_BUDGET_MS = 6000L

    /** L2 DHT 针对性确认预算（只对候选设备，数量少） */
    private const val DHT_BUDGET_MS = 6000L

    /** 网页身份识别预算（对开放 80 端口的设备做摄像头/路由器页面识别，优先排除摄像头误报） */
    private const val WEB_BUDGET_MS = 8_000L

    /** UPnP 映射条目数阈值：≥5 中（大量映射）、3-4 低、1-2 不计（游戏机/BT 正常范围） */
    private const val UPNP_MID_THRESHOLD = 5
    private const val UPNP_LOW_THRESHOLD = 3

    /** HTTP 标题探测客户端（多 IP 候选设备摄像头网页识别用；短超时；禁系统代理防历史坑）
     *  gSOAP 设备（ONVIF 摄像头）响应偏慢，read 超时放宽到 3 秒防漏识别 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    /** PCDN 主机名关键词（与 MacVendorTable 同源，此处复制保持本文件自包含） */
    private val PCDN_HOST_KEYWORDS = listOf(
        "thunder", "xunlei", "wangxin", "jdc", "jdcloud", "playcloud",
        "cdnode", "pcdn", "walkcloud", "xiazaibao", "netcore", "赚钱宝", "玩客云", "网心"
    )

    /**
     * 摄像头典型服务端口：RTSP(554)、海康 SDK(8000)、雄迈(8899/34567)、
     * 大华(37777)、通用 ONVIF/媒体端口等。一 MAC 多 IP 设备命中任一端口，
     * 即视为摄像头特征（多镜头/多网卡），不计二级路由嫌疑。
     */
    private val CAMERA_PORTS = setOf(554, 8000, 8899, 37777, 34567, 34599, 8999, 9000, 7070)

    /** 摄像头主机名关键词（小写匹配）：海康 DS-2xx / 大华 DH-xx 型号前缀、IPC/NVR/DVR 等 */
    private val CAMERA_HOST_KEYWORDS = listOf(
        "ipc", "camera", "hik", "dhi", "nvr", "dvr", "ds-2", "dh-ipc",
        "镜头", "摄像", "雄迈", "中维", "tp-ipc"
    )

    /** 摄像头网页关键词（HTTP 页标题/正文前段，多 IP 设备无端口/主机名证据时的兜底排除）
     *  含通用词 camera/tapo/webcam：TP-LINK 摄像头等网页只有品牌名+型号时也能排除
     *  （现场案例：开放 80 端口的 TP-LINK 摄像头被"tp-link"关键词误判为私接路由） */
    private val CAMERA_WEB_KEYWORDS = listOf(
        "hikvision", "dahua", "xmeye", "雄迈", "中维", "海康", "大华", "摄像", "onvif", "rtsp",
        "ipcamera", "camera", "webcam", "tapo", "ipc", "nvr", "dvr"
    )

    /**
     * 路由器管理页关键词（多 IP 设备网页命中 → 二级路由嫌疑增强）。
     * 注意："gateway" 已剔除——摄像头/打印机等设备配置页也含 "Default Gateway" 字样，
     * 曾把开 80 端口的摄像头误判成路由器（现场 .137 案例）；只保留强路由特征词。
     */
    private val ROUTER_WEB_KEYWORDS = listOf(
        "openwrt", "luci", "immortalwrt", "huawei", "华为", "小米路由", "miwifi",
        "tp-link", "路由器", "router", "宽带", "上网设置", "管理页面"
    )

    /** 路由器主机名关键词（二级路由综合判定用，小写匹配） */
    private val ROUTER_HOST_KEYWORDS = listOf(
        "router", "gateway", "miwifi", "tplink", "tp-link", "hiwifi",
        "openwrt", "lede", "immortalwrt", "padavan", "rax3000", "路由器", "newifi", "youhua"
    )

    /** MAC OUI 路由品牌关键词（vendor 字段匹配，二级路由综合判定用；小写匹配） */
    private val ROUTER_VENDOR_KEYWORDS = listOf(
        "tp-link", "普联", "水星", "mercury", "迅捷", "fast", "斐讯", "phicomm",
        "小米", "xiaomi", "华为", "huawei", "中兴", "zte", "华硕", "asus",
        "网件", "netgear", "领势", "linksys", "腾达", "tenda", "锐捷", "ruijie",
        "d-link", "友讯", "belkin", "edimax", "totolink", "totolink"
    )

    /**
     * NAT 路由 TTL 特征值：Linux 路由起始 64、Windows 类起始 128，下挂设备经 NAT 转发
     * TTL 减 1 → 探测到 63 / 127 即"经过一跳 NAT"（普通终端直连应返回 64/128）。
     */
    private val NAT_TTL_VALUES = setOf(63, 127)

    /** SSDP 响应 SERVER 头里的路由类关键词（miniupnpd 是 OpenWrt 等路由的 UPnP 守护进程） */
    private val SSDP_ROUTER_KEYWORDS = listOf(
        "miniupnpd", "openwrt", "lede", "linux", "router", "gateway"
    )

    /** 结果说明固定前缀（含判定依据与进一步确认建议） */
    private const val NOTE_BASE =
        "检出从严（漏报优先于误报）：先宽进列出全部可疑设备，再逐台详细分析（DHT 协议/UPnP 映射/网页身份）排除误报；" +
            "特征层高/中/低均为可疑提示，疑似 PCDN 需流量审计综合评分确认（总分≥4 且至少 1 个特征命中，仅供参考需人工复核）。"

    /**
     * 阶段1 特征检测当前子网（运行在 IO 线程）。
     *
     * @param context 用于取当前 Wi-Fi 网段
     * @param rootRun root 命令通道；非 null 时与设备发现页一致走 root ARP 模式
     *   （能扫到端口全关/禁 ping 的在线设备），null 退回 TCP 端口探测
     * @param timeoutSec 总耗时上限（秒）；全子网扫描可能 30-60 秒，
     *   超时返回已完成的扫描结果并附说明
     */
    suspend fun detect(
        context: Context,
        rootRun: ((String) -> String)? = null,
        timeoutSec: Int = 40,
        subnetOverride: String? = null
    ): PcdnDetectResult =
        withContext(Dispatchers.IO) {
            val subnet = subnetOverride ?: try {
                if (rootRun != null) {
                    DeviceScanner.currentSubnet(context, rootRun)
                } else {
                    DeviceScanner.currentSubnet(context)
                }
            } catch (e: Exception) {
                "192.168.1.0/24"
            }
            val totalMs = (timeoutSec * 1000L).coerceAtLeast(1000L)
            // 时间预算分配：60% 给全子网在线探测，剩余给 L1 特征端口补扫。
            // 注意：root ARP 模式 8 批（每批 32 台 ping + wait + 读 ARP 表）约需 12-16 秒，
            // 慢网络（设备响应慢）可能更久——预算必须给足，否则超时中断返回 0 台
            // （曾现：100.1 网络设备发现页无超时扫 19 台，PCDN 检测 12 秒预算中断 0 台）
            val scanMs = (totalMs * 6 / 10).coerceAtLeast(1000L)

            val hosts = withTimeoutOrNull(scanMs) {
                // 与设备发现页保持一致：root 时走 ARP 模式（链路层发现，端口全关设备也能扫到）；
                // 特征扫描只要设备清单不要设备名，关闭 mDNS 避免其等待拖慢扫描
                DeviceScanner(context).scan(
                    subnet = subnet,
                    rootRun = rootRun,
                    enableMdns = false,
                    onProgress = { _, _ -> }
                )
            }
            if (hosts == null) {
                return@withContext PcdnDetectResult(
                    devices = emptyList(),
                    scanned = 0,
                    note = NOTE_BASE + "全子网扫描超过 ${timeoutSec} 秒被中断，未获取到在线主机；请连接 Wi-Fi 或增大超时后重试。"
                )
            }
            // 扫描完成但 0 台：**明确报错区分原因**（现场踩坑：广播风暴把光猫 CPU 打满时
            // 探测全超时，静默返回 0 台被误当"无设备"——必须提示异常而非假装正常）
            if (hosts.isEmpty()) {
                return@withContext PcdnDetectResult(
                    devices = emptyList(),
                    scanned = 0,
                    note = NOTE_BASE + "扫描完成但 0 台在线设备，可能原因：①手机未连接现场 Wi-Fi；" +
                        "②网络响应异常（某设备广播风暴/光猫繁忙会把探测全部拖超时，误报成 0 台）；" +
                        "③确实无设备。建议：确认 Wi-Fi 已连接后用『设备发现』页对比结果；" +
                        "若设备发现能扫到而本页 0 台，多半是网络响应慢，增大超时或用设备发现页排查。"
                )
            }

            // ==================== L1 广筛（全部在线设备） ====================
            var partial = false
            var hits: Map<String, List<Int>> = emptyMap()
            val probeResult = withTimeoutOrNull((totalMs - scanMs).coerceAtLeast(1000L)) {
                probeFeaturePorts(hosts.map { it.ip })
            }
            if (probeResult == null) {
                partial = true
            } else {
                hits = probeResult
            }

            // 一 MAC 多 IP 分组统计（纯内存计算）：MAC 归一化小写后按 IP 数找多 IP 设备。
            // 多 IP ≠ 二级路由：双镜头/三镜头摄像头等多网卡设备同样多 IP。
            val macToHosts = hosts
                .filter { !it.mac.isNullOrBlank() }
                .groupBy { it.mac!!.lowercase() }
            val macIpCount = macToHosts.mapValues { (_, list) -> list.map { it.ip }.distinct().size }

            // L1 候选判定（不含任何深度探测）：命中任一信号即进候选集
            data class L1(val host: HostInfo, val hitPorts: List<Int>, val reason: String?, val base: String)
            val l1 = hosts.map { host ->
                val hitPorts = hits[host.ip].orEmpty().sorted()
                val hostMac = host.mac?.lowercase()
                val macIpN = macIpCount[hostMac] ?: 0
                val multiIp = macIpN >= 2
                val camPorts = multiIp && host.openPorts.any { it in CAMERA_PORTS }
                val camName = multiIp && cameraHostKeyword(host.hostname) != null
                val hostKw = hostNameKeyword(host.hostname)
                val (reason, base) = when {
                    hitPorts.size >= 2 ->
                        "命中 ${hitPorts.size} 个 PCDN 特征端口：${hitPorts.joinToString()}（迅雷/网心系常见端口，默认端口未改时）" to "高"
                    hitPorts.size == 1 ->
                        "命中 1 个 PCDN 特征端口：${hitPorts.first()}" to "中"
                    hostKw != null -> "主机名 ${host.hostname} 含 PCDN 关键词（$hostKw）" to "中"
                    multiIp && !camPorts && !camName ->
                        "一台设备占用 $macIpN 个 IP（可能是双网卡设备，也可能是二级路由——PCDN 可能藏在其内网侧）" to "中"
                    multiIp -> null to "正常" // 摄像头端口/主机名证据 → 不进候选，稍后补正常说明
                    else -> null to "正常"
                }
                L1(host, hitPorts, reason, base)
            }
            val candidates = l1.filter { it.base != "正常" }

            // ==================== L2 针对性确认（仅候选设备） ====================
            // 2.1 UPnP 映射表核对：root 时用 LanInterfaceResolver 解析网关直接单播；
            // 无 root 时 UpnpPortMapper 自行组播发现 + subnet .1 兜底。全表查一次，
            // 只与候选设备交叉核对，映射多但 L1 无信号的设备反向拉进候选。
            val gateway = if (rootRun != null) {
                try { LanInterfaceResolver.resolve(rootRun)?.gateway } catch (e: Exception) { null }
            } else null
            val upnp = withTimeoutOrNull(UPNP_BUDGET_MS) {
                UpnpPortMapper.query(gatewayIp = gateway, subnet = subnet)
            }
            val upnpMappings = upnp?.mappings.orEmpty()
            val upnpCountByIp = upnpMappings.groupBy { it.internalClient }.mapValues { (_, v) -> v.size }
            val upnpDescHitIps = upnpMappings
                .filter { UpnpPortMapper.descPcdnKeyword(it.description) != null }
                .map { it.internalClient }
                .toSet()

            // 2.2 DHT 协议 ping：对**全部在线设备**探测（不依赖 L1 候选）——
            // 破"L1 无信号→进不了 L2"的漏报死循环：端口随机化的 PCDN 盒子
            // 若 DHT 端口在默认段，靠此独立兜底信号直接确认 P2P 节点。
            // 成本可控：在线设备通常 ≤30 台，并发 20，约 3 秒；协议标准响应、误报低。
            val dhtTargets = hosts.map { it.ip }
            val dhtHits = if (dhtTargets.isEmpty()) emptySet<String>() else {
                withTimeoutOrNull(DHT_BUDGET_MS) { probeDht(dhtTargets) } ?: emptySet()
            }

            // 2.3 网页身份识别（**摄像头优先排除，反馈修正**）：对全部开放 80 端口的设备
            // **以及全部多 IP 设备**（不管其扫描端口列表是否含 80——端口补扫来源可能不同，
            // 现场 .23/.27 双镜头摄像头曾因识别未覆盖而误报）做网页识别：
            // 摄像头页/ONVIF 头 → camEvidence 排除；路由器管理页 → 增强二级路由嫌疑。
            val multiIpIps = hosts
                .filter { (macIpCount[it.mac?.lowercase()] ?: 0) >= 2 }
                .map { it.ip }
            val webProbeIps = (
                hosts.filter { it.openPorts.contains(80) }.map { it.ip } + multiIpIps
                ).distinct()
            val webIds = if (webProbeIps.isEmpty()) emptyList() else {
                withTimeoutOrNull(WEB_BUDGET_MS) {
                    coroutineScope {
                        webProbeIps.map { ip ->
                            async {
                                val diag: (String) -> Unit = { msg ->
                                    rootRun?.let { runCatching { it(msg) } }
                                }
                                webIdentity(ip, diag)?.let { ip to it }
                            }
                        }.awaitAll().filterNotNull()
                    }
                } ?: emptyList()
            }
            val webCameraIps = webIds.filter { it.second == "camera" }.map { it.first }.toSet()
            val webRouterIps = webIds.filter { it.second == "router" }.map { it.first }.toSet()
            val webTimeoutIps = webIds.filter { it.second == "timeout" }.map { it.first }.toSet()

            // 2.4 二级路由深度特征（命中 1 个即疑似，用户拍板）：
            // ①TTL 递减：root ping 候选设备取 TTL，63/127 = 经一跳 NAT（路由确定性特征）；
            // ②MAC OUI 路由品牌：vendor 匹配（合成处做，无需额外探测）；
            // ③SSDP 广播探测：响应 SERVER 头含 miniupnpd/OpenWrt 等路由守护进程特征。
            val ttlHitIps = if (rootRun != null) {
                val ttlTargets = candidates.map { it.host.ip }.distinct()
                withTimeoutOrNull(TTL_BUDGET_MS) {
                    coroutineScope {
                        ttlTargets.map { ip ->
                            async {
                                val out = runCatching { rootRun("ping -c 1 -W 1 $ip") }.getOrDefault("")
                                val ttl = Regex("""ttl=(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
                                if (ttl in NAT_TTL_VALUES) ip else null
                            }
                        }.awaitAll().filterNotNull().toSet()
                    }
                } ?: emptySet()
            } else emptySet()
            val ssdpRouterIps = withTimeoutOrNull(SSDP_BUDGET_MS) { ssdpRouterProbe() } ?: emptySet()

            // ==================== 合成最终置信度（宽进列可疑 → 严出排除误报） ====================
            // 检出从严（用户拍板：漏报优先级高于误报，宁错杀不放过）：
            // 协议级证据（DHT 响应 / UPnP 描述自报）与特征端口≥2 直接"高"；
            // 其余信号（单特征端口/多 IP/主机名/大量映射）一律"中"起步列可疑；
            // 摄像头特征仅排除"多 IP 外形"维度（有明确证据不是目标），不覆盖协议级证据。
            val byIp = l1.associateBy { it.host.ip }
            // 一 IP 多 MAC 统计（同一 IP 检测到多个 MAC = 路由器桥接/换 MAC 特征，二级路由综合判定用）
            val ipMacCount = hosts.groupBy { it.ip }
                .mapValues { (_, hs) -> hs.mapNotNull { it.mac?.lowercase() }.distinct().size }
            val devices = hosts.map { host ->
                val l = byIp[host.ip]
                val hitPorts = l?.hitPorts.orEmpty()
                val hostMac = host.mac?.lowercase()
                val macIpN = macIpCount[hostMac] ?: 0
                val multiIp = macIpN >= 2
                val camPorts = host.openPorts.any { it in CAMERA_PORTS }
                val camName = cameraHostKeyword(host.hostname) != null
                val hostKw = hostNameKeyword(host.hostname)
                val upnpCount = upnpCountByIp[host.ip] ?: 0
                val upnpDescHit = host.ip in upnpDescHitIps
                val dhtHit = host.ip in dhtHits
                val webCamera = host.ip in webCameraIps
                val webRouter = host.ip in webRouterIps
                val webTimeout = host.ip in webTimeoutIps
                // 摄像头证据（单 IP 也认；网页识别已扩大到全部开放 80 端口的设备）
                val camEvidence = camPorts || camName || webCamera
                // 二级路由综合判定（命中 1 个即疑似，用户拍板）：多 IP / 多 MAC / DNS 53 端口 /
                // 路由器管理页 / 路由器主机名 / TTL 递减 / 路由品牌 OUI / SSDP 路由响应——
                // 仅引导借道审计，不改变 PCDN 置信度。
                // **排除网关本身**：主路由也是路由器（主机名/SSDP 全命中），但不是"二级"路由
                val isGateway = host.ip == gateway
                val ttlHit = host.ip in ttlHitIps
                val vendorHit = host.vendor?.let { v ->
                    ROUTER_VENDOR_KEYWORDS.any { v.contains(it, ignoreCase = true) }
                } == true
                val ssdpHit = host.ip in ssdpRouterIps
                val routerLike = !isGateway && !camEvidence && (
                    (multiIp && !webTimeout) ||
                        (ipMacCount[host.ip] ?: 0) >= 2 ||
                        host.openPorts.contains(53) ||
                        webRouter ||
                        routerHostKeyword(host.hostname) != null ||
                        ttlHit ||
                        vendorHit ||
                        ssdpHit
                    )

                val reasons = mutableListOf<String>()
                val confidence = when {
                    // **摄像头优先排除（反馈修正）**：有摄像头证据且无协议级硬证据
                    // （DHT 响应/UPnP 描述自报/≥2 特征端口）时，PCDN 软信号
                    // （单特征端口/主机名/多 IP/中量映射）视为摄像头自带，判正常——
                    // TP-LINK 等品牌既做路由也做摄像头，不再误报"疑似路由/PCDN"
                    camEvidence && !dhtHit && !upnpDescHit &&
                        hitPorts.size < 2 && upnpCount < UPNP_MID_THRESHOLD -> {
                        reasons.add("呈摄像头特征：多 IP/单特征端口等软信号不再计可疑（避免把摄像头误判成路由器/PCDN）；注意摄像头同样可能内置或被植入 PCDN——若命中协议级硬证据（DHT 响应/UPnP 描述/≥2 特征端口/大量映射）仍会列可疑")
                        "正常"
                    }
                    // 宽进：协议级证据 → 高
                    dhtHit -> {
                        reasons.add("响应 BT DHT 协议 ping（P2P 节点协议级确认）；是否 PCDN 需流量审计确认")
                        "高"
                    }
                    upnpDescHit -> {
                        reasons.add("主路由 UPnP 映射描述含 PCDN 关键词（xunlei/玩客/网心等，设备自报家门）")
                        "高"
                    }
                    hitPorts.size >= 2 -> {
                        reasons.add("命中 ${hitPorts.size} 个 PCDN 特征端口：${hitPorts.joinToString()}（迅雷/网心系常见端口）")
                        "高"
                    }
                    // 宽进：中等信号 → 中
                    hitPorts.size == 1 -> {
                        reasons.add("命中 1 个 PCDN 特征端口：${hitPorts.first()}")
                        "中"
                    }
                    upnpCount >= UPNP_MID_THRESHOLD -> {
                        reasons.add("主路由 UPnP 映射 $upnpCount 条（大量端口映射，PCDN 盒子典型）")
                        "中"
                    }
                    hostKw != null -> {
                        reasons.add("主机名 ${host.hostname} 含 PCDN 关键词（$hostKw）")
                        "中"
                    }
                    multiIp -> {
                        val routerExtra = if (webRouter) "；网页呈路由器管理页特征，二级路由嫌疑增强" else ""
                        reasons.add("一台设备占用 $macIpN 个 IP（可能是双网卡设备，也可能是二级路由——PCDN 可能藏在其内网侧，点击卡片流量审计确认）$routerExtra")
                        "中"
                    }
                    // 宽进：弱信号 → 低（仍列可疑，交给审计确认）
                    upnpCount in UPNP_LOW_THRESHOLD until UPNP_MID_THRESHOLD -> {
                        reasons.add("主路由 UPnP 映射 $upnpCount 条（略多于正常设备）")
                        "低"
                    }
                    else -> "正常"
                }
                // 二级路由综合判定说明（命中 1 个即疑似——**不区分可疑**：
                // 端口全关的纯 NAT 二级路由无 PCDN 特征、confidence 正常，也要标出引导借道审计）
                if (routerLike) {
                    val hits = mutableListOf<String>()
                    if (multiIp) hits.add("一设备多 IP")
                    if ((ipMacCount[host.ip] ?: 0) >= 2) hits.add("一 IP 多 MAC")
                    if (host.openPorts.contains(53)) hits.add("开放 53 端口（DNS）")
                    if (webRouter) hits.add("路由器管理页")
                    if (routerHostKeyword(host.hostname) != null) hits.add("路由器主机名")
                    if (ttlHit) hits.add("TTL 递减（经 NAT 转发）")
                    if (vendorHit) hits.add("路由品牌 MAC（${host.vendor}）")
                    if (ssdpHit) hits.add("UPnP 路由响应（SSDP）")
                    reasons.add("疑似二级路由（${hits.joinToString("/")}）——PCDN 可能藏在其内网侧，建议用『借道审计』还原下挂设备")
                } else if (multiIp && webTimeout) {
                    // 多 IP 但网页识别超时未确认身份：不冒进标路由（双镜头摄像头响应慢曾被误判）
                    reasons.add("一台设备占用 $macIpN 个 IP，但网页识别超时未确认身份（疑似摄像头类设备响应慢），不计路由嫌疑；点击卡片流量审计确认")
                }

                PcdnDevice(
                    ip = host.ip,
                    mac = host.mac,
                    vendor = host.vendor,
                    hostname = host.hostname,
                    openPorts = (host.openPorts + hitPorts).distinct().sorted(),
                    confidence = confidence,
                    reasons = reasons,
                    suspicious = confidence != "正常",
                    upnpMappingCount = upnpCount,
                    routerLike = routerLike
                )
            }.sortedByDescending { it.suspicious }

            val note = buildString {
                append(NOTE_BASE)
                append("本次扫描网段 $subnet，在线主机 ${hosts.size} 台，L1 广筛候选 ${candidates.size} 台，检出可疑 ${devices.count { it.suspicious }} 台（特征命中，需流量审计确认）。")
                if (upnp == null) {
                    append("UPnP 映射查询超时，已跳过该维度。")
                } else if (upnp.mappings.isEmpty() && upnp.note != null) {
                    append("路由器未开 UPnP 或无映射条目，已跳过端口映射维度。")
                } else if (upnp.mappings.isNotEmpty()) {
                    append("主路由 UPnP 映射表共 ${upnp.mappings.size} 条（仅强证据加分，不影响其他维度）。")
                }
                if (partial) append("L1 特征端口补扫超时，部分主机未完成深度探测。")
            }
            PcdnDetectResult(devices, hosts.size, note)
        }

    /**
     * 单机判定（可选）：只对目标 IP 探测特征端口集。
     * @return "高"（命中 ≥2 个特征端口）/ "中"（命中 1 个）/ null（未命中）
     */
    suspend fun classify(target: String): String? = withContext(Dispatchers.IO) {
        if (target.isBlank()) return@withContext null
        val hits = PCDN_FEATURE_PORTS.filter { isPortOpen(target, it) }
        when {
            hits.size >= 2 -> "高"
            hits.size == 1 -> "中"
            else -> null
        }
    }

    // ---- 内部实现 ----

    /** 对一批 IP 并发补扫特征端口，返回 IP → 开放特征端口列表 */
    private suspend fun probeFeaturePorts(ips: List<String>): Map<String, List<Int>> =
        coroutineScope {
            val semaphore = Semaphore(CONCURRENCY)
            ips.map { ip ->
                async {
                    semaphore.withPermit {
                        ip to PCDN_FEATURE_PORTS.filter { port -> isPortOpen(ip, port) }
                    }
                }
            }.awaitAll().toMap()
        }

    /**
     * 对候选设备主动发 BT DHT KRPC ping（UDP），回响应即 P2P 节点。
     * 协议标准响应、误报低：只有真正监听 DHT 的 P2P 应用（BT/PCDN 客户端）会回 pong。
     */
    private suspend fun probeDht(ips: List<String>): Set<String> =
        coroutineScope {
            val semaphore = Semaphore(CONCURRENCY)
            ips.map { ip ->
                async {
                    semaphore.withPermit {
                        if (DHT_UDP_PORTS.any { dhtPing(ip, it) }) ip else null
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }

    /** 单个 DHT KRPC ping；收到 KRPC 响应（d1: 开头 + type=r）即命中 */
    private fun dhtPing(ip: String, port: Int): Boolean {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = PORT_TIMEOUT_MS
                val payload = buildDhtPing()
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), port))
                val buf = ByteArray(1024)
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(pkt)
                        val data = String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1)
                        if (data.startsWith("d1:") && data.contains("1:y1:r")) return true
                    } catch (e: SocketTimeoutException) {
                        return false
                    } catch (e: Exception) {
                        return false
                    }
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    /** 构造 BT DHT KRPC ping 报文（bencode）：d1:ad2:id20:<20B>e1:q4:ping1:t2:aa1:y1:qe */
    private fun buildDhtPing(): ByteArray {
        val id = ByteArray(20) { it.toByte() }
        val out = ByteArrayOutputStream()
        out.write("d1:ad2:id20:".toByteArray(Charsets.ISO_8859_1))
        out.write(id)
        out.write("e1:q4:ping1:t2:aa1:y1:qe".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** 单端口 TCP connect 探测 */
    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), PORT_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 主机名是否命中 PCDN 关键词；未命中返回 null */
    private fun hostNameKeyword(hostname: String?): String? {
        val h = hostname?.lowercase().orEmpty()
        return PCDN_HOST_KEYWORDS.firstOrNull { h.contains(it) }
    }

    /** 主机名是否命中摄像头关键词；未命中返回 null */
    private fun cameraHostKeyword(hostname: String?): String? {
        val h = hostname?.lowercase().orEmpty()
        return CAMERA_HOST_KEYWORDS.firstOrNull { h.contains(it) }
    }

    /** 主机名是否命中路由器关键词（二级路由综合判定用）；未命中返回 null */
    private fun routerHostKeyword(hostname: String?): String? {
        val h = hostname?.lowercase().orEmpty()
        return ROUTER_HOST_KEYWORDS.firstOrNull { h.contains(it) }
    }

    /**
     * SSDP 广播探测：发一次 UPnP M-SEARCH（239.255.255.250:1900），收集 2.5 秒内
     * 响应者的 SERVER 头；只认含路由守护进程特征（miniupnpd/OpenWrt/Linux 等）的 IP——
     * 智能音箱/电视的 UPnP 响应不算路由。
     */
    private fun ssdpRouterProbe(): Set<String> {
        return try {
            val socket = MulticastSocket(1900)
            socket.soTimeout = 2500
            val req = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: upnp:rootdevice\r\n\r\n"
            val addr = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(req.toByteArray(), req.length, addr, 1900))
            val result = mutableSetOf<String>()
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                    val server = Regex("(?i)SERVER:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim().orEmpty()
                    val ip = p.address.hostAddress
                    if (server.isNotEmpty() && SSDP_ROUTER_KEYWORDS.any { server.contains(it, ignoreCase = true) }) {
                        result.add(ip)
                    }
                } catch (e: SocketTimeoutException) {
                    break
                } catch (e: Exception) {
                    break
                }
            }
            runCatching { socket.close() }
            result
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * 网页身份识别（对开放 80 端口的设备做摄像头/路由器判定）：返回 "camera"（摄像头）、
     * "router"（路由器管理页）、null（无法识别）。
     * **ONVIF 摄像头铁证**：Server 头 gSOAP（ONVIF 标准 SOAP 栈）或 xml 内容类型 + 空页面——
     * 现场 .23/.27 双镜头摄像头只开 80、页面空、无摄像头关键词，曾因此被误判"疑似二级路由"。
     */
    /**
     * 网页身份识别（对开放 80 端口的设备做摄像头/路由器判定）：
     * 返回 "camera"（摄像头）、"router"（路由器管理页）、"timeout"（端口开放但响应超时，
     * 身份未确认）、null（无特征/连接失败）。
     * **ONVIF 摄像头铁证**：Server 头 gSOAP（ONVIF 标准 SOAP 栈）或 xml 内容类型 + 空页面——
     * 现场 .23/.27 双镜头摄像头只开 80、页面空、无摄像头关键词，曾因此被误判"疑似二级路由"。
     * 双镜头设备并发响应偏慢：超时重试一次再定论。
     */
    private fun webIdentity(ip: String, onDiag: ((String) -> Unit)? = null): String? {
        for (attempt in 0..1) {
            try {
                val resp = httpClient.newCall(Request.Builder().url("http://$ip/").get().build()).execute()
                val server = resp.header("Server").orEmpty()
                val ctype = resp.header("Content-Type").orEmpty()
                val html = resp.body?.string()?.take(8192) ?: return null
                val title = HTML_TITLE_RE.find(html)?.groupValues?.get(1).orEmpty()
                val text = (title + " " + html).lowercase()
                return when {
                    server.contains("gsoap", ignoreCase = true) ||
                        (ctype.contains("xml") && html.isBlank()) -> "camera"
                    CAMERA_WEB_KEYWORDS.any { text.contains(it) } -> "camera"
                    ROUTER_WEB_KEYWORDS.any { text.contains(it) } -> "router"
                    else -> null
                }.also {
                    // 识别诊断打进运行日志（过程可见）：识别结果/响应头，识别不到时留线索
                    val msg = "网页识别 $ip → ${it ?: "无法识别"}（Server=$server ContentType=$ctype）"
                    onDiag?.invoke(msg)
                    android.util.Log.d("PcdnWebId", msg)
                }
            } catch (e: java.net.SocketTimeoutException) {
                if (attempt == 0) continue // 双镜头设备并发响应慢：超时重试一次
                val msg = "网页识别 $ip 超时（重试后仍无响应，身份未确认）"
                onDiag?.invoke(msg)
                android.util.Log.d("PcdnWebId", msg)
                return "timeout"
            } catch (e: Exception) {
                val msg = "网页识别 $ip 失败：${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}"
                onDiag?.invoke(msg)
                android.util.Log.d("PcdnWebId", msg)
                return null
            }
        }
        return "timeout"
    }

    /** HTML <title> 提取 */
    private val HTML_TITLE_RE = Regex("""(?is)<title[^>]*>(.*?)</title>""")
}
