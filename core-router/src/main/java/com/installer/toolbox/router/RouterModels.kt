package com.installer.toolbox.router

/**
 * 路由器品牌枚举。
 *
 * 面向装维场景:运营商定制光猫/路由器(华为 FTTR、中兴 FTTR、中兴/TP-Link 路由器、天翼网关)
 * 大多闭源、无 SSH/Telnet,但都有 Web 管理页——本模块统一走 HTTP 管理页通道。
 * label 为中文展示名。
 */
enum class RouterBrand(val label: String) {
    HUAWEI("华为"),
    ZTE("中兴"),
    TP_LINK("TP-Link"),
    TIANYI("天翼网关"),
    OPENWRT("OpenWrt"),
    GENERIC("未知")
}

/**
 * 品牌探测结果。
 *
 * @param brand 识别出的品牌(无命中为 GENERIC)
 * @param modelName 型号(主要来自 UPnP 描述,可能为 null)
 * @param confidence 置信度:高 / 中 / 低(特征≥3 为高;≥1 为中;仅默认 IP 命中为低)
 * @param gatewayIp 最终选定的网关地址
 * @param adminUrl 管理页地址(HTTP 首页探测的最终 URL,通常为登录页)
 * @param signals 命中的品牌特征描述列表(用于向用户展示"为什么这么判")
 * @param note 补充说明(如未探测到网关、路径待校准等)
 */
data class RouterProbeResult(
    val brand: RouterBrand,
    val modelName: String?,
    val confidence: String,
    val gatewayIp: String,
    val adminUrl: String?,
    val signals: List<String>,
    val note: String?
)

/**
 * 路由器运行状态(登录并抓取状态页后解析)。
 *
 * @param wanStatus WAN 连接状态:已连接 / 未连接 / 未知
 * @param connectedDevices 在线设备(终端)数,解析不到为 null
 * @param wifiSsids 页面中出现的 SSID 列表(可能为空)
 * @param extra 其他抓到的键值(如 WAN MAC、运行时间等),按页面结构扩充
 */
data class RouterStatus(
    val brand: RouterBrand,
    val model: String? = null,
    val firmware: String? = null,
    val wanStatus: String? = null,
    val wanIp: String? = null,
    val wanGateway: String? = null,
    val dnsServers: String? = null,
    val connectedDevices: Int? = null,
    val wifiSsids: List<String> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
    val note: String? = null
)

/**
 * 登录尝试结果(供门面内部流转,UI 一般只看 RouterStatus)。
 */
data class LoginResult(
    val success: Boolean,
    val sessionCookies: Map<String, String>,
    val adminUrl: String?,
    val message: String
)
