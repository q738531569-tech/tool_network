package com.installer.toolbox.router

import com.installer.toolbox.router.adapter.GenericAdapter
import com.installer.toolbox.router.adapter.HuaweiAdapter
import com.installer.toolbox.router.adapter.OpenWrtAdapter
import com.installer.toolbox.router.adapter.TpLinkAdapter
import com.installer.toolbox.router.adapter.ZteAdapter

/**
 * 品牌适配器接口:账密候选 + 登录/状态页路径候选 + 状态页解析。
 *
 * 诚实约束:运营商定制设备的登录页/状态页具体路径**没有真机验证**,
 * 这里全部是"候选路径列表 + 通用 HTML 关键字解析",使用前需现场校准。
 */
interface RouterAdapter {

    /** 适配器对应的品牌 */
    val brand: RouterBrand

    /** 账密候选:运营商常见默认口令(公开资料,未逐台验证)。
     * 仅作 UI 预填提示;自动尝试时不用(现在路由器都是复杂口令,默认密码已无意义) */
    fun loginCandidates(): List<Pair<String, String>>

    /** 品牌优先登录字段(如 OpenWrt 的 luci_username/luci_password),先于通用字段尝试 */
    fun preferredLoginFields(): List<Pair<String, String>> = emptyList()

    /** 登录页路径候选(相对管理地址,待真机校准) */
    fun loginPagePaths(): List<String>

    /** 状态页路径候选(相对管理地址,待真机校准) */
    fun statusPagePaths(): List<String>

    /** 从状态页 HTML 解析运行状态;解析不到返回空字段,不抛异常 */
    fun parseStatus(html: String): RouterStatus
}

/**
 * 适配器工厂:按识别出的品牌返回对应适配器。
 *
 * 天翼网关多为华为代工(e-Link 界面,登录特征与华为光猫一致),暂复用华为适配器;
 * 后续若拿到中兴代工天翼网关真机,可拆出独立适配器。
 */
object RouterAdapterFactory {

    fun forBrand(brand: RouterBrand): RouterAdapter = when (brand) {
        RouterBrand.HUAWEI -> HuaweiAdapter
        RouterBrand.ZTE -> ZteAdapter
        RouterBrand.TP_LINK -> TpLinkAdapter
        RouterBrand.TIANYI -> HuaweiAdapter // 天翼网关多为华为代工,复用华为适配器(待现场校准)
        RouterBrand.OPENWRT -> OpenWrtAdapter
        RouterBrand.GENERIC -> GenericAdapter
    }
}

/**
 * 状态页 HTML 通用关键字解析器(所有适配器复用)。
 *
 * 依据:设备状态页虽有中英文差异,但字段名如 WAN IP / 拨号状态 / 已连接 / SSID /
 * 连接数 / DNS 在大多数固件(华为 e-Link、中兴、LuCI、TP-Link)中直接以文本出现。
 * 全部为启发式正则,匹配不到返回 null,**解析失败不抛异常**。
 */
internal object HtmlStatusParser {

    /** 解析入口:整段 try/catch 兜底,任何异常都返回"全空"状态而非抛出 */
    fun parse(html: String, brand: RouterBrand): RouterStatus = try {
        val text = stripTags(html)
        val wanIp = findWanIp(text)
        val wanStatus = findWanStatus(text)
        val wanGateway = findWanGateway(text)
        val dns = findDns(text)
        val devices = findConnectedDevices(text)
        val ssids = findSsids(text)
        val model = findModel(text)
        val firmware = findFirmware(text)
        val extra = buildMap {
            findWanMac(text)?.let { put("WAN MAC", it) }
            findRunTime(text)?.let { put("运行时间", it) }
        }
        RouterStatus(
            brand = brand,
            model = model,
            firmware = firmware,
            wanStatus = wanStatus,
            wanIp = wanIp,
            wanGateway = wanGateway,
            dnsServers = dns,
            connectedDevices = devices,
            wifiSsids = ssids,
            extra = extra,
            note = if (wanIp == null && wanStatus == null && devices == null && ssids.isEmpty()) {
                "状态页已抓取但未解析出有效字段(页面结构未知,解析模板待现场校准)"
            } else {
                null
            }
        )
    } catch (e: Exception) {
        RouterStatus(brand = brand, note = "状态页解析失败(模板待现场校准):${e.message ?: e.javaClass.simpleName}")
    }

