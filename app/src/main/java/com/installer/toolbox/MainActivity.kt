package com.installer.toolbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.installer.toolbox.remote.RemoteControlService
import com.installer.toolbox.ui.MainScreen
import com.installer.toolbox.ui.SplashScreen
import com.installer.toolbox.ui.theme.ToolboxTheme
import kotlinx.coroutines.delay

/**
 * 单 Activity 入口：先显示品牌开屏（logo，1.5 秒淡入），再加载主界面（底部导航）。
 * Android 12+ 另有系统级 Splash 主题（values-v31/themes.xml）做启动瞬间的品牌化。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 自动拉起远程控制服务（用户拍板"App 启动时视开关状态拉起"）：开关开着但服务没跑时补拉——
        // 补 force-stop/设备重启后服务不自动恢复的洞（START_STICKY 只管系统回收场景，force-stop 后
        // 一切组件停止，必须等用户再开 App）；用户点图标冷启动时 App 在前台，满足 Android 12+
        // 前台服务启动限制。开关关着不拉（服务自身 onStartCommand 还有 enabled 自检双保险兜底）。
        startRemoteControlIfEnabled()
        setContent {
            ToolboxTheme {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(1600)
                    showSplash = false
                }
                if (showSplash) {
                    SplashScreen()
                } else {
                    MainScreen()
                }
            }
        }
    }

    /** 远程开关开着则拉起前台服务（prefs 名与 RemoteControlService.PREFS_NAME 一致；服务幂等，已在跑则只重连检查） */
    private fun startRemoteControlIfEnabled() {
        val enabled = getSharedPreferences("remote", MODE_PRIVATE).getBoolean("enabled", false)
        if (enabled) {
            startForegroundService(Intent(this, RemoteControlService::class.java))
        }
    }
}
