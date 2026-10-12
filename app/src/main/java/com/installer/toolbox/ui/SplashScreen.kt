package com.installer.toolbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.installer.toolbox.ui.theme.TechBrightBlue
import com.installer.toolbox.ui.theme.TechDeepBlue
import kotlinx.coroutines.delay

/**
 * 品牌开屏（Splash）：深蓝科技渐变底 + 居中 Wi-Fi logo + "装维通" 名称。
 * 与启动图标视觉一致；约 1.5 秒淡入后由 MainActivity 切换到主界面。
 */
@Composable
fun SplashScreen() {
    val context = LocalContext.current
    // 动态取版本号（PackageManager，避免硬编码）
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    var visible by remember { mutableStateOf(false) }
    // 淡入动画
    LaunchedEffect(Unit) {
        visible = true
        delay(1500)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(TechDeepBlue, TechBrightBlue))),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(600))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 居中 logo：白 Wi-Fi 图标（对应启动图标主体）
                Icon(
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(96.dp)
                )
                // 名称
                Text(
                    text = "装维通",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
                // 副标题
                Text(
                    text = "局域网检查 · 摄像头配置 · AI 排障",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // 版本
                if (versionName.isNotBlank()) {
                    Text(
                        text = "v$versionName",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }
        }
    }
}
