package com.installer.toolbox.core.network.scan

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 全网 PCDN 一键检测器（两阶段，解决"大网逐个排查不现实"的问题）。
 *
 * 阶段1（特征扫描）：复用 [PcdnDetector.detect] 扫描全网在线主机，补扫 PCDN
 * 特征端口 + 主机名关键词，输出置信度（高/中/低）与判定依据，快速排除大部分
 * 正常设备；
 * 阶段2（流量审计）：只对可疑设备（置信度高/中优先，不足补低置信度）做
 * [TrafficAudit.audit] ARP 中间人流量审计确认（上行占比/UDP 占比/分布式 IP/
 * IDC 目标/并发连接/STUN/UPnP 映射/DNS 请求量等特征），最多并发 2 台，
 * 避免对全网逐台做耗时的 MITM 审计。
 *
 * 合规：阶段2 是主动中间人操作，必须取得设备/网络所有者授权
 * （见 [TrafficAudit.AUTH_NOTE]）；未提供 root 通道时自动跳过阶段2。
 */
object PcdnNetworkScanner {

    /** 阶段2 流量审计并发上限（同时最多 2 台，避免过多 MITM 干扰网络） */
    private const val AUDIT_CONCURRENCY = 2

    /** 单个疑似 PCDN 设备的最终结果（audit 为阶段2 流量审计，未审计为 null） */
    data class NetworkDevice(
        val ip: String,
        val mac: String?,
        val vendor: String?,
        val hostname: String?,
        /** 阶段1 置信度：高 / 中 / 低 / 正常 */
        val confidence: String,
        /** 是否可疑（true=高/中/低，false=正常在线设备） */
        val suspicious: Boolean,
        /** 在线端口（常见端口 + 特征端口） */
        val openPorts: List<Int>,
        /** 阶段1 判定依据（特征端口 / 主机名 / 多 IP / DHT / UPnP） */
        val portReasons: List<String>,
        /** 主路由 UPnP 表里该 IP 的映射条目数 */
        val upnpMappingCount: Int = 0,
        /** 阶段2 流量审计结果；未审计（无 root / 超出限额）为 null */
        val audit: TrafficAudit.AuditResult? = null,
        /** 疑似二级路由（综合判定命中 1 个即 true；UI 引导借道审计） */
        val routerLike: Boolean = false
    )

    /** 全网检测汇总结果 */
    data class NetworkScanResult(
        val devices: List<NetworkDevice>,
        /** 阶段1 全网已扫描主机数 */
        val scanned: Int,
        /** 阶段2 实际完成流量审计的设备数 */
        val audited: Int,
        /** 疑似数（检出从严）：置信度"高" 或 流量审计 pcdnLikely 的设备数 */
        val likelyCount: Int,
        val note: String?
    )

