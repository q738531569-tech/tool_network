package com.installer.toolbox.core.network.scan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 主动查询主路由 UPnP IGD 端口映射表（SSDP 发现 IGD → SOAP GetGenericPortMappingEntry 枚举）。
 *
 * 为什么做这个：PCDN 盒子（赚钱宝/网心云/甜糖等）必须通过 UPnP/DMZ 在路由器上
 * 映射端口才能被外网调度，且映射端口大量且随机（特征端口扫描的天然盲区）。
 * 映射表直接列出"内网 IP → 映射条目"，可用于 PCDN 特征判定：
 * - 映射描述含 PCDN 关键词（xunlei/玩客/网心等）→ 设备自报家门
 * - 映射条目 ≥5 → 大量随机端口映射（正常设备如游戏机/BT 通常只映射 1-2 条）
 *
 * 局限：藏在二级路由 NAT 后面的盒子，映射发生在二级路由上，主路由 UPnP 表不可见。
 */
object UpnpPortMapper {

    /** 单条端口映射条目 */
    data class PortMapping(
        val externalPort: Int,
        val internalClient: String,
        val internalPort: Int,
        val protocol: String,
        val description: String
    )

    /** UPnP 查询结果（mappings 空且 note 非空 = 路由器未开 UPnP 或不响应） */
    data class UpnpResult(
        val gatewayIp: String?,
        val mappings: List<PortMapping>,
        val note: String?
    )

    /** UPnP 映射描述里的 PCDN 关键词（设备自报家门，命中即为强特征） */
    val PCDN_DESC_KEYWORDS = listOf(
        "xunlei", "thunder", "wky", "玩客", "wangxin", "网心", "pcdn", "dcdn",
        "点心", "dianxin", "甜糖", "tiantang", "无线宝", "jdcloud", "赚", "bao"
    )

    /** SSDP 组播地址（UPnP 发现） */
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /** 枚举映射条目上限（防御异常路由器的死循环/超大表） */
    private const val MAX_MAPPINGS = 128

    /** 枚举终止错误码：无此条目（713 SpecifiedArrayIndexInvalid） */
    private const val ERR_NO_SUCH_ENTRY = 713

