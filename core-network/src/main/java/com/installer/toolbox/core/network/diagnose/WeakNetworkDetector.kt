package com.installer.toolbox.core.network.diagnose

import com.installer.toolbox.core.network.util.IpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.system.measureTimeMillis

/**
 * 弱网检测器：持续探测目标网络稳定性（丢包率/时延/抖动 jitter）。
 *
 * 探测方式按 root 能力自动选择：
 * - **有 root**（rootRun 非空）：走系统 `ping` 命令（真 ICMP，丢包/时延准确，能测网关）；
 * - **无 root**：回退 TCP connect 计时（与 PingTool 同一方案），探测 80/443 任一端口，
 *   避免路由器网关只开 80 被误判为"网络弱"。
 *
 * 统计指标：
 * - 丢包率（单次超时/连接失败记为丢包）；
 * - 时延 min/avg/max（成功样本）；
 * - 抖动 jitter（相邻成功样本 RTT 差绝对值的平均，反映时延波动/卡顿）。
 *
 * 判定阈值（启发式，可微调）：
 * - 弱：丢包 >10% 或 jitter >80ms 或 avg >300ms（明显不稳/不可用）；
 * - 一般：丢包 >3% 或 jitter >40ms 或 avg >150ms（轻度劣化）；
 * - 良好：其余情况。
 */
object WeakNetworkDetector {

    /** TCP 单端口探测超时（毫秒），超时判定为丢包 */
    private const val TIMEOUT_MS = 1000L

    /** TCP 探测端口：80 优先（网关/设备管理页），443 兜底（公网主机） */
    private val PROBE_PORTS = listOf(80, 443)

    /** 弱网检测结果 */
    data class WeakNetResult(
        val target: String,
        /** 持续探测时长（秒，已 coerceIn 3-60） */
        val durationSec: Int,
        /** 探测间隔（毫秒） */
        val intervalMs: Long,
        /** 发送次数 */
        val sent: Int,
        /** 成功次数 */
        val received: Int,
        /** 丢包率（0-100，保留 1 位小数） */
        val lossPercent: Double,
        /** 平均时延（毫秒）；无成功样本为 null */
        val avgMs: Long?,
        /** 最小时延（毫秒） */
        val minMs: Long?,
        /** 最大时延（毫秒） */
        val maxMs: Long?,
        /** 抖动（毫秒）：相邻成功样本 RTT 差绝对值均值；成功样本 <2 为 null */
        val jitterMs: Long?,
        /** 每次探测 RTT（毫秒），超时/失败为 null（供 UI 展示） */
        val samples: List<Long?>,
        /** 结论：良好 / 一般 / 弱 / 参数错误 */
        val verdict: String,
        /** 一句话结论 */
        val summary: String,
        /** 探测方式：ICMP（root ping）/ TCP（无 root 回退） */
        val probeMode: String
    )

