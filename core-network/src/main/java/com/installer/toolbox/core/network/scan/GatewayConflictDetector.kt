package com.installer.toolbox.core.network.scan

import com.installer.toolbox.core.network.discovery.HostInfo

/**
 * 网关 / IP 冲突检测器。
 *
 * 两个检测维度（与 [ArpSpoofDetector] 的"一 IP 多 MAC"互补）：
 * 1. **一 MAC 多 IP**：同一设备（MAC）出现在多个 IP——多为设备多接口/多 IP 配置，
 *    也可能是 IP 冲突（DHCP 重复分配）；装维现场常见于"多路由器乱接"或摄像头双网段。
 * 2. **多网关/网关冲突**：局域网内多个设备开放路由器特征端口（53 DNS / 67 DHCP），
 *    说明可能接了两个路由器（如光猫路由模式 + 自接路由器，或客户自接路由），
 *    会导致 DHCP 冲突、上网不稳、跨网段互访异常——装维排查"网不好"的常见根因。
 *
 * 判定（启发式，仅供参考）：疑似网关 = 开放 53 或 67 端口的设备；
 * 疑似网关数量 > 1（不含本机）即提示可能双路由/网关冲突。
 */
object GatewayConflictDetector {

    /** 路由器特征端口：53 DNS / 67 DHCP / 1900 UPnP（80/443 太泛不用于判定） */
    private val GATEWAY_PORTS = setOf(53, 67, 1900)

    /** 一 MAC 对应多个 IP */
    data class MacMultiIp(
        val mac: String,
        val ips: List<String>,
        val vendor: String?
    )

    /** 疑似网关设备 */
    data class GatewayCandidate(
        val ip: String,
        val mac: String?,
        val vendor: String?,
        /** 命中的路由器特征端口 */
        val gatewayPorts: List<Int>
    )

    /** 检测结果 */
    data class ConflictResult(
        /** 一 MAC 多 IP 的设备 */
        val macMultiIps: List<MacMultiIp>,
        /** 疑似网关列表（含已知网关，按是否等于已知网关排序在前） */
        val gateways: List<GatewayCandidate>,
        /** 当前 Wi-Fi 已知网关（DHCP 分配）；未知为 null */
        val knownGateway: String?,
        /** 网关冲突提示（疑似网关 >1 时非 null） */
        val conflictNote: String?,
        val note: String?
    )

    /**
     * 基于设备发现结果做冲突检测。
     *
     * @param devices 在线设备列表（[HostInfo]，含 MAC/厂商/开放端口）
     * @param knownGateway 当前 Wi-Fi 网关 IP（DHCP 分配）；未知传 null
     * @param selfIp 本机 IP（过滤掉本机自身，避免把手机当网关候选）
     */
    fun detect(
        devices: List<HostInfo>,
        knownGateway: String?,
        selfIp: String? = null
    ): ConflictResult {
        // 1) 一 MAC 多 IP：按 MAC 分组，同一 MAC 对应 ≥2 个 IP 且 MAC 有效
        val macMultiIps = devices
            .filter { !it.mac.isNullOrBlank() }
            .groupBy { it.mac!! }
            .mapNotNull { (mac, hosts) ->
                val ips = hosts.map { it.ip }.distinct()
                if (ips.size >= 2) MacMultiIp(mac, ips, hosts.firstOrNull { it.vendor != null }?.vendor) else null
            }
            .sortedByDescending { it.ips.size }

        // 2) 疑似网关：开放路由器特征端口（53/67/1900）的设备，排除本机
        val gateways = devices
            .filter { it.ip != selfIp }
            .mapNotNull { host ->
                val hit = host.openPorts.filter { it in GATEWAY_PORTS }
                if (hit.isNotEmpty()) {
                    GatewayCandidate(host.ip, host.mac, host.vendor, hit)
                } else {
                    null
                }
            }
            .sortedBy { it.ip }

        // 3) 网关冲突判定：疑似网关 > 1 个（其中一个是已知网关 + 还有别的 → 双路由）
        val conflictNote = when {
            gateways.size <= 1 -> null
            knownGateway != null && gateways.any { it.ip == knownGateway } -> {
                val extra = gateways.filter { it.ip != knownGateway }
                "⚠ 检测到 ${gateways.size} 个疑似网关（当前网关 $knownGateway 之外还有 ${extra.size} 个设备开路由器端口）——" +
                    "可能接了两个路由器/光猫路由模式，会导致 IP 冲突、上网不稳，建议逐台检查并关闭多余路由器的 DHCP。"
            }
            else -> {
                "⚠ 检测到 ${gateways.size} 个疑似网关（均非当前已知网关）——可能存在多路由/网关配置异常，建议排查。"
            }
        }

        val note = buildString {
            append("判定说明：疑似网关 = 开放 53(DNS)/67(DHCP)/1900(UPnP) 端口的设备；")
            append("一 MAC 多 IP = 同一设备出现在多个 IP（多为双网段/多接口，也可能是 IP 冲突）。")
            append("以上为启发式判定，仅供参考；最终以登录路由器查看 DHCP 客户端列表为准。")
        }
        return ConflictResult(macMultiIps, gateways, knownGateway, conflictNote, note)
    }
}
