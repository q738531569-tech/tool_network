package com.installer.toolbox.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启（2026-08-19）。
 *
 * 场景：手机重启后 APP 不会自动运行，远程控制服务不连服务器 → 控制端看设备一直离线。
 * 机制：系统发 BOOT_COMPLETED 广播时按远程开关（prefs "remote"/enabled）决定是否拉起
 * [RemoteControlService]——开关开着才自启（用户主动关闭远程则重启后不打扰）。
 *
 * 注意：
 * 1. Android 12+ 后台启动前台服务受限，但 BOOT_COMPLETED 广播豁免；
 * 2. 部分 ROM（MIUI 等）有自启动管理，需用户在系统设置里允许本应用自启动——
 *    广播收不到时无解，只能引导用户放行（设置页「使用说明」已提示加入电池白名单）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        // 远程开关开着才自启（与 RemoteControlService.onStartCommand 的开关自检一致）
        val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (!enabled) return
        try {
            context.startForegroundService(Intent(context, RemoteControlService::class.java))
        } catch (e: Exception) {
            // 极少数 ROM 限制开机前台服务启动时忽略（用户手动打开 APP 兜底）
        }
    }

    companion object {
        /** 与 RemoteControlService 共用同一份 prefs（开关状态同源） */
        private const val PREFS_NAME = "remote"
    }
}