    /** 去标签纯文本(script/style 整段剔除) */
    private fun stripTags(html: String): String =
        html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** IPv4 正则 */
    private val IP4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /** WAN IP:优先"WAN IP"字段,其次 IP 地址/IPv4 字段 */
    private fun findWanIp(text: String): String? {
        Regex("(?i)WAN\\s*IP\\s*[:：]?\\s*(${IP4.pattern})").find(text)?.let {
            val ip = it.groupValues[1]
            if (isPublicLike(ip)) return ip
        }
        Regex("(?i)(?:WAN(?:\\s*IPv4)?|IP(?:\\s*地址)?)\\s*[:：]?\\s*(${IP4.pattern})").find(text)?.let {
            val ip = it.groupValues[1]
            if (isPublicLike(ip)) return ip
        }
        return null
    }

    /** 过滤私网网关地址,避免把 192.168.x.x 误当 WAN IP */
    private fun isPublicLike(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return !(a == 10 || a == 192 && b == 168 || a == 172 && b in 16..31 || a == 127)
    }

    /** WAN 连接状态:拨号状态/已连接/未连接 关键字判定 */
    private fun findWanStatus(text: String): String? {
        val hasStatusField = Regex("拨号状态|WAN\\s*Status|连接状态|Internet\\s*State", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        return when {
            Regex("拨号状态\\s*[:：]?\\s*(已连接|连接成功|Connected|Online)", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                Regex("WAN\\s*Status\\s*[:：]?\\s*(Connected|Online)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "已连接"
            Regex("拨号状态\\s*[:：]?\\s*(未连接|未拨号|断开|Disconnected|Offline)", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                Regex("WAN\\s*Status\\s*[:：]?\\s*(Disconnected|Offline)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "未连接"
            hasStatusField -> {
                when {
                    text.contains("已连接", ignoreCase = true) || text.contains("Connected", ignoreCase = true) -> "已连接"
                    text.contains("未连接", ignoreCase = true) || text.contains("Disconnected", ignoreCase = true) -> "未连接"
                    else -> "未知"
                }
            }
            text.contains("已连接", ignoreCase = true) || text.contains("Connected", ignoreCase = true) -> "已连接"
            text.contains("未连接", ignoreCase = true) || text.contains("Disconnected", ignoreCase = true) -> "未连接"
            else -> null
        }
    }

    /** 默认网关(一般为运营商侧网关地址) */
    private fun findWanGateway(text: String): String? =
        Regex("(?i)(?:默认网关|缺省网关|Gateway)\\s*[:：]?\\s*(${IP4.pattern})").find(text)?.groupValues?.get(1)

    /** DNS 服务器列表 */
    private fun findDns(text: String): String? =
        Regex("(?i)(?:DNS\\s*(?:服务器|Server)?|域名服务器)\\s*[:：]?\\s*([0-9.]+(?:\\s*[,，;]\\s*[0-9.]+)*)")
            .find(text)?.groupValues?.get(1)?.replace(Regex("\\s*[,，;]\\s*"), ", ")

    /** 在线设备/连接数 */
    private fun findConnectedDevices(text: String): Int? =
        Regex("(?:连接数|在线设备|在线终端|终端数|Hosts|Clients)\\s*[:：]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** SSID 列表:兼容 "SSID: xxx" 与 "SSID1: xxx" 两种写法 */
    private fun findSsids(text: String): List<String> =
        Regex("(?i)SSID\\s*\\d?\\s*[:：]?\\s*([^\\s,;<>]{1,32})")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.equals("on", ignoreCase = true) && !it.equals("off", ignoreCase = true) }
            .distinct()
            .toList()

    /** 设备型号 */
    private fun findModel(text: String): String? =
        Regex("(?i)(?:设备型号|产品型号|型号|Model(?:\\s*Name)?)\\s*[:：]?\\s*([A-Za-z0-9._\\-]{2,40})")
            .find(text)?.groupValues?.get(1)

    /** 固件/软件版本 */
    private fun findFirmware(text: String): String? =
        Regex("(?i)(?:软件版本|固件版本|系统版本|Firmware(?:\\s*Version)?|Software(?:\\s*Version)?|Version)\\s*[:：]?\\s*([A-Za-z0-9._\\-]{2,32})")
            .find(text)?.groupValues?.get(1)

    /** WAN MAC */
    private fun findWanMac(text: String): String? =
        Regex("(?i)(?:WAN\\s*MAC|MAC\\s*地址)\\s*[:：]?\\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})")
            .find(text)?.groupValues?.get(1)

    /** 运行时间(在线时长) */
    private fun findRunTime(text: String): String? =
        Regex("(?:运行时间|在线时长|系统运行|Uptime)\\s*[:：]?\\s*([^\\s,;<>]{1,40})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
}
