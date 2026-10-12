package com.installer.toolbox.util

import android.content.Context

/**
 * 扫描/检测结果本地缓存（SharedPreferences 存 JSON 文本）。
 * 用途：弱网现场/离线回看上次设备清单、PCDN 检测结果，不必重新扫描。
 */
object ResultCache {

    private const val PREFS = "result_cache"

    /** 设备发现结果缓存 key */
    const val KEY_DEVICES = "devices"
    /** PCDN 检测结果缓存 key */
    const val KEY_PCDN = "pcdn"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存结果 JSON + 时间戳 */
    fun save(context: Context, key: String, json: String) {
        prefs(context).edit()
            .putString("${key}_data", json)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

    /** 读取结果 JSON；无缓存返回 null */
    fun load(context: Context, key: String): String? =
        prefs(context).getString("${key}_data", null)

    /** 缓存时间（毫秒）；无缓存返回 0 */
    fun time(context: Context, key: String): Long =
        prefs(context).getLong("${key}_time", 0L)

    /** 是否有缓存 */
    fun has(context: Context, key: String): Boolean = load(context, key) != null

    /** 格式化缓存时间（MM-dd HH:mm） */
    fun timeText(context: Context, key: String): String {
        val t = time(context, key)
        if (t <= 0) return ""
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(t))
    }

    /** 清空某 key 缓存 */
    fun clear(context: Context, key: String) {
        prefs(context).edit()
            .remove("${key}_data")
            .remove("${key}_time")
            .apply()
    }
}
