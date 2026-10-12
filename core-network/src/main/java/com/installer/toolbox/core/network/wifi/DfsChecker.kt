package com.installer.toolbox.core.network.wifi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 航空信号检测（DFS 信道风险检测）。
 *
 * 背景：5GHz Wi-Fi 的 **DFS 信道**（UNII-2 52-64、UNII-2e 100-140）与航空雷达共用频段。
 * 按法规，使用 DFS 信道的 AP 必须做雷达检测（Radar Detection），一旦检测到雷达信号
 * （飞机经过时机载雷达/地面航管雷达发射）就**自动切换信道并静默 60 秒**——用户表现为
 * "飞机经过就断网"。这是装维现场常见投诉。
 *
 * 本检测：扫描周边 5GHz AP，判定哪些在用 DFS 信道、当前连接是否 DFS，给出换信道建议
 * （非 DFS：36/40/44/48、149/153/157/161/165）。手机无法直接"听"雷达信号（系统不暴露
 * 该能力），但 DFS 信道占用情况已能定位"飞机经过断网"的根因。
 */
object DfsChecker {

    /** 5GHz DFS 信道集合（UNII-2 + UNII-2e，52-64 与 100-140） */
    private val DFS_CHANNELS = (52..64) + (100..140)

    /** 非 DFS 5G 推荐信道（UNII-1 36-48 + UNII-3 149-165，无雷达避让） */
    val NON_DFS_CHANNELS = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)

    /** 是否为 DFS 信道 */
    fun isDfsChannel(channel: Int): Boolean = channel in DFS_CHANNELS

    /** 是否为 DFS 频率（MHz：5260-5320、5500-5700） */
    fun isDfsFrequency(freqMhz: Int): Boolean = freqMhz in 5260..5320 || freqMhz in 5500..5700

    /** 检测报告 */
    data class DfsReport(
        /** 当前连接信道（未连接 5G 时为 null） */
        val connectedChannel: Int?,
        /** 当前连接是否为 DFS 信道 */
        val connectedIsDfs: Boolean,
        /** 周边 AP 总数（全部频段） */
        val totalApCount: Int,
        /** 周边 2.4G AP 数 */
        val count2_4G: Int,
        /** 周边使用 DFS 信道的 5G AP（按信号排序） */
        val dfsAps: List<WifiAnalyzer.ApInfo>,
        /** 周边使用非 DFS 信道的 5G AP（按信号排序） */
        val nonDfsAps: List<WifiAnalyzer.ApInfo>,
        /** 结论文案（供 UI 直接展示） */
        val summary: String
    )

    /**
     * 执行检测：扫描周边 AP + 读取当前连接，分析 DFS 信道占用。
     * 需定位权限（未授权抛 [IllegalStateException]）。
     * 注意：DFS 只存在于 5GHz（UNII-2/UNII-2e），本检测仅统计 5G AP。
     */
    suspend fun check(context: Context): DfsReport = withContext(Dispatchers.IO) {
        val aps = WifiAnalyzer.scanAround(context)
        val dfs = aps.filter { it.band() == "5G" && isDfsChannel(it.channel) }
        val nonDfs = aps.filter { it.band() == "5G" && !isDfsChannel(it.channel) }
        val count2_4G = aps.count { it.band() == "2.4G" }
        val conn = WifiAnalyzer.currentConnection(context)
        val connChannel = conn?.channel?.takeIf { conn.band == "5G" && it > 0 }
        val connDfs = connChannel != null && isDfsChannel(connChannel)

        val summary = buildString {
            if (connDfs) {
                append("⚠ 当前连接的 5G 信道 CH$connChannel 为 DFS 信道（UNII-2/UNII-2e，与航空雷达共用频谱）：按法规，使用 DFS 信道的 AP 须持续监测雷达信号，检测到即自动切换信道并进入约 60 秒静默期（Channel Availability Check），期间网络中断。建议登录路由器将 5G 信道固定为非 DFS 信道：${NON_DFS_CHANNELS.joinToString("/")}（推荐选用其中周边干扰最少者）。")
            } else if (connChannel != null) {
                append("✓ 当前 5G 信道 CH$connChannel 为非 DFS 信道，不受雷达避让（DFS）机制影响；若仍存在间歇性断网，建议进一步排查 2.4G 频段干扰或路由器自身故障。")
            } else {
                append("未连接 5G 频段（当前为 ${conn?.band ?: "未知"}）：如现场存在间歇性断网投诉，可检查路由器 5G 是否配置为 DFS 信道（见下方周边 AP 分析）。")
            }
            append("周边共 ${aps.size} 个 AP（2.4G $count2_4G 个、5G ${dfs.size + nonDfs.size} 个；DFS 仅存在于 5G），其中 DFS 信道 ${dfs.size} 个、非 DFS 信道 ${nonDfs.size} 个。")
        }

        DfsReport(connChannel, connDfs, aps.size, count2_4G, dfs.sortedByDescending { it.levelDbm }, nonDfs.sortedByDescending { it.levelDbm }, summary)
    }

    /** ApInfo 频段（复用频率判定） */
    private fun WifiAnalyzer.ApInfo.band(): String = WifiAnalyzer.bandOf(frequency)
}
