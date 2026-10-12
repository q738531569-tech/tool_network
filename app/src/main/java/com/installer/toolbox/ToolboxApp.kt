package com.installer.toolbox

import android.app.Application
import com.installer.toolbox.common.data.ToolboxDatabase
import com.installer.toolbox.common.util.UsageStats
import com.installer.toolbox.core.network.discovery.MacVendorTable
import com.installer.toolbox.core.agent.support.AutoReporter
import com.installer.toolbox.core.agent.support.CrashCollector

/**
 * 应用入口：持有全局单例（数据库等）+ 初始化崩溃收集/使用统计/自动上报。
 */
class ToolboxApp : Application() {

    /** 本地数据库（懒加载单例） */
    val database: ToolboxDatabase by lazy { ToolboxDatabase.create(this) }

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志收集（全局 handler，落盘 filesDir/crash/，诊断信息页可查看）
        CrashCollector.init(this)
        // 功能使用统计（启动计数 + 各页面打开计数，诊断信息页展示）
        UsageStats.appStart(this)
        // 预加载完整 MAC 厂商库（assets 中约 4.6 万条 OUI，与内置 nmap 共用），
        // 设备发现页扫描时直接可用；失败不抛异常（内部回退内置精简表）
        MacVendorTable.init(this)
        // 自动收集上报（启动时静默，≥12h 或有崩溃时上报运行信息到服务器）
        AutoReporter.maybeReport(this)
    }
}
