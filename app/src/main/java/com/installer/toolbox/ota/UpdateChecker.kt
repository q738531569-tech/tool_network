package com.installer.toolbox.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * 更新检查器：GET {baseUrl}/version.json 拉取远端更新信息。
 *
 * 约定：
 * - baseUrl 为更新源根地址（默认 http://192.168.10.1/ota/，设置页可改）
 * - version.json 解析见 [parseUpdateInfo]
 * - 网络层用 HttpURLConnection 直连（Proxy.NO_PROXY 绕过系统代理残留）+ 短超时
 *   （检查 5s，局域网内应秒回）。注：app 模块无法直接引用 okhttp（core-*
 *   模块均为 implementation 不传递），故未使用 OkHttp，行为等价。
 */
object UpdateChecker {

    /** 检查超时：连接 / 读取各 5 秒（version.json 很小） */
    private const val CHECK_TIMEOUT_MS = 5_000

    /**
     * 检查更新：GET {baseUrl}/version.json，成功且 latestVersion 非空返回 UpdateInfo。
     *
     * 不抛异常：网络异常 / JSON 错误 / 未配置更新源均返回 Result.failure，
     * failure 的异常 message 为中文提示，可直接用于界面展示。
     */
    suspend fun checkUpdate(baseUrl: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置更新源 URL"))
        }
        try {
            // 拼接 version.json 地址（容错 baseUrl 尾部是否带 /）
            val url = if (baseUrl.endsWith("/")) "${baseUrl}version.json" else "$baseUrl/version.json"
            // 打开连接：NO_PROXY 直连（绕过系统代理残留）+ 短超时
            val conn = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CHECK_TIMEOUT_MS
                readTimeout = CHECK_TIMEOUT_MS
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext Result.failure(
                        IllegalStateException("检查更新失败（HTTP $code）")
                    )
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val info = parseUpdateInfo(body)
                if (info == null) {
                    return@withContext Result.failure(
                        IllegalStateException("更新信息解析失败（version.json 格式不正确）")
                    )
                }
                Result.success(info)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // 网络不通、超时、DNS 失败等统一转失败结果
            Result.failure(
                IllegalStateException("网络失败：${e.message ?: e.javaClass.simpleName}")
            )
        }
    }

    /**
     * 版本号比较：latest 高于 current 返回 true。
     *
     * 按 "." 分段逐段比较数字（如 0.1.0 vs 0.1.1 → true）；
     * 段数不同的按缺失段补 0（如 1.2 vs 1.2.1 → true）；
     * 非数字段按 0 参与比较，容忍 "v1.0" 之类前缀。
     */
    fun isNewer(current: String, latest: String): Boolean {
        if (current.isBlank() || latest.isBlank()) return false
        val a = current.split(".").map { it.toIntOrNull() ?: 0 }
        val b = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val maxSeg = maxOf(a.size, b.size)
        for (i in 0 until maxSeg) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y > x) return true
            if (y < x) return false
        }
        return false // 完全相等：不是新版本
    }
}
