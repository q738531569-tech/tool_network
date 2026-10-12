package com.installer.toolbox.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 顶级导航目的地（3 个 tab）：首页工作台 / AI 助手 / 设置。
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    HOME("home", "首页", Icons.Filled.Home),
    AI("ai", "AI 助手", Icons.Filled.SmartToy),
    SETTINGS("settings", "设置", Icons.Filled.Settings)
}

/**
 * 二级功能页路由常量（不进入底部导航，从首页工作台进入）。
 *
 * 以后新增功能页：在此加常量 → MainScreen 注册 composable → 首页 ToolEntries 加一条入口。
 */
object Routes {
    const val DEVICE_DISCOVERY = "device_discovery"
    const val DIAGNOSIS = "diagnosis"
    const val WIFI = "wifi"
    // Wi-Fi 雷达（雷达图实时找 AP：信号距离 + RTT 测距）
    const val WIFI_RADAR = "wifi_radar"
    // 航空信号检测（DFS 信道风险：飞机经过断网的根因检测）
    const val AVIATION_SIGNAL = "aviation_signal"
    const val CAPTURE = "capture"
    const val CAMERA = "camera"
    const val SUBNET_CALC = "subnet_calc"
    const val ARP_DETECT = "arp_detect"
    const val GATEWAY_CONFLICT = "gateway_conflict"
    const val BLE_SCAN = "ble_scan"
    const val NMAP_SCAN = "nmap_scan"
    const val PCDN_DETECT = "pcdn_detect"
    /**
     * 流量审计（可选参数：ip=PCDN 卡片跳转带目标 IP；mode=passthrough 时自动切『借道审计』
     * 模式——二级路由卡片跳转用，免输入免选模式直达）
     */
    const val TRAFFIC_AUDIT = "traffic_audit?ip={ip}&mode={mode}"
    const val LOOP_DETECT = "loop_detect"
    // 静默模式（Wi-Fi 杀手原理：ARP 欺骗静默 + deauth 踢下线，需 root + 授权）
    const val SILENCE = "silence"
    // 二维码/条码功能（扫码识别 + 生成，页面内 Tab 切换）
    const val BARCODE_SCAN = "barcode_scan"
    // 远程终端（SSH/Telnet 统一，用户自用 + AI 共用同一执行器）
    const val REMOTE_SHELL = "remote_shell"
    // 远程控制台（App 内置：登录服务器中转控制另一台 PDA 的 AI，装维远程排查）
    const val REMOTE_CONTROL = "remote_control"
    // 弱网检测（持续探测丢包/时延/抖动，判断网络稳定性）
    const val WEAK_NETWORK = "weak_network"
    // SNMP 面板（查询设备系统信息：名称/描述/运行时间/位置/联系人）
    const val SNMP = "snmp"
    // 局域网唤醒（输入 MAC 发 magic packet 远程开机，无需 root）
    const val WAKE_ON_LAN = "wake_on_lan"
    // 反馈与建议（一键提交到服务器反馈接口）
    const val FEEDBACK = "feedback"
    // 诊断信息（设备/使用统计/崩溃日志，可导出）
    const val DIAGNOSTIC_INFO = "diagnostic_info"
    // SOS 紧急求救（声光摩斯/短信求救/一键求救，首页第一位）
    const val SOS = "sos"

    /** 归属"首页"tab 的二级路由集合（用于底部 tab 高亮） */
    val HOME_SECONDARY: Set<String> = setOf(
        DEVICE_DISCOVERY, DIAGNOSIS, WIFI, WIFI_RADAR, AVIATION_SIGNAL, CAPTURE, CAMERA, SUBNET_CALC,
        ARP_DETECT, BLE_SCAN, NMAP_SCAN, PCDN_DETECT, TRAFFIC_AUDIT,
        LOOP_DETECT, SILENCE, BARCODE_SCAN, REMOTE_SHELL, WEAK_NETWORK, SNMP, WAKE_ON_LAN,
        REMOTE_CONTROL
    )

    /** 归属"设置"tab 的二级路由集合（用于底部 tab 高亮） */
    val SETTINGS_SECONDARY: Set<String> = setOf(FEEDBACK, DIAGNOSTIC_INFO)
}