    /**
     * 全网 PCDN 一键检测（运行在 IO 线程）。
     *
     * @param context 用于取当前 Wi-Fi 网段（阶段1）
     * @param rootRun root 命令通道（如 core-agent 的 RootShell::run）；null 时跳过阶段2
     * @param perDeviceSec 阶段2 单台流量审计时长（秒）
     * @param maxAudit 阶段2 最多审计的设备数（候选按置信度高/中优先，不足补低）
     */
    suspend fun scanNetwork(
        context: Context,
        rootRun: ((String) -> String)? = null,
        perDeviceSec: Int = 15,
        maxAudit: Int = 8,
        subnetOverride: String? = null
    ): NetworkScanResult = withContext(Dispatchers.IO) {
        // 防休眠：全网扫描（特征层 20-40 秒 + 审计并发）期间持锁，灭屏/Doze 不断扫描；
        // 超时兜底 10 分钟自动释放，finally 成对释放（单台 audit 内部另有自己的短锁）
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toolbox:pcdn_scan")
        try {
            wakeLock?.acquire(10 * 60_000L)
        } catch (_: Exception) {
        }
        try {
            // ---- 阶段1：全网特征扫描（20-40 秒，PcdnDetector 默认 40 秒预算） ----
        // root 通道存在时与设备发现页一致走 ARP 模式，端口全关设备也能扫到
        val phase1 = PcdnDetector.detect(context, rootRun, subnetOverride = subnetOverride)
        // 候选排序：高 > 中 > 低 > 正常（正常设备不参与审计候选）
        val rank = mapOf("高" to 0, "中" to 1, "低" to 2, "正常" to 3)
        val candidates = phase1.devices
            .filter { it.suspicious }
            .sortedBy { rank[it.confidence] ?: 3 }
            .take(maxAudit.coerceAtLeast(1))

        // ---- 阶段2：可疑设备流量审计（并发 2 台，root 通道缺失则跳过） ----
        val runner = rootRun
        val auditMap: Map<String, TrafficAudit.AuditResult> = if (runner == null) {
            emptyMap()
        } else {
            auditCandidates(context, candidates.map { it.ip }, perDeviceSec, runner)
        }

        // 组装结果：audit 与设备按 IP 配对（全部在线设备，含正常设备）
        val devices = phase1.devices.map { d ->
            NetworkDevice(
                ip = d.ip,
                mac = d.mac,
                vendor = d.vendor,
                hostname = d.hostname,
                confidence = d.confidence,
                suspicious = d.suspicious,
                openPorts = d.openPorts,
                portReasons = d.reasons,
                upnpMappingCount = d.upnpMappingCount,
                audit = auditMap[d.ip],
                routerLike = d.routerLike
            )
        }
        val audited = auditMap.size
        // 疑似数（检出从严，漏报优先于误报）：置信度"高"（协议级证据/特征端口≥2）
        // 或流量审计 pcdnLikely 均计疑似；中/低为可疑待确认，点击卡片审计确认
        val likelyCount = devices.count { d ->
            d.confidence == "高" || (d.audit?.pcdnLikely == true)
        }

        val note = buildString {
            append("检出从严（漏报优先于误报）：先宽进列出可疑设备（特征端口/主机名/多 IP/DHT 协议/UPnP 映射），再逐台详细分析排除误报（摄像头先识别排除路由误判；摄像头同样可能内置/被植入 PCDN，协议级硬证据照样列可疑）。")
            append("②对可疑设备做 ARP 中间人流量审计确认（上行占比/UDP 占比/分布式 IP/IDC 目标/并发连接/STUN/UPnP 映射/DNS 域名等特征），总分≥4 且至少 1 个特征命中即疑似（仅供参考需人工复核）。")
            append("可疑设备可点击卡片单独流量审计确认。")
            append("预计总耗时：特征扫描约 20-40 秒 + 流量审计（每台约 ${perDeviceSec} 秒，最多 ${AUDIT_CONCURRENCY} 台并行，至多 ${maxAudit} 台）。")
            if (runner == null) {
                append("未提供 root 通道，仅完成特征扫描（阶段1），未做流量审计（阶段2）；")
                append("root 环境下重试可对可疑设备做流量确认。")
            } else {
                append("${TrafficAudit.AUTH_NOTE}。")
            }
        }
        NetworkScanResult(devices, phase1.scanned, audited, likelyCount, note)
        } finally {
            // 释放防休眠锁（成对）
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 手动单台流量审计（UI 点击设备卡片调用，无需整网重扫）。
     * @param targetIp 目标设备 IP
     * @param perDeviceSec 审计时长（秒，1-120）
     * @param rootRun root 命令通道；null 时返回 TrafficAudit 的降级说明
     */
    suspend fun auditSingle(
        context: Context,
        targetIp: String,
        perDeviceSec: Int,
        rootRun: ((String) -> String)?
    ): TrafficAudit.AuditResult = withContext(Dispatchers.IO) {
        TrafficAudit.audit(
            context = context,
            targetIp = targetIp,
            durationMs = perDeviceSec.coerceIn(1, 120) * 1000L,
            rootRun = rootRun
        )
    }

    /**
     * 对候选 IP 并发执行流量审计：Semaphore 限流（同时最多 [AUDIT_CONCURRENCY] 台），
     * 每台审计独立 async，全部完成后返回 IP → 审计结果。
     * 单台失败不中断整体（TrafficAudit 内部不抛异常，返回降级结果）。
     */
    private suspend fun auditCandidates(
        context: Context,
        ips: List<String>,
        perDeviceSec: Int,
        rootRun: ((String) -> String)
    ): Map<String, TrafficAudit.AuditResult> = coroutineScope {
        val semaphore = Semaphore(AUDIT_CONCURRENCY)
        ips.map { ip ->
            async {
                semaphore.withPermit {
                    TrafficAudit.audit(
                        context = context,
                        targetIp = ip,
                        durationMs = perDeviceSec.coerceAtLeast(1) * 1000L,
                        rootRun = rootRun
                    )
                }
            }
        }.awaitAll().associateBy { it.targetIp }
    }

    /**
     * 单台设备最终判定文案：结合阶段1 置信度与阶段2 流量审计结果。
     * - 审计命中：流量确认疑似 PCDN
     * - 审计未确认：特征可疑（流量未确认）
     * - 未审计置信度高：高度可疑（协议/特征强命中，建议点击审计确认）
     * - 未审计中/低：特征可疑（点击卡片审计确认）
     */
    fun verdict(d: NetworkDevice): String {
        val audit = d.audit
        return when {
            audit != null && audit.pcdnLikely -> "流量确认疑似 PCDN"
            audit != null && !audit.pcdnLikely -> "特征可疑（流量未确认）"
            d.confidence == "高" -> "高度可疑（协议/特征强命中，建议点击审计确认）"
            else -> "特征可疑（点击卡片审计确认）"
        }
    }
}
