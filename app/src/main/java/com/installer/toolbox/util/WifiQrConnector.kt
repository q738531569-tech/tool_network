package com.installer.toolbox.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.wifi.WifiQr

/**
 * WiFi 二维码连接器：把解析出的 [WifiQr] 连到目标 WiFi。
 *
 * 三档策略：
 * 1. **有 root**（PDA/测试机均 Magisk）：`cmd wifi connect-network` 全静默直连
 *    （Android 12+ 系统命令，root 下无弹窗、自动保存网络）
 * 2. **无 root 且 Android 10+（API 29+）**：`WifiNetworkSpecifier` 临时连接，
 *    系统弹一次确认框；API 33+ 需调用方先申请 `NEARBY_WIFI_DEVICES` 运行时权限
 * 3. **兜底（API 29 以下）**：`WifiManager.addNetwork + enableNetwork`（已废弃但可用）
 *
 * 注意：连接动作会断开当前 WiFi（切换到目标网络），调用前 UI 应提示用户。
 */
object WifiQrConnector {

    sealed interface ConnectResult {
        /** 连接已发起/成功 */
        data class Success(val via: String) : ConnectResult
        /** 免 root 模式系统弹确认框（本机静默发起，用户在系统弹窗点连接） */
        data class AwaitingSystemConfirm(val via: String) : ConnectResult
        /** 失败原因（中文，可直接展示） */
        data class Failure(val reason: String) : ConnectResult
    }

    /**
     * 连接目标 WiFi。
     * @param context 应用上下文
     * @param qr 解析出的 WiFi 信息
     * @param rootAvailable 是否有 root（有则走 cmd wifi 静默直连）
     * @param onNearbyPermissionNeeded API 33+ 无 root 时回调，要求 UI 层申请 NEARBY_WIFI_DEVICES
     */
    suspend fun connect(
        context: Context,
        qr: WifiQr,
        rootAvailable: Boolean,
        onNearbyPermissionNeeded: () -> Unit = {}
    ): ConnectResult {
        // 1. root 静默通道
        if (rootAvailable) {
            val out = try {
                RootShell.run(buildCmdWifiConnect(qr))
            } catch (e: Exception) {
                return ConnectResult.Failure("root 执行失败：${e.message}")
            }
            val msg = out.trim()
            // cmd wifi connect-network 成功/失败均静默（exit 0，无输出），网络加入 saved 列表后由系统异步连接；
            // 只要 su 通道本身没报错（未找到 su / root 执行失败 / 命令退出码）即视为连接已发起
            if (!msg.startsWith("root 执行失败") &&
                !msg.startsWith("命令退出码") &&
                !msg.startsWith("未找到 su")
            ) {
                return ConnectResult.Success("root cmd wifi")
            }
            // su 通道异常：降级免 root 通道（避免直接失败）
        }

        // 2. 免 root 通道
        return connectNoRoot(context, qr, onNearbyPermissionNeeded)
    }

    /** 免 root 连接：Android 10+ WifiNetworkSpecifier；老版本 WifiManager 兜底 */
    private suspend fun connectNoRoot(
        context: Context,
        qr: WifiQr,
        onNearbyPermissionNeeded: () -> Unit
    ): ConnectResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 33+ 需要 NEARBY_WIFI_DEVICES 运行时权限
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                onNearbyPermissionNeeded()
                return ConnectResult.Failure("需要「附近设备」权限后才能连接 WiFi，已请求授权，请允许后重试")
            }
            return requestSpecifier(context, qr)
        }

        // 老版本兜底：addNetwork + enableNetwork（deprecated）
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ConnectResult.Failure("系统 WiFi 服务不可用")
        return try {
            val cfg = WifiConfiguration().apply {
                SSID = "\"${qr.ssid}\""
                if (qr.isOpen) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"${qr.password.orEmpty()}\""
                }
                hiddenSSID = qr.hidden
            }
            val netId = wm.addNetwork(cfg)
            if (netId == -1) ConnectResult.Failure("添加网络失败（密码格式或系统限制）")
            else {
                wm.enableNetwork(netId, true)
                ConnectResult.Success("系统网络管理")
            }
        } catch (e: Exception) {
            ConnectResult.Failure("连接失败：${e.message}")
        }
    }

    /**
     * Android 10+：WifiNetworkSpecifier 一次性临时连接（系统弹确认框）。
     * 用 ConnectivityManager.requestNetwork 发起，立即返回等待系统弹窗。
     */
    private suspend fun requestSpecifier(context: Context, qr: WifiQr): ConnectResult {
        return try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(qr.ssid)
                .apply {
                    if (!qr.isOpen) setWpa2Passphrase(qr.password.orEmpty())
                }
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 已连上目标 WiFi（本回调生命周期很短，无需额外动作）
                }
            })
            ConnectResult.AwaitingSystemConfirm("系统连接（免 root）")
        } catch (e: Exception) {
            ConnectResult.Failure("发起连接失败：${e.message}")
        }
    }

    /** 构造 `cmd wifi connect-network` 命令（root 通道，Android 12+） */
    private fun buildCmdWifiConnect(qr: WifiQr): String {
        val ssid = qr.ssid.replace("\"", "\\\"").replace("\\", "\\\\")
        val typeSpec = when (qr.type?.uppercase()) {
            "WPA", "WPA2" -> "wpa2"
            "WPA3" -> "wpa3"
            "WEP" -> "wep"
            "NOPASS" -> "open"
            else -> if (qr.isOpen) "open" else "wpa2" // 未知类型按 WPA2 尝试
        }
        val pwd = qr.password?.replace("\"", "\\\"").orEmpty()
        return if (qr.isOpen) {
            "cmd wifi connect-network \"$ssid\" open"
        } else {
            "cmd wifi connect-network \"$ssid\" $typeSpec \"$pwd\""
        }
    }
}
