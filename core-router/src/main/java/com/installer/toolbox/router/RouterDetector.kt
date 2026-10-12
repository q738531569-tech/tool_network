package com.installer.toolbox.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket

/**
 * 品牌识别核心:HTTP 首页特征 + UPnP SSDP 描述 + 特征库匹配。
 *
 * 设计依据:运营商定制设备(华为/中兴光猫、天翼网关、TP-Link 路由器)虽闭源无 Shell,
 * 但 Web 管理页与 UPnP 协议是公开的——识别层(HTTP 特征 + UPnP)协议公开,做扎实;
 * 登录/状态页路径另由 [RouterAdapter] 家族承载,标注"候选待现场校准"。
 *
 * 注意:所有网络请求均需 OkHttp `.proxy(Proxy.NO_PROXY)` 直连(绕过系统代理),
 * 每项探测独立 try/catch,单项失败不影响整体识别。
 */
object RouterDetector {

    /** 每项 HTTP 探测超时(毫秒),与门面共享客户端保持一致 */
    internal const val HTTP_TIMEOUT_MS = 3000L

    /** 网关候选列表:运营商光猫/路由器常见默认管理地址 */
    private val GATEWAY_CANDIDATES = listOf("192.168.1.1", "192.168.0.1", "192.168.100.1")

    /** 组播地址与端口:UPnP SSDP 发现协议固定值 */
    private const val SSDP_GROUP = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /** 等待 SSDP 响应的时间(毫秒) */
    private const val SSDP_WAIT_MS = 2000L

    /**
     * 品牌识别入口。
     *
     * @param gatewayIp 指定网关;为空时从候选列表探测哪个通(TCP 80 快速试)
     * @param okHttpClient 由门面传入的共享客户端(必须已配 NO_PROXY 与超时)
     */
    suspend fun detect(gatewayIp: String? = null, okHttpClient: OkHttpClient): RouterProbeResult =
        withContext(Dispatchers.IO) {
            // 1. 确定网关:显式传入优先,否则候选列表逐个试 80 端口
            val gw = gatewayIp?.takeIf { it.isNotBlank() }?.trim() ?: findGateway()
            if (gw == null) {
                return@withContext RouterProbeResult(
                    brand = RouterBrand.GENERIC,
                    modelName = null,
                    confidence = "低",
                    gatewayIp = gatewayIp ?: "",
                    adminUrl = null,
                    signals = emptyList(),
                    note = "候选网关 ${GATEWAY_CANDIDATES.joinToString()} 的 80 端口均无响应," +
                        "请确认手机与光猫/路由器在同一局域网,或手动输入网关地址"
                )
            }
            // 2. HTTP 首页探测(Server 头 / 最终 URL / 标题 / 前 500 字符文本)
            val http = probeHttp(gw, okHttpClient)
            // 3. UPnP SSDP 组播探测(厂商/型号描述)
            val upnp = probeUpnp(okHttpClient)
            // 4. 特征库匹配,产出探测结果
            matchBrand(gw, http, upnp)
        }

    // ------------------------------------------------------------------
    // 内部探测数据结构
    // ------------------------------------------------------------------

    /** HTTP 首页探测结果 */
    private data class HttpProbeInfo(
        val serverHeader: String?,
        val finalUrl: String?,
        val title: String?,
        /** 去除标签后的页面文本前 500 字符,用于关键字匹配 */
        val headText: String?
    )

    /** UPnP 描述信息 */
    private data class UpnpInfo(
        val manufacturer: String?,
        val modelName: String?,
        val location: String?
    )

    /** 命中的一条品牌特征 */
    private data class BrandSignal(
        val brand: RouterBrand,
        val desc: String,
        /** true 表示该特征较弱(仅默认 IP 命中),单独命中只给"低"置信度 */
        val weak: Boolean = false
    )

    // ------------------------------------------------------------------
    // 网关探测
    // ------------------------------------------------------------------

