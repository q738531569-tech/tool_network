package com.installer.toolbox.core.agent.support

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * 自动收集上报调度：App 启动时静默上报运行信息到服务器（供优化分析），
 * 间隔 ≥12 小时，有崩溃日志时优先上报；失败静默不影响使用。
 * 上报内容（不含网络拓扑隐私）：版本/机型/系统/root/启动次数/功能使用统计/崩溃日志。
 */
object AutoReporter {

    private const val PREFS = "auto_report"
    private const val KEY_LAST = "last_report_ts"
    private const val INTERVAL_MS = 12L * 3600 * 1000 // 12 小时

    /** App 启动时调用（后台线程静默上报） */
    fun maybeReport(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0)
        val hasCrash = CrashCollector.logs(ctx).isNotEmpty()
        val due = System.currentTimeMillis() - last >= INTERVAL_MS
        if (!due && !hasCrash) return
        // 有崩溃：立即上报一次（帮助开发者第一时间拿到现场堆栈）
        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        Thread {
            try {
                runBlocking { FeedbackApi.autoReport(ctx) }
            } catch (e: Exception) {
                // 静默：自动上报失败不影响任何功能
            }
        }.start()
    }
}
