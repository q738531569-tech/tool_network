package com.installer.toolbox.util

import android.content.Context
import android.content.Intent

/**
 * 报告导出/分享工具：把检测/诊断结果拼成文本，调系统分享（微信/文件/短信等）。
 * 装维现场留档给客户/公司用。
 */
object ShareHelper {

    /**
     * 分享文本。
     * @param title 标题（分享面板与消息主题）
     * @param text 正文（换行分隔）
     */
    fun shareText(context: Context, title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "分享 $title"))
        } catch (e: Exception) {
            // 无可用分享目标时忽略（部分 ROM/权限场景）
        }
    }
}
