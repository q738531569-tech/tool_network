package com.installer.toolbox.core.agent.support

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 长期记忆：AI 通过 remember_info 工具主动记忆的重要信息
 * （现场网络拓扑、排查结论、用户偏好等），存 SharedPreferences（明文 JSON）。
 * 新会话自动注入 system 提示词，实现跨会话记忆。
 *
 * 安全约束（提示词层强制执行）：**禁止记忆密码、API Key、密钥等敏感凭据**。
 */
object AiMemory {

    private const val PREFS = "ai_memory"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 50

    /** 单条记忆（sessionId 关联来源会话，删除会话时一并删除） */
    data class MemoryItem(
        val text: String,
        val sessionId: String? = null,
        val ts: Long = System.currentTimeMillis()
    )

    /** 读取全部记忆（旧→新） */
    fun all(context: Context): List<MemoryItem> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val text = obj.optString("text")
                    if (text.isNotBlank()) {
                        add(MemoryItem(text, obj.optString("sessionId").ifBlank { null }, obj.optLong("ts", 0)))
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 新增记忆（去重：相同文本不重复存；超上限滚动淘汰最旧） */
    fun add(context: Context, text: String, sessionId: String? = null) {
        val t = text.trim()
        if (t.isBlank()) return
        val list = all(context).toMutableList()
        if (list.any { it.text == t }) return
        list.add(MemoryItem(t, sessionId))
        while (list.size > MAX_ITEMS) list.removeAt(0)
        save(context, list)
    }

    /** 某会话相关的记忆条数（删除会话提示用） */
    fun countBySession(context: Context, sessionId: String): Int =
        all(context).count { it.sessionId == sessionId }

    /** 删除指定会话的全部记忆，返回删除条数 */
    fun removeBySession(context: Context, sessionId: String): Int {
        val list = all(context)
        val removed = list.count { it.sessionId == sessionId }
        save(context, list.filterNot { it.sessionId == sessionId })
        return removed
    }

    /** 删除指定记忆（按文本精确匹配） */
    fun remove(context: Context, text: String) {
        val list = all(context).filterNot { it.text == text }
        save(context, list)
    }

    /** 清空全部记忆 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_ITEMS).apply()
    }

    /** 记忆注入文本：**仅注入指定会话自己的记忆**（用户每个会话对应一个客户/场景，跨会话不混用） */
    fun injectText(context: Context, sessionId: String?): String? {
        val items = if (sessionId.isNullOrBlank()) emptyList() else all(context).filter { it.sessionId == sessionId }
        if (items.isEmpty()) return null
        return buildString {
            append("\n\n【本会话记忆（此前对话中 AI 主动记忆的重要信息，可依赖但需与现场实测核对）】")
            items.forEachIndexed { i, it ->
                append("\n").append(i + 1).append(". ").append(it.text)
            }
        }
    }

    /** 指定会话的记忆列表（记忆管理弹窗用） */
    fun ofSession(context: Context, sessionId: String): List<MemoryItem> =
        all(context).filter { it.sessionId == sessionId }

    private fun save(context: Context, list: List<MemoryItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("text", item.text)
                if (item.sessionId != null) put("sessionId", item.sessionId)
                put("ts", item.ts)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
