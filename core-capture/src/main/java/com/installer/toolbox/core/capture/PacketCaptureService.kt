package com.installer.toolbox.core.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 免 root 抓包前台服务：通过 VpnService 建立 tun 虚拟网卡，
 * 由 [CaptureEngine] 后台线程抓包统计，快照经 [CaptureBus] 推送给 UI。
 *
 * VPN 配置：
 * - 会话名"装维工具箱抓包"；
 * - 虚拟地址 10.99.0.2/32；
 * - 路由 0.0.0.0/0（全量路由：应用自身流量也会进入 tun，后续里程碑
 *   可细化路由规则只抓目标网段）；
 * - setBlocking(true)：tun fd 为阻塞模式，read 会阻塞等待数据包。
 *
 * 注意：tun 只能看到 L3 IP 包（看不到 ARP 等 L2 帧），广播风暴检测基于
 * IP 层广播/组播包速率；完整 L2 检测需 root 通道（后续里程碑实现）。
 *
 * 使用方式：
 * - [start]：启动（需先完成 VpnService.prepare() 授权 + POST_NOTIFICATIONS 授权）；
 * - [stop]：停止服务；
 * - [isRunning]：查询运行状态（供 UI 恢复状态）。
 */
class PacketCaptureService : VpnService() {

    private var captureEngine: CaptureEngine? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("PacketCaptureService", "onStartCommand running=${isRunning()} action=${intent?.action}")
        // 停止命令：VpnService 被系统绑定，stopService 无法触发 onDestroy，
        // 必须显式停止：关 fd（撤销 VPN）→ 解除前台 → stopSelf
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning()) {
            // startForegroundService 启动后 5 秒内必须 startForeground，否则系统 ANR/崩溃
            startForegroundNotification()
            if (!establishVpn()) {
                // 建立 VPN 失败（未授权或系统拒绝）：无法抓包，停止服务
                stopSelf()
                return START_NOT_STICKY
            }
            startCapture()
        }
        return START_STICKY
    }

    /** 建立 tun 虚拟网卡；失败（如授权被撤销）返回 false */
    private fun establishVpn(): Boolean {
        val builder = Builder()
            .setSession("装维工具箱抓包")
            .addAddress("10.99.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        val fd = builder.establish() ?: return false
        tunFd = fd
        return true
    }

    /** 启动抓包引擎，快照经 CaptureBus 推给 UI */
    private fun startCapture() {
        val fd = tunFd ?: return
        val engine = CaptureEngine(fd.fileDescriptor, BroadcastStormDetector())
        captureEngine = engine
        engine.start { snapshot -> CaptureBus.emit(snapshot) }
        running.set(true)
    }

    /** 停止引擎、关闭 tun fd */
    private fun stopCapture() {
        android.util.Log.d("PacketCaptureService", "stopCapture called")
        running.set(false)
        captureEngine?.stop()
        captureEngine = null
        try {
            tunFd?.close()
        } catch (e: IOException) {
            // 忽略关闭异常
        }
        tunFd = null
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "抓包分析", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("抓包分析")
            .setContentText("正在抓包分析…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        // targetSdk 34 前台服务必须声明并传入类型（dataSync），见 manifest
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onDestroy() {
        android.util.Log.d("PacketCaptureService", "onDestroy")
        stopCapture()
        super.onDestroy()
    }

    /** 用户撤销 VPN 授权：系统调用本方法后停止服务 */
    override fun onRevoke() {
        android.util.Log.d("PacketCaptureService", "onRevoke")
        stopCapture()
        super.onRevoke()
    }

    companion object {
        private const val CHANNEL_ID = "capture_analysis"
        private const val NOTIFICATION_ID = 1001

        /** 运行标志（进程内静态，供 UI 查询） */
        private val running = AtomicBoolean(false)

        /**
         * 启动抓包前台服务（startForegroundService）。
         * Android 13+ 前台服务通知需要 POST_NOTIFICATIONS 授权，未授权时放弃启动，
         * 调用方（UI）应先用权限请求流程取得授权。
         */
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val intent = Intent(context, PacketCaptureService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止抓包服务：发停止命令（VpnService 被系统绑定，stopService 无效） */
        fun stop(context: Context) {
            val intent = Intent(context, PacketCaptureService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** 停止命令 action */
        const val ACTION_STOP = "com.installer.toolbox.capture.STOP"

        /** 服务是否在运行 */
        fun isRunning(): Boolean = running.get()
    }
}