    /**
     * 执行弱网检测。
     *
     * @param target 目标 IP 或域名（空/非法返回 verdict="参数错误" 的结果，不抛异常）
     * @param durationSec 持续探测时长（秒），coerceIn(3, 60)
     * @param intervalMs 探测间隔（毫秒），coerceIn(200, 5000)
     * @param rootRun root 命令通道（如 RootShell::run）；非空时优先用系统 ping（ICMP），
     *                ping 不可用（su 失败/无 ping 命令）自动回退 TCP
     */
    suspend fun detect(
        target: String,
        durationSec: Int = 10,
        intervalMs: Long = 500,
        rootRun: ((String) -> String)? = null
    ): WeakNetResult = withContext(Dispatchers.IO) {
        val t = target.trim()
        if (t.isEmpty() || !isValidTarget(t)) {
            return@withContext errorResult(t, "参数错误：目标格式非法，请输入合法 IP 或域名")
        }
        val sec = durationSec.coerceIn(3, 60)
        val interval = intervalMs.coerceIn(200, 5000)
        // 探测次数 = 时长 / 间隔
        val count = (sec * 1000L / interval).toInt().coerceAtLeast(1)

        // 有 root：优先系统 ping（真 ICMP）
        if (rootRun != null) {
            val ping = runCatching { pingOnce(rootRun, t, count, interval) }.getOrNull()
            if (ping != null && ping.usable) {
                if (ping.rtts.isNotEmpty()) {
                    return@withContext buildResult(t, sec, interval, count, ping.rtts, "ICMP（root ping）")
                }
                // ICMP 全丢包：可能是目标禁 ICMP（抖音/淘宝等 CDN 常见）→ TCP 回退验证
                val tcpFallback = tcpProbe(t, count, interval)
                if (tcpFallback.any { it != null }) {
                    // TCP 通 → 目标可达但禁 ping，改用 TCP 结果（避免误报网络弱）
                    return@withContext buildResult(t, sec, interval, count, tcpFallback, "TCP（ICMP 被禁回退）")
                }
                // TCP 也不通 → 目标真不可达，保持 ICMP 全丢包结果
                return@withContext buildResult(t, sec, interval, count, ping.rtts, "ICMP（root ping）")
            }
            // ping 不可用，回退 TCP
        }

        // 无 root 或 ping 不可用：TCP connect 探测 80/443
        buildResult(t, sec, interval, count, tcpProbe(t, count, interval), "TCP（无 root 回退）")
    }

    /** TCP 探测：依次试 80/443（probeOnce），循环 count 次，样本间 interval 间隔 */
    private suspend fun tcpProbe(host: String, count: Int, interval: Long): List<Long?> {
        val samples = mutableListOf<Long?>()
        repeat(count) {
            val elapsed = try {
                withTimeout(TIMEOUT_MS * PROBE_PORTS.size + 500) {
                    probeOnce(host)
                }
            } catch (e: Exception) {
                null // 全部端口超时或连接失败 → 丢包
            }
            samples.add(elapsed)
            if (it < count - 1) delay(interval) // 样本间间隔
        }
        return samples
    }

    /** 由样本列表统一构造结果（统计 + 判定 + 文案） */
    private fun buildResult(
        t: String,
        sec: Int,
        interval: Long,
        count: Int,
        samples: List<Long?>,
        mode: String
    ): WeakNetResult {
        val ok = samples.filterNotNull()
        val loss = "%.1f".format((count - ok.size) * 100.0 / count).toDouble()
        val avg = if (ok.isEmpty()) null else ok.sum() / ok.size
        val min = ok.minOrNull()
        val max = ok.maxOrNull()
        val jitter = if (ok.size < 2) null else {
            var sum = 0L
            for (i in 1 until ok.size) sum += abs(ok[i] - ok[i - 1])
            sum / (ok.size - 1)
        }

        // 判定：弱 > 一般 > 良好（null 的 avg/jitter 按最大处理，丢包 100% 必进"弱"）
        val verdict = when {
            loss > 10.0 || (jitter ?: Long.MAX_VALUE) > 80 || (avg ?: Long.MAX_VALUE) > 300 -> "弱"
            loss > 3.0 || (jitter ?: Long.MAX_VALUE) > 40 || (avg ?: Long.MAX_VALUE) > 150 -> "一般"
            else -> "良好"
        }
        val summary = buildString {
            append("丢包 ${"%.1f".format(loss)}%、")
            append("平均 ${avg ?: "-"}ms、")
            append("抖动 ${jitter ?: "-"}ms，网络$verdict")
            if (ok.isEmpty()) {
                append(
                    if (mode.startsWith("ICMP")) "（目标无响应：可能不在线或禁 ping）"
                    else "（目标不可达或 80/443 端口均不通，可能目标未开这些端口）"
                )
            }
        }

        return WeakNetResult(t, sec, interval, count, ok.size, loss, avg, min, max, jitter, samples, verdict, summary, mode)
    }

