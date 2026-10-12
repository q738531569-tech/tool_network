package com.installer.toolbox.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 远程升级安装完成后自动重启（2026-08-18）。
 *
 * 机制：App 被覆盖安装（pm install -r）后，系统向本包发送 [Intent.ACTION_MY_PACKAGE_REPLACED]
 * 专属广播并拉起本 receiver——这是 Android 官方推荐的"应用自更新后自动重启"方式。
 * （此前尝试 root nohup 后台子进程——pm install 杀 App 进程时连带杀 su 子进程（同一 cgroup），
 * 无效；AlarmManager——MIUI 禁止后台 Activity 启动，被拦截。）
 *
 * 2026-08-19 修复：原实现只 startActivity 拉起 MainActivity——Android 10+ 后台 Activity
 * 启动限制会静默拦截（实测：升级后进程活着但 MainActivity 没起来、服务没连服务器、远程离线）。
 * 改为优先 startForegroundService 直接拉起 RemoteControlService——Android 12+ 对
 * MY_PACKAGE_REPLACED 广播启动前台服务有豁免，保证升级后服务必连服务器、设备自动上线；
 * Activity 作为第二优先（ROM 放行时顺带亮 UI，被拦也无所谓）。
 */
class UpdateRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            // 一：拉起远程控制前台服务（MY_PACKAGE_REPLACED 豁免后台 FGS 启动限制）
            context.startForegroundService(Intent(context, RemoteControlService::class.java))
        } catch (e: Exception) {
            // 服务启动失败不阻塞 UI 拉起
        }
        try {
            // 二：拉起主界面（后台 Activity 限制拦截时忽略，服务已在跑即可保持在线）
            val i = Intent(context, com.installer.toolbox.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Exception) {
            // 后台启动被 ROM 限制时忽略（服务已拉起，用户可稍后手动打开）
        }
    }
}
