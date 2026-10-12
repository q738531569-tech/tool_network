package com.installer.toolbox.core.agent.support

import android.content.Context
import android.os.Build
import com.installer.toolbox.common.util.UsageStats
import com.installer.toolbox.core.agent.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 反馈/自动收集上报客户端（服务器接口：your-server.example:88，POST JSON）。
 * - submitFeedback：用户主动反馈（含反馈内容 + 可选诊断信息/网络信息 + 崩溃日志）
 * - autoReport：App 启动静默自动上报（设备/版本/root/使用统计/崩溃日志，不含网络拓扑）
 * 注意：系统代理残留坑——必须 Proxy.NO_PROXY（同 OnvifClient/DeepSeekClient）。
 */
object FeedbackApi {

    private const val FEEDBACK_URL = "http://your-server.example:8090/toolbox/api/feedback.php"

    /** 与服务器 feedback.php 约定一致的简单密钥（防垃圾提交） */
    private const val APP_KEY = "toolbox-fb-2026-v1"

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 提交用户反馈（运行在 IO 线程）。
     * @return Result.success(服务器返回 JSON) / Result.failure(网络或服务错误)
     */
    suspend fun submitFeedback(
        context: Context,
        type: String,
        content: String,
        includeDiagnostic: Boolean = true,
        includeNetwork: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val json = baseJson(context).apply {
            put("type", "feedback")
            put("fbType", type)
            put("text", content.ifBlank { "（未填写具体内容）" })
            if (includeDiagnostic) {
                put(
                    "diagnostic",
                    FeedbackHelper.buildDiagnosticText(context, includeNetwork = includeNetwork, includeUsage = true)
                )
            }
        }
        post(json)
    }

    /**
     * 自动收集上报（App 启动时静默调用；失败不打扰用户）。
     * 上报内容：设备/版本/系统/root/累计启动/功能使用统计/崩溃日志（截断），
     * 不含 SSID/网关等网络拓扑信息（隐私考虑）。
     */
    suspend fun autoReport(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val json = baseJson(context).apply {
            put("type", "auto")
            put("appStarts", UsageStats.appStarts(context))
            put(
                "usage",
                JSONObject().apply {
                    UsageStats.all(context).forEach { (k, v) -> put(k, v) }
                }
            )
        }
        post(json)
    }

    /** 基础字段（反馈与自动上报共用） */
    private fun baseJson(context: Context): JSONObject {
        val ctx = context.applicationContext
        return JSONObject().apply {
            put("key", APP_KEY)
            put("app", CrashCollector.versionName(ctx))
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            put("root", RootShell.isAvailable())
            put(
                "crash",
                JSONArray().apply {
                    CrashCollector.logs(ctx).take(3).forEach { put(it.readText().take(3000)) }
                }
            )
            put("ts", System.currentTimeMillis())
        }
    }

    /** POST JSON；ok:true 视为成功 */
    private fun post(json: JSONObject): Result<String> {
        return try {
            val req = Request.Builder()
                .url(FEEDBACK_URL)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            try {
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.contains("\"ok\":true")) {
                    Result.success(body)
                } else {
                    Result.failure(IOException("服务返回异常（HTTP ${resp.code}）：${body.take(200)}"))
                }
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
