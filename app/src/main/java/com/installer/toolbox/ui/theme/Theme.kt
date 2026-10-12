package com.installer.toolbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 浅色主题色板：科技蓝主色 + 微灰蓝底（统一卡片/背景层次） */
private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FF),
    onPrimaryContainer = Color(0xFF0A2C5C),
    secondary = TealAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0F0EA),
    onSecondaryContainer = Color(0xFF0A3B34),
    tertiary = Color(0xFF5C6BC0),
    tertiaryContainer = Color(0xFFE0E4FA),
    background = LightBg,
    onBackground = Color(0xFF16233B),
    surface = LightSurface,
    onSurface = Color(0xFF16233B),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF4A5A75),
    // 容器系列（NavigationBar 背景等）：浅蓝白，避免 Material3 默认淡紫粉
    surfaceContainer = Color(0xFFEEF3FB),
    surfaceContainerHigh = Color(0xFFE6EDF8),
    surfaceContainerHighest = Color(0xFFDFE8F5),
    outline = Color(0xFF8A97AD),
    error = Color(0xFFD32F2F),
    errorContainer = Color(0xFFFFDAD6)
)

/** 深色主题色板：深蓝科技底（#0A1428 系） */
private val DarkColors = darkColorScheme(
    primary = PrimaryLightBlue,
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF154E8F),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF4DB6AC),
    secondaryContainer = Color(0xFF17524C),
    tertiary = Color(0xFF9FA8DA),
    background = TechDarkBg,
    onBackground = Color(0xFFE2E8F5),
    surface = TechDarkSurface,
    onSurface = Color(0xFFE2E8F5),
    surfaceVariant = TechDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF9FB0CC),
    surfaceContainer = Color(0xFF15233F),
    surfaceContainerHigh = Color(0xFF1A2A4A),
    surfaceContainerHighest = Color(0xFF20314F),
    outline = Color(0xFF6C7FA3),
    error = Color(0xFFEF5350),
    errorContainer = Color(0xFF5C1A1A)
)

/** 全局形状：统一圆角（科技感：卡片 14-20、按钮/小元素 10） */
private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

/**
 * 全局主题：跟随系统深色模式；品牌深蓝科技配色 + 统一圆角。
 */
@Composable
fun ToolboxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
