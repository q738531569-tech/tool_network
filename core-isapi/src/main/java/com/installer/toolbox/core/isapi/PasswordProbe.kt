package com.installer.toolbox.core.isapi

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 摄像头弱口令探测（非爆破）。
 *
 * 对常见弱口令组合**逐个**尝试 ONVIF / ISAPI 认证，成功即记录并停止。
 * 设计约束（用户要求"探测成功显示、非爆破"）：
 * - 口令列表覆盖装维现场常见默认口令（约 50 组：通用高频 + 海康/大华/雄迈/TP-LINK/宇视等品牌）；
 * - 每组尝试间隔 [TRY_INTERVAL_MS]，绝不高频重试、不穷举；
 * - 单组尝试有超时，网络异常跳过继续。
 */
object PasswordProbe {

    /**
     * 常见弱口令组合（按出现概率排序 + 品牌默认口令分组）。
     * 覆盖：海康/萤石、大华、雄迈/中维、TP-LINK/水星/迅捷、宇视、天地伟业、乔安等
     * 装维现场常见品牌默认账密；每组间隔 500ms，非爆破、不穷举。
     */
    private val COMMON_CREDENTIALS: List<Pair<String, String>> = listOf(
        // ---- 高频通用（装维第一梯队）----
        "admin" to "admin",
        "admin" to "12345",
        "admin" to "123456",
        "admin" to "12345678",
        "admin" to "888888",
        "admin" to "666666",
        "admin" to "1234",
        "admin" to "123",
        "admin" to "admin123",
        "admin" to "Admin123",
        "admin" to "Admin@123",
        "admin" to "123456789",
        "admin" to "abc123",
        "admin" to "password",
        "admin" to "00000000",
        "admin" to "654321",
        "root" to "root",
        "user" to "user",
        // ---- 海康 / 萤石 / 老海康----
        "admin" to "11111111",
        "admin" to "1234567",
        "admin" to "1234567890",
        "admin" to "88888888",
        "admin" to "1234abcd",
        // ---- 大华 / 乐橙----
        "admin" to "999999",
        "admin" to "abc12345",
        "admin" to "jiayuan",
        "admin" to "daHua123",
        "admin" to "123456a",
        // ---- 雄迈 / 中维世纪 / 天视通（老 NVR 常见）----
        "admin" to "1001",
        "admin" to "10001",
        "admin" to "12345a",
        "root" to "123456",
        "root" to "admin",
        "user" to "123456",
        // ---- TP-LINK / 水星 / 迅捷（安防路由器/摄像头）----
        "admin" to "tladmin",
        "admin" to "12345678a",
        "admin" to "202020",
        // ---- 宇视 / 天地伟业----
        "admin" to "1111",
        "admin" to "112233",
        "admin" to "abcd1234",
        // ---- 其他用户名（部分品牌用 supervisor/service/guest）----
        "supervisor" to "123456",
        "supervisor" to "supervisor",
        "service" to "123456",
        "guest" to "123456",
        "admin" to "1",
        "admin" to "123123",
        // ---- 空密码放最后（部分设备 ONVIF 对空密码认证放行，误报率高；只有常见口令全试过仍未中才兜底）----
        "admin" to "",
        "root" to ""
    )

    /** 每组尝试间隔（毫秒）：非爆破，留出呼吸时间 */
    private const val TRY_INTERVAL_MS = 500L

    /** 单组尝试超时（毫秒） */
    private const val TRY_TIMEOUT_MS = 4_000L

    /** 探测到的有效账密 */
    data class ProbeResult(val username: String, val password: String)

    /**
     * ONVIF 认证探测：用 GetNetworkInterfaces（轻量需认证请求）验证账密。
     * 认证失败返回 SOAP Fault（ok=false），成功返回接口列表。
     *
     * @param credentials 自定义账密列表（AI 传入品牌默认口令等）；null 用内置表
     * @param onProgress 进度回调（已完成组数, 总组数）
     * @return 首个成功的账密；全部失败返回 null
     */
    suspend fun probeOnvif(
        host: String,
        port: Int = 80,
        credentials: List<Pair<String, String>>? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ProbeResult? {
        val list = credentials ?: COMMON_CREDENTIALS
        list.forEachIndexed { index, (user, pass) ->
            onProgress(index + 1, list.size)
            val ok = try {
                withTimeoutOrNull(TRY_TIMEOUT_MS) {
                    OnvifClient.getInterfaces(host, port, user, pass).isNotEmpty()
                } ?: false
            } catch (e: Exception) {
                false
            }
            if (ok) return ProbeResult(user, pass)
            delay(TRY_INTERVAL_MS)
        }
        return null
    }

    /**
     * 海康 ISAPI 认证探测：用 deviceInfo（HTTP Digest 认证，Go 层处理）验证账密。
     * 阻塞调用，须在 IO 线程执行。
     *
     * @param credentials 自定义账密列表（AI 传入品牌默认口令等）；null 用内置表
     * @param onProgress 进度回调（已完成组数, 总组数）
     * @return 首个成功的账密；全部失败返回 null
     */
    fun probeHik(
        host: String,
        port: Long = 80,
        credentials: List<Pair<String, String>>? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ProbeResult? {
        val list = credentials ?: COMMON_CREDENTIALS
        list.forEachIndexed { index, (user, pass) ->
            onProgress(index + 1, list.size)
            val r = try {
                HikvisionApi.deviceInfo(host, port, user, pass)
            } catch (e: Exception) {
                HikvisionApi.HikResult(false, e.message, null)
            }
            if (r.isSuccess) return ProbeResult(user, pass)
            try {
                Thread.sleep(TRY_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return null
    }

    /** 总组数（UI/工具显示用） */
    val credentialCount: Int get() = COMMON_CREDENTIALS.size
}
