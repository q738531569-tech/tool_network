package com.installer.toolbox.core.agent.support

import android.content.Context
import java.io.File

/**
 * AI 资料笔记（.md 文件）：AI 通过 write_md 工具自动生成现场资料
 * （客户信息/网络拓扑/排查结论/报告），清空会话后可通过 read_md 读回，
 * 像开发 agent 写 AGENTS.md/PROGRESS.md 一样自动沉淀知识。
 *
 * 按会话隔离：每会话一个子目录 filesDir/ai_notes/<sessionId>/；
 * 删除会话时目录连带删除；清空会话时保留。
 */
object AiNotes {

    private const val ROOT = "ai_notes"

    /** 会话资料目录（不存在则创建） */
    fun dir(context: Context, sessionId: String): File {
        val d = File(context.filesDir, "$ROOT/$sessionId")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 写入/覆盖一份 md（文件名自动补 .md 后缀、过滤非法字符；内容上限 200KB） */
    fun write(context: Context, sessionId: String, fileName: String, content: String): File {
        val safeName = fileName.trim()
            .replace(Regex("""[/\\:*?"<>|]"""), "_")
            .let { if (it.endsWith(".md")) it else "$it.md" }
            .ifBlank { "note_${System.currentTimeMillis()}.md" }
        val f = File(dir(context, sessionId), safeName)
        f.writeText(content.take(200 * 1024))
        return f
    }

    /** 列出会话全部 md 文件（按修改时间倒序） */
    fun list(context: Context, sessionId: String): List<File> =
        dir(context, sessionId).listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 读取指定 md（文件名可带可不带 .md 后缀） */
    fun read(context: Context, sessionId: String, fileName: String): String? {
        val name = if (fileName.endsWith(".md")) fileName else "$fileName.md"
        val f = File(dir(context, sessionId), name)
        return if (f.isFile) f.readText() else null
    }

    /** 删除会话目录（删除会话时连带清理） */
    fun removeBySession(context: Context, sessionId: String) {
        File(context.filesDir, "$ROOT/$sessionId").deleteRecursively()
    }
}
