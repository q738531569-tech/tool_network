package com.installer.toolbox.common.util

import android.content.Context

/**
 * 功能使用统计：记录各功能页打开次数（SharedPreferences 轻量计数）。
 * 供"诊断信息"页展示，帮助开发者了解哪些功能常用、哪些少用；
 * 仅本地存储、不上传，统计失败静默不影响功能。
 */
object UsageStats {

    private const val PREFS = "usage_stats"

    /** 页面名 → 显示标签（诊断信息页展示用；新增功能页在此补一条） */
    val LABELS: Map<String, String> = mapOf(
        "home" to "首页工作台",
        "ai" to "AI 助手",
        "settings" to "设置",
        "device_discovery" to "设备发现",
        "diagnosis" to "网络诊断",
        "wifi" to "Wi-Fi 分析",
        "wifi_radar" to "Wi-Fi 雷达",
        "aviation_signal" to "航空信号检测",
        "capture" to "抓包分析",
        "camera" to "摄像头管理",
        "subnet_calc" to "IP 计算器",
        "arp_detect" to "ARP 欺骗检测",
        "gateway_conflict" to "网关/IP 冲突检测",
        "ble_scan" to "BLE 扫描",
        "nmap_scan" to "nmap 扫描",
        "pcdn_detect" to "PCDN 全网检测",
        "traffic_audit" to "流量审计",
        "loop_detect" to "环路检测",
        "silence" to "静默模式",
        "barcode_scan" to "二维码/条码",
        "remote_shell" to "远程终端",
        "weak_network" to "弱网检测",
        "snmp" to "SNMP 面板",
        "wake_on_lan" to "局域网唤醒",
        "feedback" to "反馈与建议",
        "diagnostic_info" to "诊断信息"
    )

    /** 计数 +1（页面打开/功能使用） */
    fun count(context: Context, key: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        } catch (e: Exception) {
            // 统计失败不影响功能
        }
    }

    /** App 启动计数（诊断信息页展示"累计启动次数"） */
    fun appStart(context: Context) = count(context, "__app_start__")

    /** 全部计数（key→次数，按次数降序；不含 __app_start__，单独接口取） */
    fun all(context: Context): List<Pair<String, Int>> {
        return try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
                .filterKeys { it != "__app_start__" }
                .filterValues { it is Int }
                .map { it.key to (it.value as Int) }
                .sortedByDescending { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 累计启动次数 */
    fun appStarts(context: Context): Int =
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("__app_start__", 0)
        } catch (e: Exception) {
            0
        }

    /** 重置统计 */
    fun reset(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (e: Exception) {
        }
    }
}
