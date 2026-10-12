package com.installer.toolbox.core.network.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wi-Fi 雷达：扫描周边 AP 并映射到雷达视图（找 AP 用）。
 *
 * 距离获取（两级，不重复造轮子）：
 * 1. **Wi-Fi RTT（802.11mc FTM，Google 官方测距 API）**：对支持 FTM 的 AP 返回
 *    **真实物理距离（米）**（[WifiRttManager]）；一次最多测 8 个、整体 5 秒超时；
 * 2. 不支持 FTM 的 AP **自动退回 RSSI 估算**（信号越强越近）。
 *
 * 方位：Android 扫描只给 RSSI 不给方向，雷达图**角度按 BSSID 哈希稳定分配**
 * （同 AP 多次扫描角度不变），装维拿着手机走动时看信号强弱变化判断 AP 方位。
 *
 * 注意：Android 13+ 系统限制扫描频率（约 30 秒一次真实扫描），RSSI/RTT 变化需稍等。
 */
object WifiRadar {

    /** 雷达点：AP + 稳定角度 + 距离 */
    data class RadarPoint(
        val ap: WifiAnalyzer.ApInfo,
        /** 稳定角度（0..360，按 BSSID 哈希分配，无罗盘时的视觉布局角度） */
        val angleDeg: Float,
        /** RSSI 估算距离 0..1（1 = 信号最强/最近圆心，0 = 最弱/最远） */
        val distance: Float,
        /** Wi-Fi RTT 真实距离（米）；AP 不支持 FTM 或测距失败时为 null */
        val distanceMeters: Float?
    )

    /**
     * 信号强度 → 距离归一化。
     * RSSI -30（很强）→ 1.0（贴圆心）；-100（极弱）→ 0.05（边缘）。
     * 区间外线性截断。
     */
    fun distanceOf(rssiDbm: Int): Float {
        val normalized = (rssiDbm - (-100)).toFloat() / ((-30) - (-100)).toFloat()
        return normalized.coerceIn(0.05f, 1.0f)
    }

    /** 稳定角度：BSSID 哈希 → 0..360（同 AP 每次扫描角度一致） */
    fun angleOf(bssid: String): Float {
        val hash = bssid.fold(0) { acc, c -> acc * 31 + c.code } and 0x7fffffff
        return (hash % 3600) / 10f
    }

    /** 雷达上的可用距离描述（优先 RTT 米数，退回 RSSI 估算） */
    fun distanceText(p: RadarPoint): String = p.distanceMeters?.let { "距本机约 %.1f m（RTT）".format(it) }
        ?: when {
            p.distance >= 0.7f -> "信号强（近）"
            p.distance >= 0.4f -> "信号中"
            else -> "信号弱（远）"
        }

    /**
     * 扫描一次周边 AP 并生成雷达点（按信号从强到弱排序；前 8 强尝试 RTT 测距）。
     * 需定位权限（未授权抛 [IllegalStateException]，由 UI 层提示授权）。
     */
    suspend fun scan(context: Context): List<RadarPoint> = withContext(Dispatchers.IO) {
        val raw = WifiAnalyzer.scanRaw(context)
        // RTT 测距（系统限制一次最多 8 个 AP；不支持的 AP 返回 null，整体 5 秒超时防卡）
        val rttMap = withTimeoutOrNull(5000L) {
            raw.take(8).mapNotNull { r ->
                rttRange(context, r)?.let { bssidOf(r) to it }
            }.toMap()
        } ?: emptyMap()
        raw.map { r ->
            val ap = WifiAnalyzer.ApInfo(
                ssid = ssidOf(r).ifBlank { "（隐藏网络）" },
                bssid = bssidOf(r),
                levelDbm = r.level,
                frequency = r.frequency,
                channel = WifiAnalyzer.channelOf(r.frequency),
                security = WifiAnalyzer.securityOf(r.capabilities ?: "")
            )
            RadarPoint(
                ap = ap,
                angleDeg = angleOf(ap.bssid),
                distance = distanceOf(ap.levelDbm),
                distanceMeters = rttMap[ap.bssid]
            )
        }
    }

    /**
     * Wi-Fi RTT 测距（802.11mc FTM，API 28+）：返回 AP 到本机的真实距离（米）。
     * AP 不支持 FTM / 权限缺失 / 失败 → null（调用方退回 RSSI 估算）。
     * 权限缺失时 startRanging 抛 SecurityException 由下方 catch 兜底返回 null，
     * 语义安全——函数级抑制 MissingPermission（与 SosEngine/BleScanner 惯例一致）。
     */
    @SuppressLint("MissingPermission")
    private suspend fun rttRange(context: Context, scanResult: ScanResult): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            suspendCancellableCoroutine { cont ->
                val manager = context.applicationContext.getSystemService(WifiRttManager::class.java)
                if (manager == null || !manager.isAvailable) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val request = RangingRequest.Builder().addAccessPoints(listOf(scanResult)).build()
                manager.startRanging(
                    request,
                    Executors.newSingleThreadExecutor(),
                    object : RangingResultCallback() {
                        override fun onRangingResults(results: List<RangingResult>) {
                            val r = results.firstOrNull { it.status == RangingResult.STATUS_SUCCESS }
                            cont.resume(r?.let { it.distanceMm / 1000f })
                        }

                        override fun onRangingFailure(code: Int) {
                            cont.resume(null)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 兼容读取 ScanResult.BSSID（API 34 大写，旧版小写） */
    private fun bssidOf(result: ScanResult): String = textOf(result, "BSSID", "bssid")

    /** 兼容读取 ScanResult.SSID */
    private fun ssidOf(result: ScanResult): String = textOf(result, "SSID", "ssid")

    private fun textOf(result: ScanResult, vararg names: String): String {
        for (name in names) {
            try {
                val v = ScanResult::class.java.getField(name).get(result)
                if (v is String && v.isNotBlank()) return v
            } catch (_: Exception) {
            }
        }
        return ""
    }
}
