package com.installer.toolbox.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.installer.toolbox.ui.theme.TechBrightBlue
import com.installer.toolbox.ui.theme.TechDeepBlue

/**
 * 首页工作台：顶部 APP 名 + 副标题，下方按分类展示工具入口卡片。
 * 整页 LazyColumn 可滚动；点击卡片回调 [onNavigate] 进入二级功能页。
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- 品牌 Header（跟随系统亮暗：浅色=浅蓝渐变+深蓝字；深色=深蓝渐变+白字）----
        item {
            val isDark = isSystemInDarkTheme()
            val headerGradient = if (isDark) {
                listOf(Color(0xFF050F24), Color(0xFF0E2A55))
            } else {
                listOf(Color(0xFFE3EDFB), Color(0xFFC9DCF7))
            }
            val titleColor = if (isDark) Color.White else Color(0xFF0D2B56)
            val subColor = if (isDark) Color.White.copy(alpha = 0.8f) else Color(0xFF0D2B56).copy(alpha = 0.75f)
            val decoColor = if (isDark) Color.White.copy(alpha = 0.22f) else Color(0xFF1565C0).copy(alpha = 0.28f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(headerGradient)
                    )
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                Column {
                    Text(
                        text = "装维通",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = titleColor
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "局域网检查 · 摄像头配置 · AI 排障",
                        style = MaterialTheme.typography.bodyMedium,
                        color = subColor
                    )
                }
                // 右侧装饰：大 Wi-Fi 信号图标（半透明，科技点缀）
                Icon(
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = decoColor,
                    modifier = Modifier
                        .size(88.dp)
                        .align(Alignment.CenterEnd)
                )
                // 左上角青色光点
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .padding(top = 14.dp, end = 18.dp)
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.35f) else Color(0xFF1565C0).copy(alpha = 0.35f),
                            RoundedCornerShape(50)
                        )
                )
            }
        }

        // ---- 分类入口 ----
        ToolEntries.categories.forEach { category ->
            item {
                // 分类标题：左侧主色竖条标记 + 标题（科技感）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            items(category.tools, key = { it.id }) { tool ->
                ToolCard(tool = tool, onClick = { onNavigate(tool.route) })
            }
        }
    }
}

/** 单个工具入口卡片：图标（深蓝渐变底 + 白色图标，科技感）+ 名称 + 描述（2 行截断） */
@Composable
private fun ToolCard(tool: ToolEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            // 浅色容器色：surfaceVariant 淡色变体，贴合科技感浅底
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(listOf(TechDeepBlue, TechBrightBlue))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.name,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tool.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
