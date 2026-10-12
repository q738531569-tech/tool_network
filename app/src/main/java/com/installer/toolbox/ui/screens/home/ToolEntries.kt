package com.installer.toolbox.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EmergencyShare
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.installer.toolbox.ui.navigation.Routes
import com.installer.toolbox.ui.navigation.TopLevelDestination

/**
 * 首页工作台的单个工具入口（可扩展核心：以后加功能 = 分类里加一条）。
 */
data class ToolEntry(
    val id: String,
    val name: String,
    val desc: String,
    val icon: ImageVector,
    val route: String
)

/** 首页工作台的工具分类 */
data class ToolCategory(
    val title: String,
    val tools: List<ToolEntry>
)

/**
 * 首页工作台入口清单（分类 + 工具卡片）。
 * 图标均取自 material-icons-extended 确定存在的集合。
 */
object ToolEntries {

    val categories: List<ToolCategory> = listOf(
        // 应急求救排第一（紧急情况最快点到）
        ToolCategory(
            title = "应急求救",
            tools = listOf(
                ToolEntry(
                    id = "sos",
                    name = "SOS 紧急求救",
                    desc = "声光摩斯 + 短信求救（含位置）+ 一键全发，紧急情况救命用",
                    icon = Icons.Filled.EmergencyShare,
                    route = Routes.SOS
                )
            )
        ),
        ToolCategory(
            title = "网络工具",
            tools = listOf(
                ToolEntry(
                    id = "device_discovery",
                    name = "设备发现",
                    desc = "扫一遍局域网，列出有哪些设备连着网",
                    icon = Icons.Filled.Devices,
                    route = Routes.DEVICE_DISCOVERY
                ),
                ToolEntry(
                    id = "diagnosis",
                    name = "网络诊断",
                    desc = "一键体检：网通不通、哪里卡、端口开没开",
                    icon = Icons.Filled.Speed,
                    route = Routes.DIAGNOSIS
                ),
                ToolEntry(
                    id = "wifi",
                    name = "Wi-Fi 分析",
                    desc = "看当前 Wi-Fi 信号强不强，周边信道挤不挤",
                    icon = Icons.Filled.Wifi,
                    route = Routes.WIFI
                ),
                ToolEntry(
                    id = "wifi_radar",
                    name = "Wi-Fi 雷达",
                    desc = "雷达图实时找 AP：走动看信号，还能 RTT 测距（米）",
                    icon = Icons.Filled.Router,
                    route = Routes.WIFI_RADAR
                ),
                ToolEntry(
                    id = "aviation_signal",
                    name = "航空信号检测",
                    desc = "查间歇性断网是不是 5G 用了和飞机雷达共用的信道",
                    icon = Icons.Filled.Security,
                    route = Routes.AVIATION_SIGNAL
                ),
                ToolEntry(
                    id = "capture",
                    name = "抓包分析",
                    desc = "实时查看手机收发哪些网络数据（需授权）",
                    icon = Icons.Filled.Analytics,
                    route = Routes.CAPTURE
                ),
                ToolEntry(
                    id = "subnet_calc",
                    name = "IP 计算器",
                    desc = "算子网、广播地址和可用 IP 范围",
                    icon = Icons.Filled.Calculate,
                    route = Routes.SUBNET_CALC
                ),
                ToolEntry(
                    id = "remote_shell",
                    name = "远程终端",
                    desc = "远程登录路由器等设备执行命令（SSH/Telnet）",
                    icon = Icons.Filled.Terminal,
                    route = Routes.REMOTE_SHELL
                ),
                ToolEntry(
                    id = "remote_control",
                    name = "远程控制台",
                    desc = "在家遥控客户现场的 PDA，用它的 AI 排查网络（需装维账号）",
                    icon = Icons.Filled.CastConnected,
                    route = Routes.REMOTE_CONTROL
                ),
                ToolEntry(
                    id = "weak_network",
                    name = "弱网检测",
                    desc = "持续测一会儿网络稳不稳：丢包、卡顿、抖动都看得到",
                    icon = Icons.Filled.NetworkCheck,
                    route = Routes.WEAK_NETWORK
                ),
                ToolEntry(
                    id = "snmp",
                    name = "SNMP 面板",
                    desc = "查路由器/交换机等设备的系统信息（需设备开 SNMP，无需 root）",
                    icon = Icons.Filled.Dns,
                    route = Routes.SNMP
                ),
                ToolEntry(
                    id = "wake_on_lan",
                    name = "局域网唤醒",
                    desc = "远程开机：让关机/休眠的电脑一键开机（需电脑开启网络唤醒）",
                    icon = Icons.Filled.PowerSettingsNew,
                    route = Routes.WAKE_ON_LAN
                )
            )
        ),
        ToolCategory(
            title = "安全审计",
            tools = listOf(
                ToolEntry(
                    id = "arp_detect",
                    name = "ARP 欺骗检测",
                    desc = "查有没有人冒充网关/设备劫持网络（需 root）",
                    icon = Icons.Filled.Security,
                    route = Routes.ARP_DETECT
                ),
                ToolEntry(
                    id = "gateway_conflict",
                    name = "网关/IP 冲突检测",
                    desc = "排查两台设备抢 IP、多路由器乱接导致的掉线",
                    icon = Icons.Filled.Security,
                    route = Routes.GATEWAY_CONFLICT
                ),
                ToolEntry(
                    id = "ble_scan",
                    name = "BLE 扫描",
                    desc = "看看周边有哪些蓝牙低功耗设备",
                    icon = Icons.Filled.Bluetooth,
                    route = Routes.BLE_SCAN
                ),
                ToolEntry(
                    id = "nmap_scan",
                    name = "nmap 扫描",
                    desc = "深度扫描设备开了哪些服务/端口（需 root）",
                    icon = Icons.Filled.TravelExplore,
                    route = Routes.NMAP_SCAN
                ),
                ToolEntry(
                    id = "pcdn_detect",
                    name = "PCDN 全网检测",
                    desc = "一键找出偷跑上传、占网速的 PCDN 设备",
                    icon = Icons.Filled.Psychology,
                    route = Routes.PCDN_DETECT
                ),
                ToolEntry(
                    id = "traffic_audit",
                    name = "流量审计",
                    desc = "查某台设备上传/下载了多少流量（需 root+授权）",
                    icon = Icons.Filled.Speed,
                    // 首页入口不带 ip 参数（PCDN 卡片『长时审计』跳转才带），
                    // 直接跳无 query 路由，defaultValue 生效目标 IP 留空
                    route = "traffic_audit"
                ),
                ToolEntry(
                    id = "loop_detect",
                    name = "环路检测",
                    desc = "查网线/交换机接成环导致的网络瘫痪（需 root）",
                    icon = Icons.Filled.AccountTree,
                    route = Routes.LOOP_DETECT
                ),
                ToolEntry(
                    id = "silence",
                    name = "静默模式",
                    desc = "让偷跑流量的设备静默/断连，测速更准（需 root+授权，责任自负）",
                    icon = Icons.Filled.Block,
                    route = Routes.SILENCE
                )
            )
        ),
        ToolCategory(
            title = "安防设备",
            tools = listOf(
                ToolEntry(
                    id = "camera",
                    name = "摄像头管理",
                    desc = "自动发现摄像头，改 IP/密码、重启、恢复出厂",
                    icon = Icons.Filled.Videocam,
                    route = Routes.CAMERA
                )
            )
        ),
        ToolCategory(
            title = "二维码/条码",
            tools = listOf(
                ToolEntry(
                    id = "barcode",
                    name = "二维码/条码功能",
                    desc = "扫码识别、生成二维码/条码",
                    icon = Icons.Filled.QrCodeScanner,
                    route = Routes.BARCODE_SCAN
                )
            )
        )
    )
}