    /**
     * 查询主路由 UPnP 端口映射表（运行在 IO 线程，阻塞网络 IO 控制在 [timeoutMs] 内）。
     *
     * @param gatewayIp 已知网关 IP（root 模式由 LanInterfaceResolver 解析），
     *   非 null 时直接单播该网关，最快最稳；null 时先组播 SSDP 发现，再兜底探测 subnet 的 .1
     * @param subnet 当前网段（如 192.168.10.0/24），用于兜底推算 .1 网关
     * @param timeoutMs 总耗时上限（毫秒）
     */
    suspend fun query(
        gatewayIp: String? = null,
        subnet: String? = null,
        timeoutMs: Int = 6000
    ): UpnpResult = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(2000)
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        try {
            // 1) 发现 IGD：已知网关单播 → 组播 → subnet .1 单播兜底
            var locUrl: String? = null
            var igdIp: String? = gatewayIp
            if (gatewayIp != null) {
                locUrl = mSearch(gatewayIp, deadline)
            }
            if (locUrl == null) {
                locUrl = mSearch(SSDP_ADDR, deadline)
                igdIp = igdIp ?: locUrl?.let { hostOf(it) }
            }
            if (locUrl == null) {
                val fallback = subnetDot1(subnet) ?: return@withContext UpnpResult(
                    gatewayIp, emptyList(), "未找到 UPnP 网关（路由器未开 UPnP 或不响应 SSDP），跳过端口映射筛查"
                )
                locUrl = mSearch(fallback, deadline)
                igdIp = fallback
            }
            if (locUrl == null) {
                return@withContext UpnpResult(
                    igdIp, emptyList(), "路由器未响应 SSDP（可能未开 UPnP），跳过端口映射筛查"
                )
            }

            // 2) 解析 description.xml 找 WANIPConnection 的 controlURL
            val (descXml, baseUrl) = fetch(client, locUrl, deadline) ?: return@withContext UpnpResult(
                igdIp, emptyList(), "UPnP 描述文件获取失败，跳过端口映射筛查"
            )
            val control = findWanControlUrl(descXml, baseUrl) ?: return@withContext UpnpResult(
                igdIp, emptyList(), "未找到 WANIPConnection 服务（路由器 UPnP 配置异常），跳过端口映射筛查"
            )

            // 3) 枚举映射条目，直到 713（无此条目）或上限
            val mappings = mutableListOf<PortMapping>()
            for (index in 0 until MAX_MAPPINGS) {
                if (System.currentTimeMillis() > deadline) break
                val entry = fetchMapping(client, control, index) ?: break
                mappings += entry
            }
            UpnpResult(igdIp, mappings, null)
        } catch (e: Exception) {
            UpnpResult(gatewayIp, emptyList(), "UPnP 查询失败（${e.message ?: e.javaClass.simpleName}），跳过端口映射筛查")
        }
    }

    /** 映射描述是否含 PCDN 关键词（小写匹配） */
    fun descPcdnKeyword(description: String): String? {
        val d = description.lowercase()
        return PCDN_DESC_KEYWORDS.firstOrNull { d.contains(it) }
    }

    // ---- 内部实现 ----

    /** SSDP M-SEARCH 发现 IGD，返回 LOCATION URL；无响应返回 null */
    private fun mSearch(target: String, deadline: Long): String? {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2500
            val body = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
                "\r\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(target), SSDP_PORT))
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val resp = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val loc = LOCATION_RE.find(resp)?.groupValues?.get(1)
                    if (loc != null) return loc
                } catch (e: Exception) {
                    return null // 超时/端口不可达 → 该目标无响应
                }
            }
        }
        return null
    }

    /** HTTP GET，返回 body 与 URL 的 base（scheme://host:port） */
    private fun fetch(client: OkHttpClient, url: String, deadline: Long): Pair<String, String>? {
        if (System.currentTimeMillis() > deadline) return null
        return try {
            val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = resp.body?.string() ?: return null
            val base = url.substringBeforeLast("/")
            body to base
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 description.xml 里的 WANIPConnection（或 WANPPPConnection）controlURL。
     * 遍历全部 service 节点匹配 serviceType 前缀，兼容 IGD1/IGD2 嵌套结构。
     */
    private fun findWanControlUrl(descXml: String, baseUrl: String): String? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(descXml))
            var inService = false
            var serviceType = ""
            var controlUrl: String? = null
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "service" -> { inService = true; serviceType = ""; controlUrl = null }
                        "serviceType" -> if (inService) serviceType = parser.nextText()
                        "controlURL" -> if (inService) controlUrl = parser.nextText()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "service") {
                        if (serviceType.startsWith("urn:schemas-upnp-org:service:WANIPConnection") ||
                            serviceType.startsWith("urn:schemas-upnp-org:service:WANPPPConnection")
                        ) {
                            val ctrl = controlUrl ?: return null
                            return if (ctrl.startsWith("http")) ctrl else "$baseUrl/$ctrl"
                        }
                        inService = false
                    }
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** SOAP GetGenericPortMappingEntry；713（无此条目）或 HTTP/解析失败返回 null 终止枚举 */
    private fun fetchMapping(client: OkHttpClient, controlUrl: String, index: Int): PortMapping? {
        val serviceType = if (controlUrl.contains("WANIPConnection")) {
            "urn:schemas-upnp-org:service:WANIPConnection:1"
        } else {
            "urn:schemas-upnp-org:service:WANPPPConnection:1"
        }
        val body = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:GetGenericPortMappingEntry xmlns:u="$serviceType">
<NewPortMappingIndex>$index</NewPortMappingIndex>
</u:GetGenericPortMappingEntry></s:Body></s:Envelope>"""
        return try {
            val req = Request.Builder()
                .url(controlUrl)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"$serviceType#GetGenericPortMappingEntry\"")
                .post(body.toRequestBody("text/xml".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            val xml = resp.body?.string() ?: return null
            if (!resp.isSuccessful) return null
            // 713 错误码 = 枚举结束
            if (ERROR_CODE_RE.find(xml)?.groupValues?.get(1)?.toIntOrNull() == ERR_NO_SUCH_ENTRY) return null
            parseMapping(xml)
        } catch (e: Exception) {
            null
        }
    }

    /** 解析单条映射响应里的字段 */
    private fun parseMapping(xml: String): PortMapping? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var tag = ""
            val values = mutableMapOf<String, String>()
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> tag = parser.name
                    XmlPullParser.TEXT -> if (tag in MAPPING_FIELDS) values[tag] = values[tag].orEmpty() + parser.text
                }
                eventType = parser.next()
            }
            PortMapping(
                externalPort = values["NewExternalPort"]?.toIntOrNull() ?: return null,
                internalClient = values["NewInternalClient"] ?: return null,
                internalPort = values["NewInternalPort"]?.toIntOrNull() ?: return null,
                protocol = values["NewProtocol"].orEmpty(),
                description = values["NewPortMappingDescription"].orEmpty()
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 映射响应字段集 */
    private val MAPPING_FIELDS = setOf(
        "NewExternalPort", "NewInternalClient", "NewInternalPort", "NewProtocol", "NewPortMappingDescription"
    )

    /** SSDP 响应 LOCATION 头 */
    private val LOCATION_RE = Regex("""(?i)location:\s*(\S+)""")

    /** SOAP 错误码（713 = SpecifiedArrayIndexInvalid，枚举结束） */
    private val ERROR_CODE_RE = Regex("""<errorCode>\s*(\d+)\s*</errorCode>""")

    /** URL 里的 host:port */
    private fun hostOf(url: String): String? {
        val m = Regex("""https?://(\[[0-9a-fA-F:]+\]|[^/:]+)""").find(url) ?: return null
        return m.groupValues[1].trim('[', ']')
    }

    /** subnet（192.168.10.0/24）→ 兜底网关 192.168.10.1 */
    private fun subnetDot1(subnet: String?): String? {
        val ip = subnet?.substringBefore('/') ?: return null
        val parts = ip.split('.')
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.1"
    }
}