    /** 候选列表逐个试 TCP 80(TCP 快速试探,每地址 800ms),返回第一个可连通的 */
    private fun findGateway(): String? {
        for (candidate in GATEWAY_CANDIDATES) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(candidate, 80), 800)
                    return candidate
                }
            } catch (_: Exception) {
                // 该地址不通,继续下一个
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // HTTP 首页探测
    // ------------------------------------------------------------------

    private fun probeHttp(gw: String, client: OkHttpClient): HttpProbeInfo {
        return try {
            // NO_PROXY 已在共享客户端配置;OkHttp 默认跟随重定向,finalUrl 即最终登录页
            val request = Request.Builder().url("http://$gw/").build()
            client.newCall(request).execute().use { resp ->
                val html = resp.body?.string() ?: ""
                HttpProbeInfo(
                    serverHeader = resp.header("Server"),
                    finalUrl = resp.request.url.toString(),
                    title = extractTitle(html),
                    headText = stripTags(html).take(500)
                )
            }
        } catch (e: Exception) {
            // 首页不可达:不影响后续 UPnP 探测与默认 IP 特征
            HttpProbeInfo(null, null, null, null)
        }
    }

    /** 提取 <title> 内容 */
    private fun extractTitle(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues?.get(1)?.trim()?.take(120)

    /** 去除 script/style 与标签,保留纯文本(用于关键字匹配) */
    private fun stripTags(html: String): String =
        html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ------------------------------------------------------------------
    // UPnP SSDP 探测
    // ------------------------------------------------------------------

    /**
     * 向 239.255.255.250:1900 发送 M-SEARCH 组播,等 2 秒收 InternetGatewayDevice 响应,
     * 解析 LOCATION 头并 GET 设备描述 XML,提取 manufacturer / modelName。
     *
     * 说明:Android 收组播依赖系统对组播包放行(部分 ROM 默认过滤),收不到时该信号
     * 自动缺失,不影响 HTTP 特征识别;加入组播组失败也仅记为空,不阻塞流程。
     */
    private fun probeUpnp(client: OkHttpClient): UpnpInfo = try {
        val group = InetAddress.getByName(SSDP_GROUP)
        val socket = MulticastSocket(0)
        try {
            socket.soTimeout = SSDP_WAIT_MS.toInt()
            socket.timeToLive = 2
            runCatching { socket.joinGroup(group) } // 失败仅丢 UPnP 信号
            val query = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_GROUP:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
            val data = query.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, group, SSDP_PORT))

            // 收响应直到超时,优先取 IGD 的 LOCATION
            val deadline = System.currentTimeMillis() + SSDP_WAIT_MS
            var location: String? = null
            while (System.currentTimeMillis() < deadline) {
                val buf = ByteArray(4096)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet) // 超时抛 SocketTimeoutException,结束收包
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val loc = parseLocation(text)
                if (loc != null) {
                    location = loc
                    break
                }
            }
            location?.let { fetchUpnpDescription(it, client) } ?: UpnpInfo(null, null, null)
        } finally {
            runCatching { socket.leaveGroup(group) }
            socket.close()
        }
    } catch (e: Exception) {
        UpnpInfo(null, null, null)
    }

    /** 从 SSDP 响应报文解析 LOCATION 头(仅接受 200 OK 响应) */
    private fun parseLocation(ssdpResponse: String): String? {
        if (!ssdpResponse.startsWith("HTTP/1.1 200")) return null
        return ssdpResponse.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    }

    /** GET 设备描述 XML,提取 manufacturer / modelName */
    private fun fetchUpnpDescription(location: String, client: OkHttpClient): UpnpInfo {
        return try {
            val request = Request.Builder().url(location).build()
            client.newCall(request).execute().use { resp ->
                val xml = resp.body?.string() ?: ""
                UpnpInfo(
                    manufacturer = extractXmlTag(xml, "manufacturer"),
                    modelName = extractXmlTag(xml, "modelName"),
                    location = location
                )
            }
        } catch (e: Exception) {
            UpnpInfo(null, null, location)
        }
    }

    private fun extractXmlTag(xml: String, tag: String): String? =
        Regex("(?is)<$tag[^>]*>(.*?)</$tag>").find(xml)
            ?.groupValues?.get(1)?.trim()?.take(120)

    // ------------------------------------------------------------------
    // 品牌特征库匹配
    // ------------------------------------------------------------------

    /**
     * 特征库匹配(依据公开资料整理,中文注释说明依据):
     * - 华为 HUAWEI:Server 头含 httpd;标题含 HUAWEI;登录 URL 含 /asp/ 或 login.cgi;
     *   UPnP manufacturer 含 HUAWEI;默认管理地址 192.168.100.1
     * - 中兴 ZTE:标题/页面含 ZTE、中兴;URL 含 getpage.gch;UPnP manufacturer 含 ZTE
     * - TP-Link:标题含 TP-LINK;UPnP manufacturer 含 TP-LINK/TP-Link;常见 192.168.1.1/192.168.0.1
     * - 天翼网关 TIANYI:Server 头 Nexus;URL login.cgi;页面含 天翼网关/e-Link;
     *   UPnP 为 ZTE/Huawei 代工且页面含电信字样
     * - OpenWrt:标题/页面含 OpenWrt、ImmortalWrt、LuCI;Server 含 luci;URL 含 /cgi-bin/luci
     *
     * 命中多个时取置信度最高;置信度:特征≥3 高、≥1 中、仅默认 IP 命中低;无命中 → GENERIC。
     */
    private fun matchBrand(gw: String, http: HttpProbeInfo, upnp: UpnpInfo): RouterProbeResult {
        val signals = mutableListOf<BrandSignal>()
        // 页面文本 = 标题 + 前 500 字符,统一做关键字匹配
        val page = buildString {
            http.title?.let { append(it).append(' ') }
            http.headText?.let { append(it) }
        }
        val url = http.finalUrl ?: ""

        // ---- 华为 HUAWEI ----
        if (http.serverHeader?.contains("httpd", ignoreCase = true) == true) {
            signals += BrandSignal(RouterBrand.HUAWEI, "Server 头含 httpd(华为设备常见)")
        }
        if (page.contains("HUAWEI", ignoreCase = true)) {
            signals += BrandSignal(RouterBrand.HUAWEI, "页面标题/文本含 HUAWEI")
        }
        if (url.contains("/asp/") || url.contains("login.cgi", ignoreCase = true)) {
            signals += BrandSignal(RouterBrand.HUAWEI, "登录入口 URL 含 /asp/ 或 login.cgi")
        }
        if (upnp.manufacturer?.contains("HUAWEI", ignoreCase = true) == true) {
            signals += BrandSignal(RouterBrand.HUAWEI, "UPnP 描述 manufacturer 含 HUAWEI")
        }
        if (gw == "192.168.100.1") {
            signals += BrandSignal(RouterBrand.HUAWEI, "管理地址为华为默认 192.168.100.1", weak = true)
        }

        // ---- 中兴 ZTE ----
        if (page.contains("ZTE", ignoreCase = true) || page.contains("中兴")) {
            signals += BrandSignal(RouterBrand.ZTE, "页面标题/文本含 ZTE 或 中兴")
        }
        if (url.contains("getpage.gch", ignoreCase = true)) {
            signals += BrandSignal(RouterBrand.ZTE, "登录入口 URL 含 getpage.gch(中兴常见)")
        }
        if (upnp.manufacturer?.contains("ZTE", ignoreCase = true) == true) {
            signals += BrandSignal(RouterBrand.ZTE, "UPnP 描述 manufacturer 含 ZTE")
        }

        // ---- TP-Link ----
        if (page.contains("TP-LINK", ignoreCase = true)) {
            signals += BrandSignal(RouterBrand.TP_LINK, "页面标题/文本含 TP-LINK")
        }
        if (upnp.manufacturer?.contains("TP-LINK", ignoreCase = true) == true) {
            signals += BrandSignal(RouterBrand.TP_LINK, "UPnP 描述 manufacturer 含 TP-LINK")
        }
        if (gw == "192.168.1.1" || gw == "192.168.0.1") {
            signals += BrandSignal(RouterBrand.TP_LINK, "管理地址为 TP-Link 常见默认 192.168.1.1/192.168.0.1", weak = true)
        }

        // ---- 天翼网关 TIANYI ----
        if (http.serverHeader?.contains("Nexus", ignoreCase = true) == true) {
            signals += BrandSignal(RouterBrand.TIANYI, "Server 头含 Nexus(天翼网关 e-Link 常见)")
        }
        if (url.contains("login.cgi", ignoreCase = true)) {
            signals += BrandSignal(RouterBrand.TIANYI, "登录入口 URL 含 login.cgi")
        }
        if (page.contains("天翼网关") || page.contains("e-Link", ignoreCase = true) || page.contains("中国电信")) {
            signals += BrandSignal(RouterBrand.TIANYI, "页面含 天翼网关/e-Link/中国电信 字样")
        }
        if ((upnp.manufacturer?.contains("ZTE", ignoreCase = true) == true ||
                upnp.manufacturer?.contains("HUAWEI", ignoreCase = true) == true) &&
            (page.contains("天翼") || page.contains("中国电信") || page.contains("e-Link", ignoreCase = true))
        ) {
            signals += BrandSignal(RouterBrand.TIANYI, "UPnP 显示为 ZTE/Huawei 代工且页面含电信/天翼字样")
        }

        // ---- OpenWrt(含 ImmortalWrt 等衍生)----
        if (page.contains("OpenWrt", ignoreCase = true) ||
            page.contains("ImmortalWrt", ignoreCase = true) ||
            page.contains("LuCI", ignoreCase = true) ||
            upnp.modelName?.contains("OpenWrt", ignoreCase = true) == true
        ) {
            signals += BrandSignal(RouterBrand.OPENWRT, "页面/UPnP 型号含 OpenWrt/ImmortalWrt/LuCI")
        }
        if (http.serverHeader?.contains("luci", ignoreCase = true) == true) {
            signals += BrandSignal(RouterBrand.OPENWRT, "Server 头含 luci")
        }
        if (url.contains("/cgi-bin/luci", ignoreCase = true)) {
            signals += BrandSignal(RouterBrand.OPENWRT, "登录入口 URL 含 /cgi-bin/luci")
        }

        // ---- 汇总判定 ----
        if (signals.isEmpty()) {
            return RouterProbeResult(
                brand = RouterBrand.GENERIC,
                modelName = upnp.modelName,
                confidence = "低",
                gatewayIp = gw,
                adminUrl = http.finalUrl ?: "http://$gw/",
                signals = emptyList(),
                note = "未命中已知品牌特征(HTTP 与 UPnP 均无匹配),无法自动登录,请手动打开管理页确认品牌"
            )
        }

        // 命中多个取置信度最高;同置信度取命中信号数多者
        val best = signals.groupBy { it.brand }
            .maxWithOrNull(
                compareBy<Map.Entry<RouterBrand, List<BrandSignal>>> { confidenceRank(it.value) }
                    .thenBy { it.value.size }
            )!!

        val adminUrl = http.finalUrl ?: "http://$gw/"
        return RouterProbeResult(
            brand = best.key,
            modelName = upnp.modelName,
            confidence = confidenceText(best.value),
            gatewayIp = gw,
            adminUrl = adminUrl,
            signals = best.value.map { it.desc },
            note = "识别基于公开的 HTTP/UPnP 特征;登录页/状态页路径为候选模板,首次使用建议现场校准"
        )
    }

    /** 置信度排序权重:高=3 / 中=2 / 低=1 */
    private fun confidenceRank(signals: List<BrandSignal>): Int = when {
        signals.size >= 3 -> 3
        signals.size >= 1 -> if (signals.all { it.weak }) 1 else 2
        else -> 1
    }

    /** 置信度文本:特征≥3 高、≥1 中、仅默认 IP 命中低 */
    private fun confidenceText(signals: List<BrandSignal>): String = when (confidenceRank(signals)) {
        3 -> "高"
        2 -> "中"
        else -> "低"
    }
}
