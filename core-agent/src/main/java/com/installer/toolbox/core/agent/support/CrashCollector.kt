package com.installer.toolbox.core.agent.support

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志收集：全局 UncaughtExceptionHandler 落盘（filesDir/crash/，保留最近 10 份），
 * 诊断信息页可查看/导出。收集范围仅崩溃堆栈与应用版本/机型，不含任何用户数据；
 * 崩溃后交给系统默认处理器（不改变崩溃行为）。
 */
object CrashCollector {

    private const val MAX_LOGS = 10
    private val fileDf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private val lineDf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 应用启动时初始化（幂等，重复调用仅重置 handler，无害） */
    fun init(context: Context) {
        val ctx = context.applicationContext
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                save(ctx, thread, throwable)
            } catch (e: Exception) {
                // 落盘失败不阻塞崩溃流程
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    private fun save(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, "crash")
        dir.mkdirs()
        val sb = StringBuilder()
        sb.appendLine("崩溃时间：${lineDf.format(Date())}")
        sb.appendLine("App 版本：${versionName(context)}")
        sb.appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
        sb.appendLine("线程：${thread.name}")
        sb.appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
        sb.appendLine(throwable.stackTraceToString())
        File(dir, "crash_${fileDf.format(Date())}.txt").writeText(sb.toString())
        // 滚动保留最近 MAX_LOGS 份
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_LOGS)?.forEach { it.delete() }
    }

    /** App 版本号（PackageManager 读取，app 模块未开 buildConfig） */
    fun versionName(context: Context): String = try {
        val pkg = context.applicationContext.packageManager
            .getPackageInfo(context.applicationContext.packageName, 0)
        pkg.versionName ?: "未知"
    } catch (e: Exception) {
        "未知"
    }

    /** 崩溃日志列表（新→旧） */
    fun logs(context: Context): List<File> =
        File(context.filesDir, "crash").listFiles()
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 清空崩溃日志 */
    fun clear(context: Context) {
        File(context.filesDir, "crash").listFiles()?.forEach { it.delete() }
    }
}
