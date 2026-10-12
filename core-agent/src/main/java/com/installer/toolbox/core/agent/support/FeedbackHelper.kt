package com.installer.toolbox.core.agent.support

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.installer.toolbox.common.util.UsageStats
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.scan.LanInterfaceResolver
import com.installer.toolbox.core.network.wifi.WifiAnalyzer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 反馈与诊断信息助手：生成诊断信息文本（反馈与诊断页共用）+ 组装反馈内容。
 * 诊断信息包含设备/系统/App 版本/root 状态/使用统计/崩溃日志条数；
 * 网络信息（SSID/局域网接口/网关）属敏感信息，仅在用户勾选时附带。
 */
object FeedbackHelper {

    /** 反馈类型选项（反馈页 chips 用） */
    val FEEDBACK_TYPES = listOf("问题反馈", "功能建议", "体验优化", "其他")

    /**
     * 收集诊断信息文本。
     * @param includeNetwork 是否附带当前网络信息（SSID/局域网 IP/网关等，默认不附）
     * @param includeUsage 是否附带功能使用统计（默认附）
     */
    fun buildDiagnosticText(
        context: Context,
        includeNetwork: Boolean = false,
        includeUsage: Boolean = true
    ): String {
        val ctx = context.applicationContext
        val pkg = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        } catch (e: Exception) {
            null
        }
        return buildString {
            appendLine("=== 装维工具箱 诊断信息 ===")
            appendLine("生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("App 版本：${pkg?.versionName ?: "未知"}（versionCode ${pkg?.let { PackageInfoCompat.getLongVersionCode(it) } ?: "?"}）")
            appendLine("首次安装：${pkg?.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.firstInstallTime)) } ?: "未知"}")
            appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}（${android.os.Build.PRODUCT}）")
            appendLine("系统：Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
            appendLine("ABI：${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("root：${if (RootShell.isAvailable()) "已获取" else "未获取"}")
            appendLine("累计启动：${UsageStats.appStarts(ctx)} 次")
            val crashCount = CrashCollector.logs(ctx).size
            appendLine("崩溃日志：$crashCount 份（${if (crashCount > 0) "请一并反馈，便于定位问题" else "无"}）")
            if (includeNetwork) {
                appendLine()
                appendLine("--- 网络信息（用户授权附带）---")
                try {
                    val wifi = WifiAnalyzer.currentConnection(ctx)
                    if (wifi != null) {
                        appendLine("Wi-Fi：${wifi.ssid}（BSSID ${wifi.bssid}，信号 ${wifi.rssiDbm} dBm，${wifi.band} 信道 ${wifi.channel}）")
                    } else {
                        appendLine("Wi-Fi：未连接（可能为有线/移动数据）")
                    }
                } catch (e: Exception) {
                    appendLine("Wi-Fi：读取失败")
                }
                if (RootShell.isAvailable()) {
                    try {
                        val lan = LanInterfaceResolver.resolve(RootShell::run)
                        if (lan != null) {
                            appendLine("局域网接口：${lan.name} IP ${lan.ip} 网段 ${lan.subnet} 网关 ${lan.gateway ?: "未知"}")
                        } else {
                            appendLine("局域网接口：未解析到")
                        }
                    } catch (e: Exception) {
                        appendLine("局域网接口：解析失败")
                    }
                }
            }
            if (includeUsage) {
                appendLine()
                appendLine("--- 功能使用统计（本机累计）---")
                val all = UsageStats.all(ctx)
                if (all.isEmpty()) {
                    appendLine("暂无统计")
                } else {
                    all.forEach { (k, v) ->
                        appendLine("${UsageStats.LABELS[k] ?: k}：$v 次")
                    }
                }
            }
        }
    }

    /** 组装反馈文本：反馈内容 + 类型 + 可选诊断信息/网络信息 */
    fun buildFeedbackText(
        context: Context,
        type: String,
        content: String,
        includeDiagnostic: Boolean = true,
        includeNetwork: Boolean = false
    ): String = buildString {
        appendLine("=== 装维工具箱 反馈 ===")
        appendLine("类型：$type")
        appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        appendLine()
        appendLine("反馈内容：")
        appendLine(content.ifBlank { "（未填写具体内容）" })
        if (includeDiagnostic) {
            appendLine()
            appendLine(buildDiagnosticText(context, includeNetwork = includeNetwork, includeUsage = true))
        }
    }
}