    /**
     * 用系统 ping 命令探测（root）。解析每行 time= 提取 RTT 样本。
     * @return usable=false 表示 ping 命令不可用（su 失败/输出无效），调用方应回退 TCP
     */
    private fun pingOnce(rootRun: (String) -> String, host: String, count: Int, intervalMs: Long): PingOutcome {
        // -i 间隔（秒，root 下可 <1s；toybox 下限 0.2）；-W 单次超时秒；-c 次数
        val intervalSec = (intervalMs / 1000.0).coerceAtLeast(0.2)
        val cmd = "ping -c $count -i $intervalSec -W 1 $host"
        val out = rootRun(cmd).orEmpty()
        // 有效性：ping 头或统计行出现才算可用（命令不存在/无权限时输出为空或错误文本）
        if (out.isBlank() || (!out.contains("packets transmitted") && !out.contains("icmp_seq"))) {
            return PingOutcome(usable = false, rtts = emptyList())
        }
        // 解析 time=xx.x ms → RTT 样本（毫秒取整）；全丢包时无 time= 行，样本为空
        val rtts = Regex("""time=([\d.]+) ms""").findAll(out)
            .map { it.groupValues[1].toDouble().toLong() }
            .toList()
        return PingOutcome(usable = true, rtts = rtts)
    }

    /** ping 探测结果：usable=false 表示命令不可用需回退；rtts 为成功样本 RTT（丢包由 count-rtts.size 推断） */
    private data class PingOutcome(val usable: Boolean, val rtts: List<Long>)

    /** TCP 单次探测：依次试 80/443，任一端口连通即返回其 RTT（毫秒）；全部失败抛异常（由外层记为丢包） */
    private fun probeOnce(host: String): Long {
        for (p in PROBE_PORTS) {
            try {
                return measureTimeMillis {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, p), TIMEOUT_MS.toInt())
                    }
                }
            } catch (e: Exception) {
                // 当前端口不通，试下一个
            }
        }
        throw java.net.ConnectException("所有探测端口均不通")
    }

    /** 目标合法性：合法 IPv4（IpUtil 严格校验）或域名（字母数字/点/中划线，不含空格） */
    private fun isValidTarget(t: String): Boolean {
        if (IpUtil.isValidIp(t)) return true
        return t.length in 2..253 && t.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    /** 参数错误结果（不抛异常，返回可读错误文本，由调用方展示） */
    private fun errorResult(target: String, message: String) = WeakNetResult(
        target = target,
        durationSec = 0,
        intervalMs = 0,
        sent = 0,
        received = 0,
        lossPercent = 100.0,
        avgMs = null,
        minMs = null,
        maxMs = null,
        jitterMs = null,
        samples = emptyList(),
        verdict = "参数错误",
        summary = message,
        probeMode = "-"
    )
}

/** 主流平台目标（装维现场常用：测某平台网络质量） */
data class PlatformTarget(val name: String, val host: String)

/** 预置主流平台列表（10 个，官方主域名） */
val PLATFORMS: List<PlatformTarget> = listOf(
    PlatformTarget("抖音", "www.douyin.com"),
    PlatformTarget("微信", "weixin.qq.com"),
    PlatformTarget("B站", "www.bilibili.com"),
    PlatformTarget("淘宝", "www.taobao.com"),
    PlatformTarget("京东", "www.jd.com"),
    PlatformTarget("王者荣耀", "pvp.qq.com"),
    PlatformTarget("腾讯视频", "v.qq.com"),
    PlatformTarget("爱奇艺", "www.iqiyi.com"),
    PlatformTarget("快手", "www.kuaishou.com"),
    PlatformTarget("微博", "weibo.com")
)

/** 按平台名匹配（精确，供 AI 工具 platform 参数用）；找不到返回 null */
fun resolvePlatform(name: String): PlatformTarget? =
    PLATFORMS.firstOrNull { it.name == name.trim() }
