package com.installer.toolbox.core.network.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Wi-Fi 分析器：当前连接信息 + 周边 AP 扫描。
 *
 * 注意：Android 9+ 扫描周边 Wi-Fi 必须持有定位权限（ACCESS_FINE_LOCATION /
 * ACCESS_COARSE_LOCATION）且系统位置服务已开启；权限请求流程由 UI 层完成。
 */
object WifiAnalyzer {

    /** 当前连接信息 */
    data class ConnectedWifi(
        val ssid: String,
        val bssid: String,
        /** 信号强度（dBm，负值） */
        val rssiDbm: Int,
        /** 链路速率（Mbps） */
        val linkSpeedMbps: Int,
        /** 频率（MHz） */
        val frequency: Int,
        /** 频段：2.4G / 5G / 6G */
        val band: String,
        /** 信道号 */
        val channel: Int
    )

    /** 周边 AP 信息 */
    data class ApInfo(
        val ssid: String,
        val bssid: String,
        /** 信号强度（dBm，负值） */
        val levelDbm: Int,
        /** 频率（MHz） */
        val frequency: Int,
        /** 信道号 */
        val channel: Int,
        /** 加密方式：WPA3 / WPA2 / WPA / WEP / 开放 / 未知 */
        val security: String
    )

    private fun wifiManager(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** 是否已授予 Wi-Fi 扫描所需的定位权限 */
    fun hasScanPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** 获取当前连接信息；未连接 Wi-Fi 或读取失败时返回 null */
    fun currentConnection(context: Context): ConnectedWifi? {
        return try {
            val info = wifiManager(context).connectionInfo ?: return null
            val ssid = info.ssid?.trim('"') ?: "未知"
            val bssid = info.bssid ?: "未知"
            if (ssid == "未知" && bssid == "未知") return null
            ConnectedWifi(
                ssid = ssid,
                bssid = bssid,
                rssiDbm = info.rssi,
                linkSpeedMbps = info.linkSpeed,
                frequency = info.frequency,
                band = bandOf(info.frequency),
                channel = channelOf(info.frequency)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 扫描周边 AP（原始 ScanResult，供 Wi-Fi RTT 测距等需要原始数据的场景）。
     * 需要定位权限；未授权抛出 [IllegalStateException]。
     * ACCESS_WIFI_STATE 为 normal 权限（Manifest 声明即授予），定位权限已在入口
     * hasScanPermission() 检查并提前 throw——静态分析跨函数识别不到，函数级抑制。
     */
    @SuppressLint("MissingPermission")
    suspend fun scanRaw(context: Context): List<ScanResult> = withContext(Dispatchers.IO) {
        if (!hasScanPermission(context)) {
            throw IllegalStateException("缺少定位权限，无法扫描周边 Wi-Fi")
        }
        val wifi = wifiManager(context)
        try {
            wifi.startScan()
        } catch (e: Exception) {
            // 扫描过于频繁等场景：忽略异常，直接读取已有结果
        }
        var results: List<ScanResult> = emptyList()
        for (i in 0 until 20) {
            delay(500)
            val current = wifi.scanResults
            if (current.isNotEmpty()) {
                results = current
                break
            }
        }
        results
            .distinctBy { result -> resultText(result, "BSSID", "bssid") }
            .sortedByDescending { result -> result.level }
    }

    /**
     * 扫描周边 AP。
     * 需要定位权限；未授权抛出 [IllegalStateException]，由调用方提示用户授权。
     */
    suspend fun scanAround(context: Context): List<ApInfo> = withContext(Dispatchers.IO) {
        scanRaw(context).map { result ->
            ApInfo(
                ssid = resultText(result, "SSID", "ssid").ifBlank { "（隐藏网络）" },
                bssid = resultText(result, "BSSID", "bssid"),
                levelDbm = result.level,
                frequency = result.frequency,
                channel = channelOf(result.frequency),
                security = securityOf(result.capabilities ?: "")
            )
        }
    }

    /** 信号强度 dBm 转等级描述（1-5 格） */
    fun signalLevel(rssiDbm: Int): Int = when {
        rssiDbm >= -50 -> 5
        rssiDbm >= -60 -> 4
        rssiDbm >= -70 -> 3
        rssiDbm >= -80 -> 2
        else -> 1
    }

    /** 优化建议条目：level 为重要程度（重要/建议/提示） */
    data class WifiSuggestion(
        val level: String,
        val text: String
    )

    /**
     * 生成已连接 Wi-Fi 的优化建议（信道 / 信号 / 频段 / 安全 / 漫游）。
     * @param connected 当前连接信息
     * @param aps 周边 AP 扫描结果（用于信道拥挤度、5G 可用性、同 SSID 信号对比）
     */
    fun suggestions(connected: ConnectedWifi, aps: List<ApInfo>): List<WifiSuggestion> {
        val list = mutableListOf<WifiSuggestion>()
        val sameSsid = aps.filter { it.ssid == connected.ssid && it.bssid != connected.bssid }

        // 1. 信号强度
        when {
            connected.rssiDbm < -75 -> list += WifiSuggestion("重要", "当前信号很弱（${connected.rssiDbm} dBm），网速会明显受影响：请靠近路由器、减少隔墙/金属遮挡，或给路由器换更好的摆放位置。")
            connected.rssiDbm < -65 -> list += WifiSuggestion("建议", "当前信号偏弱（${connected.rssiDbm} dBm）：靠近路由器或减少遮挡可明显提升网速和稳定性。")
            connected.rssiDbm >= -50 -> list += WifiSuggestion("提示", "当前信号很好（${connected.rssiDbm} dBm），网络质量主要取决于路由器带宽与干扰。")
        }

        // 2. 频段建议（2.4G 干扰大；同 SSID 5G 信号可用时建议切换）
        if (connected.band == "2.4G") {
            val better5g = aps.filter { it.ssid == connected.ssid && it.band() == "5G" }
                .maxByOrNull { it.levelDbm }
            if (better5g != null) {
                list += WifiSuggestion(
                    "建议",
                    "当前连的是 2.4G（信号 ${connected.rssiDbm} dBm），检测到同名的 5G 信号（${better5g.levelDbm} dBm）：5G 干扰少、速度快，建议切换连接 5G（若 5G 信号仍够用）。"
                )
            } else {
                list += WifiSuggestion("提示", "当前连的是 2.4G：2.4G 穿墙好但干扰多（蓝牙/微波炉/邻居路由器同频），若附近有同名的 5G 信号建议优先连 5G。")
            }
        } else {
            list += WifiSuggestion("提示", "当前连的是 5G/6G：速度快、干扰少；若穿墙后信号弱，可考虑改用 2.4G 保稳定。")
        }

        // 3. 信道拥挤度（2.4G 信道重叠大，重点看；5G 看同信道数量）
        val sameChannel = aps.count { it.channel == connected.channel }
        val adjacent = aps.count {
            it.channel != connected.channel &&
                (it.channel in (connected.channel - 2)..(connected.channel + 2))
        }
        if (connected.band == "2.4G" && sameChannel + adjacent >= 3) {
            val best = best2_4GChannel(aps, connected.channel)
            list += WifiSuggestion(
                "重要",
                "当前信道 ${connected.channel} 很拥挤（周边 ${sameChannel} 个同信道 + ${adjacent} 个相邻信道 AP）：同频干扰会明显拖慢网速。建议登录路由器把 2.4G 信道改为 ${best}（1/6/11 三选一中干扰最少的），或在路由器开启『自动优选信道』。"
            )
        } else if (connected.band != "2.4G" && sameChannel >= 3) {
            val best = best5GChannel(aps, connected.channel)
            list += WifiSuggestion(
                "建议",
                "当前信道 ${connected.channel} 有 ${sameChannel} 个周边 AP 同频：建议登录路由器把 5G 信道改为 ${best}（常见非 DFS 信道 36/40/44/48/149/153/157/161/165 中周边干扰最少的一个）。"
            )
        }

        // 4. 安全加密
        if (connected.ssid != "未知") {
            val ap = aps.firstOrNull { it.ssid == connected.ssid }
            if (ap != null && (ap.security == "开放" || ap.security == "WEP")) {
                list += WifiSuggestion("重要", "当前 Wi-Fi 加密为「${ap.security}」：任何人都能窃听流量，建议登录路由器开启 WPA2/WPA3 加密。")
            }
        }

        // 5. 多 AP 漫游（同 SSID 其他 AP 信号更好）
        val strongerAp = sameSsid.maxByOrNull { it.levelDbm }
        if (strongerAp != null && strongerAp.levelDbm > connected.rssiDbm + 10) {
            list += WifiSuggestion(
                "建议",
                "附近有同名的另一个 AP（${strongerAp.levelDbm} dBm，比当前强 ${strongerAp.levelDbm - connected.rssiDbm} dB）：手机可能没自动漫游，可断开重连让它连到信号更好的那个。"
            )
        }

        return list
    }

    /** 2.4G 推荐信道：在 1/6/11 中选周边干扰（同信道+相邻 2 信道）最少的一个 */
    private fun best2_4GChannel(aps: List<ApInfo>, current: Int): Int {
        fun score(ch: Int): Int = aps.count { it.band() == "2.4G" && it.channel in (ch - 2)..(ch + 2) }
        return listOf(1, 6, 11).minByOrNull { score(it) } ?: current
    }

    /** 5G 推荐信道：常见非 DFS 信道（36/40/44/48/149/153/157/161/165）中周边同频最少的一个 */
    private fun best5GChannel(aps: List<ApInfo>, current: Int): Int {
        val candidates = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
        fun score(ch: Int): Int = aps.count { it.band() == "5G" && it.channel == ch }
        return candidates.minByOrNull { score(it) } ?: current
    }

    /** ApInfo 频段（复用频率判定） */
    private fun ApInfo.band(): String = bandOf(frequency)

    /** 频率判定频段 */
    fun bandOf(frequency: Int): String = when {
        frequency <= 0 -> "未知"
        frequency < 3000 -> "2.4G"
        frequency < 5900 -> "5G"
        else -> "6G"
    }

    /** 频率换算信道号；无法换算返回 0 */
    fun channelOf(frequency: Int): Int = when {
        frequency in 2412..2484 -> (frequency - 2407) / 5
        frequency in 5170..5885 -> (frequency - 5000) / 5
        else -> 0
    }

    /** 从 capabilities 字符串推断加密方式 */
    fun securityOf(capabilities: String): String = when {
        capabilities.contains("WPA3", ignoreCase = true) -> "WPA3"
        capabilities.contains("WPA2", ignoreCase = true) -> "WPA2"
        capabilities.contains("WPA", ignoreCase = true) -> "WPA"
        capabilities.contains("WEP", ignoreCase = true) -> "WEP"
        capabilities.contains("OPEN", ignoreCase = true) -> "开放"
        else -> "未知"
    }

    /**
     * 兼容读取 ScanResult 的 SSID/BSSID 字段。
     * Android 14（API 34）把小写 ssid/bssid 改名为大写 SSID/BSSID，
     * 旧版本无新字段名。按优先级逐个尝试，兼容 minSdk 26 起的全部版本。
     */
    private fun resultText(result: ScanResult, vararg fieldNames: String): String {
        for (name in fieldNames) {
            try {
                val value = ScanResult::class.java.getField(name).get(result)
                if (value is String && value.isNotBlank()) return value
            } catch (_: Exception) {
                // 当前版本无此字段名，尝试下一个
            }
        }
        return ""
    }
}
