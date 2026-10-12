package com.installer.toolbox.core.agent.support

import android.content.Context
import org.json.JSONObject

/**
 * 会话资料（.md/.txt 导入）：装维把现场资料（客户信息/网络拓扑/排查记录等 md 文档）
 * 导入当前会话作为背景资料。按会话隔离（一个会话一个客户），
 * 清空会话消息时资料保留（重新聊天 AI 仍可读取），删除会话时连带删除。
 */
object AiSessionDocs {

    private const val PREFS = "ai_session_docs"

    /** 单份资料（fileName + 全文；注入时截断控制费用） */
    data class Doc(val fileName: String, val text: String)

    /** 取指定会话的资料；无则 null */
    fun get(context: Context, sessionId: String?): Doc? {
        if (sessionId.isNullOrBlank()) return null
        return try {
            val all = loadAll(context)
            all[sessionId]
        } catch (e: Exception) {
            null
        }
    }

    /** 保存/覆盖当前会话资料（text 上限 200KB，防手滑导入超大文件） */
    fun save(context: Context, sessionId: String, fileName: String, text: String) {
        if (sessionId.isBlank()) return
        val all = loadAll(context).toMutableMap()
        all[sessionId] = Doc(fileName.take(80), text.take(200 * 1024))
        persist(context, all)
    }

    /** 清除指定会话的资料 */
    fun remove(context: Context, sessionId: String) {
        val all = loadAll(context).toMutableMap()
        all.remove(sessionId)
        persist(context, all)
    }

    /** 删除会话时连带删除其资料（与记忆同生命周期） */
    fun removeBySession(context: Context, sessionId: String) = remove(context, sessionId)

    private fun loadAll(context: Context): Map<String, Doc> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("docs", null) ?: return emptyMap()
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { sid ->
                    val d = obj.optJSONObject(sid) ?: return@forEach
                    val name = d.optString("name")
                    val text = d.optString("text")
                    if (name.isNotBlank() && text.isNotBlank()) {
                        put(sid, Doc(name, text))
                    }
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun persist(context: Context, all: Map<String, Doc>) {
        val obj = JSONObject()
        all.forEach { (sid, doc) ->
            obj.put(
                sid,
                JSONObject().put("name", doc.fileName).put("text", doc.text)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("docs", obj.toString()).apply()
    }
}
