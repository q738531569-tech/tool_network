package com.installer.toolbox.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.installer.toolbox.core.agent.DeepSeekClient
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.ota.ApkUpdater
import com.installer.toolbox.ota.DEFAULT_UPDATE_URL
import com.installer.toolbox.ota.UpdateChecker
import com.installer.toolbox.ota.UpdateInfo
import com.installer.toolbox.remote.OemKeepAliveGuide
import com.installer.toolbox.remote.RemoteControlService
import com.installer.toolbox.ui.navigation.Routes
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 设置页：DeepSeek API Key 管理 + 反馈/诊断入口 + 关于信息。
 * Key 保存到 SharedPreferences（"agent" 文件），AI 页会同步读取。
 */
@OptIn(ExperimentalMaterial3Api::class) // 模型下拉框 ExposedDropdownMenuBox（material3 1.3 实验 API）
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val apiKey by viewModel.apiKey.collectAsState()
    // 输入框默认留空（不回显已保存的 Key）：未填写=不改动，填了新值才保存
    var keyInput by rememberSaveable { mutableStateOf("") }
    val currentModel by viewModel.modelName.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val modelsLoading by viewModel.modelsLoading.collectAsState()
    var modelInput by rememberSaveable { mutableStateOf(currentModel) }
    // 模型下拉框展开状态与自定义输入模式（选项由 API /models 返回 + 当前模型 + 默认模型兜底）
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var customModelMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 进设置页时拉取 API 可用模型列表（选项动态化；无 Key 或失败时下拉用兜底选项）
    LaunchedEffect(Unit) { viewModel.refreshModels() }
    // 【需求 2026-08-17】每次进入设置页刷新账户余额（API Key 旁显示）
    LaunchedEffect(Unit) { viewModel.refreshBalance() }
    // 关于区版本号动态读取（与 build.gradle versionName 保持一致，避免写死过期）
    val aboutVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ---- 页标题 ----
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))

        // ---- 反馈与诊断入口 ----
        Text(
            text = "帮助与反馈",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextButton(
                    onClick = { onNavigate(Routes.FEEDBACK) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("💬 反馈与建议", modifier = Modifier.weight(1f))
                    Text("›")
                }
                Text(
                    text = "提需求/报问题，一键提交到服务器，我们会持续优化",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { onNavigate(Routes.DIAGNOSTIC_INFO) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔍 诊断信息", modifier = Modifier.weight(1f))
                    Text("›")
                }
                Text(
                    text = "设备/版本/使用统计/崩溃日志，出问题时可导出给开发者",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        // ---- 关于区 ----
        Text(
            text = "关于",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "装维通",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "版本 $aboutVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "装维通：局域网发现 / 诊断 / 抓包 / 摄像头 / AI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "作者：彭果",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "长沙县江背装维",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "© 2026 彭果 · 装维专用工具，仅限授权场景使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                // 免责声明（跟随"关于"区；覆盖全功能现状，主动操作类功能必须取得客户授权）
                Text(
                    text = "免责声明：本工具仅限装维/网络维护的授权场景使用。以下主动操作必须事先取得设备/网络所有者（客户）明确授权：静默断网与流量审计（ARP 欺骗/信道干扰）、PCDN 检测与借道审计、摄像头恢复出厂与弱口令探测、root 命令执行、局域网唤醒等。AI 助手调用 DeepSeek 付费接口（费用由所配 API Key 账户承担，对话前请确认已理解计费）；SOS 紧急求救会真实发送短信/拨打电话，请仅在真实紧急情况使用。因违规使用产生的一切后果由使用者自行承担。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(6.dp))
                // 兼容性说明（跟随"关于"区）
                Text(
                    text = "兼容性：支持 Android 8.0 及以上系统；64 位设备可完整使用 root 增强功能（环路检测 / nmap / 流量审计 / 静默模式等），32 位老设备 root 工具不可用、其余功能正常。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        // ---- 软件更新区（OTA：检查更新 / 下载 / 安装）----
        Text(
            text = "软件更新",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        UpdateSection()
        Spacer(Modifier.height(24.dp))

        // ---- DeepSeek API Key 区 ----
        Text(
            text = "DeepSeek API Key",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("API Key") },
            placeholder = { Text("未填写（留空=不修改已保存的 Key）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        // 已存值提示：掩码展示或"未配置"
        Text(
            text = if (apiKey.isNotBlank()) "已保存：${maskKey(apiKey)}" else "未配置",
            style = MaterialTheme.typography.bodySmall,
            color = if (apiKey.isNotBlank()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
        // 账户余额（每次进入设置页刷新；未配 Key/查询失败不显示）
        val balance by viewModel.balance.collectAsState()
        if (balance != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "💰 账户余额：$balance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    viewModel.saveKey(keyInput)
                    keyInput = "" // 保存后输入框清空留空
                },
                enabled = keyInput.isNotBlank()
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = {
                    viewModel.clearKey()
                    keyInput = ""
                },
                enabled = apiKey.isNotBlank()
            ) {
                Text("清除")
            }
        }
        Spacer(Modifier.height(16.dp))
        // 模型选择（下拉框：选项由 DeepSeek /models 接口返回，非硬编码；换模型/升级不用发版）
        Text(
            text = "模型选择",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        // 下拉选项 = API 返回列表 + 当前已保存模型 + 默认模型（去重兜底，保证任何情况下有得选）
        val modelOptions = remember(availableModels, currentModel) {
            (availableModels + currentModel + DeepSeekClient.DEFAULT_MODEL).distinct()
        }
        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = { modelMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = currentModel,
                onValueChange = {},
                readOnly = true,
                label = { Text("模型") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                supportingText = {
                    Text(
                        if (modelsLoading) "模型列表获取中…"
                        else if (availableModels.isEmpty()) "选项由 API 返回（需先配置 Key）；当前 ${currentModel}，可点开切换或自定义"
                        else "共 ${availableModels.size} 个可用模型（API 返回），点击切换"
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false }
            ) {
                modelOptions.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = m,
                                    fontWeight = if (m == currentModel) FontWeight.Bold else FontWeight.Normal
                                )
                                if (m == currentModel) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        onClick = {
                            viewModel.saveModelName(m)
                            modelMenuExpanded = false
                            customModelMode = false
                        }
                    )
                }
                // 自定义入口：API 列表之外的模型手动输入
                DropdownMenuItem(
                    text = { Text("自定义模型…", color = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        modelInput = currentModel
                        customModelMode = true
                        modelMenuExpanded = false
                    }
                )
            }
        }
        // 自定义模式：手输模型名（下拉列表之外的模型）
        if (customModelMode) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                label = { Text("自定义模型名") },
                supportingText = { Text("填写 DeepSeek 模型 id，如 deepseek-v4-pro") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        viewModel.saveModelName(modelInput)
                        customModelMode = false
                    },
                    enabled = modelInput.trim().isNotBlank()
                ) {
                    Text("保存并启用")
                }
                TextButton(onClick = { customModelMode = false }) { Text("取消") }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 单次回复最大 token 数（可配不写死：模型更新/长回复需求随时可调，改完立即生效）
        val agentPrefs = remember { context.getSharedPreferences("agent", Context.MODE_PRIVATE) }
        var maxTokensText by remember { mutableStateOf(agentPrefs.getInt("max_output_tokens", 256000).toString()) }
        OutlinedTextField(
            value = maxTokensText,
            onValueChange = { v ->
                maxTokensText = v.filter { it.isDigit() }
                val n = v.filter { it.isDigit() }.toIntOrNull()
                if (n != null && n in 1024..384000) {
                    agentPrefs.edit().putInt("max_output_tokens", n).apply()
                    DeepSeekClient.maxOutputTokens = n
                }
            },
            label = { Text("单次回复最大长度（token）") },
            supportingText = { Text("默认 256000（官方上限 384K）；回复被截断就调大，想省费用就调小，改完立即生效") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        // 思考强度（reasoning_effort 可配：思考与正文共享输出预算，多工具轮次思考过长会挤占正文）
        val effortOptions = listOf(
            "off" to "关（最快最省，正文预算全给回复）",
            "low" to "低（省输出预算，回复更不易截断）",
            "high" to "高（默认）",
            "max" to "最高（推理最强，最易截断）"
        )
        var effortMenu by remember { mutableStateOf(false) }
        val currentEffort = agentPrefs.getString("reasoning_effort", "high") ?: "high"
        ExposedDropdownMenuBox(
            expanded = effortMenu,
            onExpandedChange = { effortMenu = it }
        ) {
            OutlinedTextField(
                value = effortOptions.firstOrNull { it.first == currentEffort }?.second ?: "高（默认）",
                onValueChange = {},
                readOnly = true,
                label = { Text("思考强度") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = effortMenu) },
                supportingText = {
                    Text("思考与正文共享输出预算：多工具轮次回复被截断时调『低』")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = effortMenu,
                onDismissRequest = { effortMenu = false }
            ) {
                effortOptions.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            agentPrefs.edit().putString("reasoning_effort", value).apply()
                            DeepSeekClient.reasoningEffort = value
                            effortMenu = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 注册引导：platform.deepseek.com 为可点击链接（浏览器打开）
        val uriHandler = LocalUriHandler.current
        Text(
            text = buildAnnotatedString {
                append("用于 AI 诊断助手（DeepSeek v4-flash），仅存本机。获取方式：")
                withLink(LinkAnnotation.Clickable("https://platform.deepseek.com") {
                    uriHandler.openUri("https://platform.deepseek.com")
                }) {
                    // 只给链接部分上超链接蓝 + 下划线（Text 级 color 保持普通色，避免整段变蓝）
                    pushStyle(SpanStyle(color = Color(0xFF1565C0), textDecoration = TextDecoration.Underline))
                    append("platform.deepseek.com")
                    pop()
                }
                append(" 注册登录 → API Keys → 创建新 Key → 粘贴到上方输入框保存。")
                append("\n⚠ API 为付费服务，需在 platform.deepseek.com 充值后方可使用；Key 请妥善保管，仅存本机。")
            },
            style = MaterialTheme.typography.bodySmall,
            // 整段保持普通说明色，链接部分在 SpanStyle 内单独上蓝
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // ---- 远程控制区（M1：通道打通，前台服务 + WebSocket 注册/心跳/状态上报）----
        Text(
            text = "🛰 远程控制",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        RemoteControlCard()

        Spacer(Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        // ---- 运行环境（root 状态自适应提示）----
        Text(
            text = "运行环境",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val rootAvailable = remember { RootShell.isAvailable() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (rootAvailable) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                else androidx.compose.ui.graphics.Color(0xFFE65100),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (rootAvailable) "已获取 root 权限（完整功能可用）" else "未检测到 root（部分功能降级）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (rootAvailable) {
                        "系统开关控制、ARP 欺骗检测抓包、流量审计（ARP 中间人）、nmap 深度扫描可用；无 root 时核心功能（发现/诊断/抓包/摄像头/AI）不受影响。"
                    } else {
                        "核心功能（设备发现/诊断/抓包/摄像头/AI 助手）无需 root 即可用；系统开关控制、流量审计、nmap 需 root（Magisk）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // ---- 📖 使用说明区（放在设置页最后，详细功能手册）----
        Text(
            text = "📖 使用说明",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        // 逐条渲染使用说明（每节一张卡片：标题加粗 + 正文）
        USAGE_SECTIONS.forEach { section ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    // 说明正文可选择复制
                    SelectionContainer {
                        Text(
                            text = section.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))
    }
}

/** 掩码展示 Key：sk-***xxxx（过短时只显示位数） */
private fun maskKey(key: String): String =
    if (key.length >= 8) {
        "${key.take(3)}***${key.takeLast(4)}"
    } else {
        "（${key.length} 位）"
    }

/**
 * 远程控制开关卡片：开关状态存 SharedPreferences（"remote"/enabled）；
 * 装维账号 / 密码存 "remote"/account、"remote"/pwd（随 register 帧上报，服务器校验归属）。
 * 开启 → startForegroundService 启动 RemoteControlService；关闭 → stopService 停止。
 */

/** 电池优化白名单引导（远程控制开启后调用）：未加入白名单则弹提示并跳系统设置 */
private fun promptBatteryOptimization(context: Context) {
    try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return // 已在白名单，无需引导
        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:" + context.packageName))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Toast.makeText(context, "请选择「允许」：后台无限制可保证远程控制常驻不断连", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        // 部分 ROM 不支持直达页面：兜底跳应用详情页手动设置（电池 → 无限制）
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + context.packageName))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
@Composable
private fun RemoteControlCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("remote", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    // 账号 / 密码（输入即时落盘，开远程时随 register 帧上报）
    var account by remember { mutableStateOf(prefs.getString("account", "") ?: "") }
    var pwd by remember { mutableStateOf(prefs.getString("pwd", "") ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🛰 远程控制",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "远程排查：开启后本机常驻连接服务器，可在电脑/手机浏览器远程操控本机 AI 助手排查网络（远程对话两端实时同步；本机发起的 root 命令在本机确认，控制端发起的在控制端确认，ARP 类命令免审批）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (checked && (account.isBlank() || pwd.isBlank())) {
                            // 开启前校验：账号 / 密码任一为空则阻止开启并提示
                            Toast.makeText(context, "请先填写账号和密码", Toast.LENGTH_LONG).show()
                        } else {
                            enabled = checked
                            prefs.edit().putBoolean("enabled", checked).apply()
                            if (checked) {
                                // 开启：启动前台服务（Android 8+ 前台服务须用 startForegroundService）
                                context.startForegroundService(Intent(context, RemoteControlService::class.java))
                                // 电池优化引导：未加入白名单时提示并跳转（放现场常驻，防系统冻结杀后台）
                                promptBatteryOptimization(context)
                            } else {
                                // 关闭：停服务（服务 onDestroy 关 socket、停通知）
                                context.stopService(Intent(context, RemoteControlService::class.java))
                            }
                        }
                    }
                )
            }
            // 设备归属提示：账号名显示 / 未填显示「未绑定账号」
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (account.isBlank()) "设备归属：未绑定账号" else "设备归属：$account",
                style = MaterialTheme.typography.bodySmall,
                color = if (account.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            // 电池优化状态提示（常驻小字：未加入白名单时醒目提示，点击可再次跳转设置）
            val batteryIgnored = remember { mutableStateOf(false) }
            val batteryChecked = remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(enabled) {
                if (enabled) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    batteryIgnored.value = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                    batteryChecked.value = true
                } else {
                    batteryChecked.value = false
                }
            }
            if (batteryChecked.value) {
                Text(
                    text = if (batteryIgnored.value) {
                        "✅ 已允许后台无限制，可放心放现场常驻"
                    } else {
                        "⚠ 未允许后台无限制：放现场可能被系统冻结断连。点此去设置（电池 → 无限制）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (batteryIgnored.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable { promptBatteryOptimization(context) }
                        .padding(vertical = 2.dp)
                )
            }
            // 各厂商 ROM 后台常驻引导（自启动管理/后台清理拦截，2026-08-19）：按机型显示放行步骤
            val oemName = remember { OemKeepAliveGuide.detectOem() }
            if (enabled && oemName != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "🔒 常驻引导（$oemName）：${OemKeepAliveGuide.guideText(oemName)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .clickable { OemKeepAliveGuide.openSettings(context) }
                        .padding(vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            // 装维账号输入框
            OutlinedTextField(
                value = account,
                onValueChange = { v ->
                    account = v
                    prefs.edit().putString("account", v).apply()
                },
                label = { Text("账号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), // 英文键盘
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            // 密码输入框（掩码显示）
            OutlinedTextField(
                value = pwd,
                onValueChange = { v ->
                    pwd = v
                    prefs.edit().putString("pwd", v).apply()
                },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), // 密码键盘
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "🌐 控制台地址：http://your-server.example:8090/toolbox/remote/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "放客户家后：在电脑/手机浏览器打开上方控制台地址，登录后连接本机远程排查。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 使用说明单节：标题 + 正文 */
private data class UsageSection(val title: String, val body: String)

/** 设置页"📖 使用说明"完整内容（覆盖全 APP 功能，中文） */
private val USAGE_SECTIONS: List<UsageSection> = listOf(
    // 1. APP 定位
    UsageSection(
        title = "1. APP 定位",
        body = "装维通：面向装维/网管人员的局域网一体化工具，覆盖设备发现、网络诊断、抓包分析、摄像头管理与 AI 智能排障。"
    ),
    // 2. 首页工作台：各功能入口一行说明
    UsageSection(
        title = "2. 首页工作台",
        body = "各功能入口用途：\n" +
            "· SOS 紧急求救（第一位）：🆘 一键求救（声光摩斯+短信群发一步齐发）、🔦 声光摩斯（手电闪烁+高频蜂鸣循环 SOS 码，可随时停）、📩 短信求救（自动附 GPS 定位+地图链接，短信模板可自定义）、📞 一键拨打紧急联系人；紧急联系人可预填 1-3 个；短信/拨号仅在你点击按钮时真实触发\n" +
            "· 设备发现：扫描当前 Wi-Fi 网段在线设备（IP/MAC/厂商/端口/设备名 mDNS）\n" +
            "· 网络诊断：ping（可选 80/443 端口）/ DNS 查询 / 端口扫描，排查连通性问题\n" +
            "· Wi-Fi 分析：查看当前连接信息并扫描周边信号，给出优化建议（信道/信号/频段）\n" +
            "· Wi-Fi 雷达：雷达图实时找 AP（走动看信号强弱判断方位），支持 RTT 测距（米）\n" +
            "· 航空信号检测：查间歇性断网是否因 5G 使用 DFS 信道（与航空雷达共用频段，雷达信号触发 AP 自动避让）\n" +
            "· 抓包分析：捕获并解析本机网络流量（需授权 VPN）\n" +
            "· IP 计算器：子网掩码、网段与可用主机计算\n" +
            "· 远程终端：SSH/Telnet 交互式登录设备执行命令（彩色终端，账密不保存）\n" +
            "· 二维码/条码：扫码识别（相机/相册）+ 码生成（QR/Code128/EAN13）\n" +
            "· ARP 欺骗检测：抓 ARP 包发现 IP-MAC 冲突（需 root；无 root 自动降级为 ARP 表对比）\n" +
            "· 网关/IP 冲突检测：检测多网关（双路由）与一 MAC 多 IP，排查网不好\n" +
            "· BLE 扫描：发现周边蓝牙低功耗设备（识别厂商：小米/华为/苹果等）\n" +
            "· nmap 扫描：深度服务/版本探测（内置，需 root，无需 Termux）\n" +
            "· PCDN 全网检测：先特征层宽进列出可疑设备（特征端口/主机名/多 IP/DHT 协议探测/UPnP 映射；摄像头先识别排除路由误判，但摄像头同样可能跑 PCDN，硬证据照算），再对可疑设备流量审计综合评分确认；点击设备卡片可单独审计\n" +
            "· 流量审计：对单台设备做 ARP 中间人流量统计（需授权；含 IPv6 与 DNS 域名特征）\n" +
            "· 环路检测：tcpdump 抓包统计广播/组播占比，排查环路/广播风暴（需 root）\n" +
            "· 弱网检测：持续测网络稳不稳（丢包/时延/抖动），定位断流卡顿根因；有 root 用真 ICMP ping 更准，无 root 自动回退端口探测（支持一键测抖音/微信/B站/淘宝等 10 个主流平台的网络质量）\n" +
            "· SNMP 面板：查路由器/交换机/打印机等支持 SNMP 的设备信息（名称/系统描述/运行时长/位置/联系人），装维识别设备用；需设备开 SNMP 服务（community 默认 public），无需 root\n" +
            "· 局域网唤醒：输入电脑 MAC 发魔术包远程开机（需电脑 BIOS/系统开启网络唤醒，无需 root）\n" +
            "· 网线测试：网线插设备网口直测线缆质量——断芯/短芯/短路/接触不良/丢包严重一眼看出（需 root+有线网口；网口请先手动开启，被测线对端必须接通电设备如交换机/光猫；测不出具体哪对芯/短路位置，那要万用表/线缆仪）\n" +
            "· 光猫 Web 助手：内嵌浏览器打开光猫管理页（默认自动填当前网络网关，参数区可收起），登录后问 AI『读取光猫页面』可帮你分析设备信息/日志；验证码需手动输入，AI 只读不点危险按钮\n" +
            "· 静默模式：让偷跑流量的设备静默（断网不断 Wi-Fi）或踢下线（仅无线设备），测速更准；支持 标准/洪泛/信道阻塞 三档，对顽固设备可升级强度（信道阻塞会影响同网段其他设备，谨慎使用）；IPv6 网络自动叠加 NDP 欺骗双重断网（需 root+授权，责任自负）\n" +
            "· 摄像头管理：ONVIF/海康摄像头发现与维护\n" +
            "· AI 助手：DeepSeek 智能对话，自动调用工具排查网络问题\n" +
            "· 结果导出：诊断/设备发现/PCDN 检测/流量审计/环路检测/ARP 欺骗检测等页面的结果可点『导出』，保存为 txt 报告到手机 下载/装维通 目录\n"
    ),
    // 3. AI 助手
    UsageSection(
        title = "3. AI 助手",
        body = "· API Key 配置：在设置页填写 DeepSeek API Key（本机加密存储，Android Keystore）\n" +
            "· 模型选择：设置页下拉选择 AI 模型——选项由 DeepSeek 接口实时返回（如 deepseek-v4-flash 快又省 / deepseek-v4-pro 更强），也可选『自定义模型…』手动填写模型名；选中即生效，AI 页顶部会显示当前模型\n" +
            "· 自由对话：直接描述网络问题，AI 结合现场数据给出诊断\n" +
            "· 工具调用：AI 可自主调用扫描、ping、摄像头、系统开关等工具并继续分析\n" +
            "· 过程可见：AI 的思考过程与每个工具的执行过程全程显示（思考完自动折叠、点开可看；工具调用与输出结果可折叠查看；调用每个工具前 AI『准备说的话』也会实时显示，不用干等），排查过程一目了然，便于反馈改进\n" +
            "· 断线恢复：意外退出/断网消息已实时保存，重进自动接上并提示上次中断；AI 页显示每轮耗时与缓存命中\n" +
            "· root 命令（需 root）：AI 可执行任意 root shell 命令排查疑难问题；审批三档——关闭 / 每步审批（默认，每条命令弹窗确认）/ 本会话允许（危险），档位按钮在『AI 诊断助手』标题后面；无 root 时该能力自动不可用\n" +
            "· AI 记忆（按会话隔离）：AI 发现网络拓扑/排查结论等重要信息会主动记忆，本会话后续对话自动带着；每个会话对应一个客户，记忆互不串用；标题旁 🧠 按钮可查看/删除本会话记忆，删除会话时其记忆一并清除（会提示条数）；不记忆密码密钥\n" +
            "· 凭据保险库（密码加密专存）：AI 发现现场密码（摄像头/路由器/WiFi/ONVIF 密码等）会自动存进本机加密保险库（AES 加密落盘，仅本设备可解密），并把对话记录里的密码明文打码成 ***——翻记录文件也看不到密码；记忆里只记『密码已存保险库』的位置，不记值；保险库按会话隔离：清空会话保留（重聊自动接上，不用重输密码）、删除会话连带清除；🧠 弹窗可查看/单删凭据（只显示用途与设备，不显示值）\n" +
            "· 资料档案（.md 自动沉淀）：AI 排查告一段落会**自动**把完整资料/报告写成 .md 档案（短结论记记忆、长资料写档案，自动区分无需吩咐）；清空会话后重新聊天时 AI 自动列出档案并读回上下文；档案按会话隔离，删除会话时连带清除\n" +
            "· 清空会话：清空当前会话全部消息、上下文重新开始；记忆与 md 档案保留；清空前会弹确认提示\n" +
            "· 智能压缩：长对话自动控制上下文大小——超过上限一半（约 50 万 token）自动把更早历史压缩成摘要继续携带，接近上限（约 80 万）只保留最近几轮；回复被截断会自动提示『可让我继续补充』，内容太长报错会自动降级重试一次\n" +
            "· 输出上限与思考强度（设置页可调）：单次回复最大长度默认 256000（官方上限 384K），回复被截断就调大、想省费用就调小，改完立即生效；思考强度四档——关（最快最省，正文预算全给回复）/低（更不易截断）/高（默认）/最高（推理最强），多工具轮次回复被截断时调『低』\n" +
            "· 缓存命中：DeepSeek 上下文自动缓存，AI 页显示命中率（输入费用约 1/10）\n" +
            "· 多会话：+ 新建、切换、删除会话，历史自动保存；会话列表两行展示（标题完整显示 + 第二行时间与清空/重命名/删除按钮）；标题可编辑（点击 ✏️ 重命名，手动命名后不再随新消息自动变化）\n" +
            "· root 系统控制：可开关 Wi-Fi/蓝牙/移动数据/飞行模式；注意开启飞行模式会立刻断网，导致 AI 对话中断！\n" +
            "· 报告导出：AI 可以把结果导出为报告文件（export_report），保存到手机 下载/装维通 目录\n" +
            "· 反馈与诊断：可直接说『帮我反馈一个建议』（submit_feedback）或『查一下诊断信息』（get_diagnostic_info）\n" +
            "· 远程控制：设置页开启『🛰 远程控制』后，本机常驻连接服务器（App 重启自动续连，放现场不用管），可在电脑/手机浏览器远程操控本机 AI 助手排查网络（远程期间本机弹窗静默，操作在控制端）；本 App 首页网络工具区也有『远程控制台』入口，登录后可反过来控制别的 PDA\n"
    ),
    // 4. 摄像头
    UsageSection(
        title = "4. 摄像头",
        body = "· 发现：ONVIF WS-Discovery 自动发现局域网摄像头，兼容海康 SADP/ISAPI\n" +
            "· 弱口令探测：逐个试常见默认口令（非爆破，成功即提示改密）\n" +
            "· 免旧密码重置：生成安全码 → 提交重置，无需知道旧密码\n" +
            "· 管理操作：改 IP、改密码、重启、恢复出厂设置"
    ),
    // 5. root 工具
    UsageSection(
        title = "5. root 工具",
        body = "· ARP 欺骗检测：内置 tcpdump 抓包检测；无 root 自动降级为 ARP 表对比（精度较低）\n" +
            "· 环路检测：tcpdump 抓链路层帧统计广播/组播占比与包速率（抓包时长可调，默认 8 秒）；无 root 时不可用，可改用抓包分析（免 root）看本机广播信号\n" +
            "· 流量审计：ARP 中间人统计目标上下行，需 root + 设备所有者授权（内置 arpspoof/tcpdump，无需 Termux）\n" +
            "· nmap：内置 nmap，root 即可使用深度扫描\n" +
            "· BLE 扫描：需蓝牙与定位权限\n" +
            "· 静默模式：双向 ARP 欺骗（同时骗目标与网关）让目标静默断外网（有线/无线均有效，目标启用 ARP 防火墙也断——网关侧兜底；仅路由器侧静态绑定/AP 隔离时可能无效）或 802.11 踢下线（仅无线设备，多数 Android 芯片不支持注入）；仅限授权设备，进页面需确认免责声明，责任由使用者承担"
    ),
    // 6. 权限与 root 说明
    UsageSection(
        title = "6. 权限与 root 说明",
        body = "· 定位权限：Wi-Fi 扫描、设备发现必需（系统位置开关也需开启）\n" +
            "· 蓝牙权限：BLE 扫描必需\n" +
            "· root（Magisk/KernelSU）：解锁系统开关控制、ARP 欺骗检测（完整抓包模式）、环路检测、流量审计与静默模式/踢下线\n" +
            "· 无 root 时：ARP 检测降级为 ARP 表对比、环路检测/流量审计与系统开关不可用并提示原因，其余功能不受影响"
    ),
    // 6.5 反馈与诊断
    UsageSection(
        title = "6.5 反馈与诊断",
        body = "· 反馈与建议：设置页进入，写反馈内容一键提交到服务器（无需邮箱/微信），开发者据此持续优化；弱网/离线现场提交失败时可用『复制留档』先保存\n" +
            "· 诊断信息：查看设备/版本/使用统计/崩溃日志，出问题时可『导出诊断包』发给开发者（『含网络』导出会附带 Wi-Fi 名称/网关等现场信息，发送前请确认可外发）\n" +
            "· 自动收集：App 启动时静默上报运行信息（版本/机型/使用统计/崩溃日志，不含网络拓扑隐私，≥12 小时一次），仅用于优化分析"
    ),
    // 7. 常见问题
    UsageSection(
        title = "7. 常见问题",
        body = "· 抓包：需在系统设置中授权本应用的 VPN 连接\n" +
            "· 断网排查：先用设备发现确认在线，再 ping 网关/外网定位断点\n" +
            "· PCDN 检测原理（检出从严，漏报优先于误报）：特征层——特征端口（迅雷/网心/BT 段）/主机名关键词/一 MAC 多 IP/BT DHT 协议探测（确认 P2P 节点）/主路由 UPnP 映射表核对（摄像头先识别排除路由误判，摄像头也可能跑 PCDN，硬证据照算）；审计层——上行占比、目的 IP 指向 IDC、并发连接、STUN、UPnP、DNS 域名自报等综合评分（总分≥4 且至少 1 个特征命中即疑似，需人工复核），先宽进列可疑再流量确认\n" +
            "· 内置依赖：nmap/tcpdump/arpspoof 已内置，无需安装 Termux；root 权限即可使用全部功能"
    )
    // 免责声明已移到顶部"关于"区
)

// ==================== 软件更新区（OTA）====================

/**
 * 软件更新区：更新源 URL 配置 + 检查更新 + 下载安装。
 *
 * - 更新源 URL 存 SharedPreferences（"ota" 文件、"update_url" 键），输入时实时保存
 * - 检查 / 下载 / 安装全部在后台协程执行（内部已切 Dispatchers.IO），loading 期间禁用按钮
 * - 安装 root 优先（静默），非 root 自动降级系统安装器
 */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 当前版本号：PackageManager 读取（app 模块未开 buildConfig，效果等同 BuildConfig.VERSION_NAME）
    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    // 操作状态：checking 检查中 / downloading 下载中 / installing 安装中
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }            // 下载进度 0..1
    var progressText by remember { mutableStateOf("") }        // 下载进度文案（百分比 + 大小）
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) } // 最近一次检查到的更新信息
    var checkResult by remember { mutableStateOf<String?>(null) }    // 检查结果文案
    var installResult by remember { mutableStateOf<String?>(null) }  // 安装结果文案

    val busy = checking || downloading || installing

    // root 命令通道：非 root 时 RootShell.run 返回"未找到 su"（不含 uid=0），自动降级系统安装器
    val rootRun: ((String) -> String)? = remember { { cmd -> RootShell.run(cmd) } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 当前版本
            Text(
                text = "当前版本：$currentVersion",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))

            // 检查更新按钮
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            checking = true
                            checkResult = null
                            updateInfo = null
                            installResult = null
                            val result = UpdateChecker.checkUpdate(DEFAULT_UPDATE_URL)
                            checking = false
                            result.onSuccess { info ->
                                updateInfo = info
                                checkResult = if (UpdateChecker.isNewer(currentVersion, info.latestVersion)) {
                                    "发现新版本 ${info.latestVersion}"
                                } else {
                                    "已是最新版本"
                                }
                            }.onFailure { e ->
                                checkResult = "检查更新失败：${e.message}"
                            }
                        }
                    },
                    enabled = !busy
                ) {
                    Text("检查更新")
                }
            }
            Spacer(Modifier.height(12.dp))

            // 检查中提示（不确定进度条）
            if (checking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "正在检查更新…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            // 检查结果文案（发现新版=蓝 / 已最新=绿 / 失败=红）
            checkResult?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        text.startsWith("发现新版本") -> MaterialTheme.colorScheme.primary
                        text.startsWith("已是最新版本") -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            // 有新版本：展示更新日志 + 下载并安装按钮
            val info = updateInfo
            if (info != null && !checking && UpdateChecker.isNewer(currentVersion, info.latestVersion)) {
                Text(
                    text = "更新日志",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = info.changelog ?: "（无更新说明）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            downloading = true
                            installResult = null
                            // 进度节流：仅百分比变化时更新 UI（回调在 IO 线程触发）
                            var lastPercent = -1
                            val file = ApkUpdater.download(
                                context = context,
                                baseUrl = DEFAULT_UPDATE_URL,
                                info = info,
                                onProgress = { downloaded, total ->
                                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        scope.launch {
                                            progress = if (total > 0) downloaded.toFloat() / total else 0f
                                            progressText = if (total > 0) {
                                                "正在下载 $percent%（${formatSize(downloaded)} / ${formatSize(total)}）"
                                            } else {
                                                "正在下载… ${formatSize(downloaded)}"
                                            }
                                        }
                                    }
                                }
                            )
                            downloading = false
                            if (file == null) {
                                installResult = "下载失败，请检查网络与更新源"
                                return@launch
                            }
                            // 下载完成 → 安装（root 静默优先，非 root 调系统安装器）
                            installing = true
                            installResult = ApkUpdater.install(context, file, rootRun)
                            installing = false
                        }
                    },
                    enabled = !busy
                ) {
                    Text("下载并安装")
                }
                Spacer(Modifier.height(12.dp))
            }

            // 下载中：百分比进度条 + 进度文案
            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            // 安装中：不确定进度条
            if (installing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "正在安装…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            // 安装结果提示（失败=红，成功=绿）
            installResult?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (text.contains("失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color(0xFF2E7D32)
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            // 更新记录（静态维护，每次发版在此追加；与 OTA 弹窗的 changelog 互补）
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = "📋 更新记录（点击展开）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            // 更新记录：最近 5 条默认展示（可逐条展开），更早的历史版本收进"历史版本"折叠区
            val recentLog = UPDATE_LOG.take(5)
            val historyLog = UPDATE_LOG.drop(5)
            var historyExpanded by remember { mutableStateOf(false) }
            recentLog.forEachIndexed { index, log ->
                // 折叠：默认仅显示版本号行，点击展开/收起描述（避免长文本叠加影响阅读）
                var expanded by remember { mutableStateOf(index == 0) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "${if (expanded) "▼" else "▶"} ${log.version}（${log.date}）",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (expanded) {
                        Text(
                            text = log.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // 历史版本折叠区（仅保留最近 5 条在明面，其余全部折叠）
            if (historyLog.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { historyExpanded = !historyExpanded }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "${if (historyExpanded) "▼" else "▶"} 历史版本（${historyLog.size} 条）",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (historyExpanded) {
                        historyLog.forEach { log ->
                            var hExpanded by remember { mutableStateOf(false) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { hExpanded = !hExpanded }
                                    .padding(vertical = 3.dp)
                            ) {
                                Text(
                                    text = "${if (hExpanded) "▼" else "▶"} ${log.version}（${log.date}）",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (hExpanded) {
                                    Text(
                                        text = log.desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 更新记录条目（版本号 + 发布日期 + 简介；发版时维护） */
private data class VersionLog(val version: String, val date: String, val desc: String)

/** 更新记录（按版本从新到旧；项目 2026-08-11 起快速迭代，日期均为发布日） */
private val UPDATE_LOG: List<VersionLog> = listOf(
    VersionLog("1.6.19", "2026-08-18", "①**远程强制升级**：Web 控制台/内置控制台选中设备点「升级」，自动检查更新→下载→静默安装→重启应用，全程可见进度（设备执行任务中会拒绝，避免打断）②光猫 Web 助手**自动切换网关**：换网络后打开自动加载新网关管理页，不再停留旧页面 ③AI 读光猫页面**等加载完成**：页面没加载完会提示「还在加载请稍后重读」，不再拿残缺数据误分析 ④Wi-Fi 雷达**测距修复**：不再显示离谱的十万米，异常自动退回信号强弱估算"),
    VersionLog("1.6.17", "2026-08-18", "攒积大版本：①**停止按钮实时生效**——AI 干活时按停止立即中断（之前要等当前步骤跑完）②**设置页显示账户余额**——API Key 旁直接看还剩多少钱，进页自动刷新 ③**首页右上角快捷扫码**——扫一扫图标一键进扫码识别 ④**网线测试**——网线插设备网口直测线缆质量：断芯/短芯/短路/接触不良/**丢包严重**一眼看出（需 root+有线网口，被测线对端要接通电设备；测不出具体哪对芯/短路位置，那要万用表）⑤**光猫 Web 助手**——内嵌浏览器打开光猫管理页（默认自动填当前网关，参数区可收起），AI 可**读取页面内容**帮你分析、也可**点击页面元素**（登录/翻页/查状态全自动，重启/恢复出厂等危险操作 AI 不做），切到 AI 助手网页继续后台运行不中断，换光猫可一键清除缓存 ⑥**AI 回复不再莫名截断**——多工具排查后网络波动导致的中断会明确提示『可让我继续』；AI 工具调用**不再限制 8 轮**，复杂排查一路查到出结论（死循环按停止即可）⑦远程会话历史不再截断（长思考/长回复完整显示）⑧AI 工具增至 54 个（网线测试/读网页/操作网页/执行 JS）"),
    VersionLog("1.6.16", "2026-08-17", "修复：网络波动重连后，正在进行的对话轮次不再消失——补发审批后如果消息没显示，会自动重新拉取会话历史恢复完整对话（之前需要手动断开重新绑定设备才能看到）"),
    VersionLog("1.6.15", "2026-08-17", "远程控制网络波动加固（微信式送达确认）：①App 内置远程控制台与 Web 控制页一样，断线期间的操作（发消息/切会话/停止/审批等）自动暂存，重连后自动补发，不再悄悄丢失 ②审批请求断线丢失后，重连会自动向设备拉回未决审批并重新弹窗——不会出现『点了审批但设备还在等』 ③服务端新增送达确认：消息/审批发给设备成功有回执，失败会明确提示原因（如『设备离线』）④重连后设备正在执行任务会有提示（可能等待 root 审批）"),
    VersionLog("1.6.14", "2026-08-17", "远程控制断线重连优化（网络波动不再影响体验）：①设备 AI 正在干活时，控制端重连也能正常加载当前会话历史（之前会被『正在执行任务』挡住，消息区空白/不刷新）②控制页新增设备掉线/上线实时提示（谁掉了、谁回来了一眼可见，带防抖不刷屏）③控制页重连成功有『已恢复』提示，设备列表显示『执行中』状态④重连后自动拉取最新消息，不再需要手动刷新"),
    VersionLog("1.6.13", "2026-08-17", "修复 root 命令审批路由：远程控制开着时，本机（PDA 屏）发起的 root 命令现在直接在本机弹窗确认，不再发到远程控制端等审批——装维现场直接操作 PDA 排查时不会被远端卡住（控制端发起的 root 命令仍在控制端确认）"),
    VersionLog("1.6.12", "2026-08-17", "扫码连 WiFi（新功能）：①扫别的设备分享的 WiFi 二维码（手机分享/路由器管理页/贴纸上的码）一键连网，装维到客户家不用再手输密码 ②支持摄像头实时扫，也支持从相册选 WiFi 二维码图片识别 ③有 root 的设备静默直连（不弹系统确认），无 root 设备走系统连接确认（Android 13+ 会自动申请『附近设备』权限）④『二维码/条码功能』入口说明已更新"),
    VersionLog("1.6.11", "2026-08-17", "AI 对话体验大升级：①思考过程与回复正文全部逐行实时打印（打字机效果，不再整段蹦出）②修复多工具长任务回复被腰斩——工具轮数达到上限后自动追加总结轮，AI 会把结论说完整（不再停在半句话）③思考过程先展示、正式回答出来后才自动收起（可随时点开回看）④正式回答与上方的思考/工具记录隔开一点，层次更清楚"),
    VersionLog("1.6.10", "2026-08-17", "AI 助手全面对标优化：①长对话自动控制上下文——超过一半（约 50 万 token）自动把更早历史压缩成摘要，接近上限（约 80 万）只保留最近几轮，超限报错还会自动降级重试一次，聊再久也不怕②修复：AI 调用工具前说的话现在实时显示（之前被吞掉，只能干等工具）③修复：思考强度选『关』时报错的问题④回复太长自动提示『可继续补充』（之前截断无提示）⑤新增 SSH 交互工具：ssh_open/ssh_send/ssh_close 三步式连设备执行命令，交互式设备（要分步输命令的）也能用 AI 排查⑥远程控制：App 重启自动续连服务器（放客户现场不用管，开机就挂上）⑦设置页可调：单次回复最大长度（默认 256000，官方上限 384K）、思考强度四档（关/低/高/最高）⑧读超时 300 秒 + 失败自动重试，慢任务不再半路报错"),
    VersionLog("1.6.9", "2026-08-16", "远程控制体验优化：①取消设备所有者授权勾选（三端全部移除，AI 不再向你申请授权，直接干活）②控制端新增 root 命令审批三档：每步审批（默认）/会话允许（本会话自动放行）/关闭（自动拒绝），点对话区顶部按钮切换；ARP 类命令（arpspoof/arping）免审批直接执行 ③锁屏不再卡任务：AI 执行期间保持全速运行（防休眠锁），平时正常休眠省电 ④网络差不怕断：断线自动重连（5-60 秒退避）、断网瞬间的 AI 过程与结果不丢（断线帧缓存补发）、长时间无响应自动判断——AI 长任务静默执行不误报，只有设备真离线才提示 ⑤三端聊天实时同步：PDA 屏上聊，网页/App 控制台实时看到全过程（思考/工具/结果）；控制台聊，PDA 屏上也实时显示，任意一端可停止 ⑥AI 提示词全面精简：删掉申请授权、界面操作等啰嗦话，root 命令审批一句话带过"),
    VersionLog("1.6.8", "2026-08-16", "远程 AI 控制（大功能）：①PDA 放客户家，你在家用电脑/手机浏览器远程操控 PDA 上的 AI 排障——控制台注册账号+绑定设备，远程期间本机弹窗静默，审批与授权都在控制端确认 ②两端会话同步：控制页与 PDA 屏上 AI 页共用同一份会话（切换/新建/清空/删除/重命名），历史 5 条分页（网页点按钮、App 内上滑瀑布流）③记忆与单轮删除三端同步：🧠 记忆弹窗可查看/单删/清空本会话；气泡旁垃圾桶可删掉某一轮对话（不再进 AI 上下文）④安全：账号单点登录（一处登录）、登录失败锁定、防暴力破解、登录令牌 30 天（刷新网页免重输密码）⑤App 内置远程控制台：首页网络工具新增『远程控制台』入口，手机也能控制别的 PDA ⑥体验：远程思考过程与工具卡完成后自动折叠、登录框自动英文/密码键盘"),
    VersionLog("1.6.7", "2026-08-15", "①流量审计大修（真机实测定位三层断网根因）：审计时目标不再断网——补转发路由与策略规则（Android 默认丢弃转发）、关闭反向路径过滤、放行系统转发防火墙（tether 链），双向欺骗同时骗目标与网关，上下行真实可抓 ②误判修复：刷抖音/看视频不再被标疑似——去掉连接数弱特征、UDP 阈值提高、IDC 判定收紧（云厂商/CDN 不再算机房）、疑似判定要求上行量达标 ③修复摄像头弱口令探测误报『密码为空』：空密码组合移到表尾，真查到空密码会明确提示『未设密码』④密码可见性优化：AI 探测出密码显示打码形式（前2位+***+后2位）方便确认；你说『密码给我』AI 展示完整值（界面上能看，翻记录文件还是打码的）⑤凭据保险库加眼睛按钮：🧠 弹窗点 👁 可临时查看已存密码（加密解密），再点隐藏、关闭弹窗自动隐藏 ⑥流量审计页新增『借道审计（二级路由）』模式：专查客户私接二级路由背后藏的设备 ⑦全 App 文案口语化大排查：tcpdump/SSID/特征端口等术语改大白话，各功能页补充『相关功能』互相指引 ⑧PCDN 检测结果卡片精简（审计详情折叠、特征依据一行），摄像头页直连区折叠、API Key 输入框不再回显、默认模型跟随接口返回的第一个"),
    VersionLog("1.6.6", "2026-08-15", "现场密码保险库：①AI 发现你发的密码（摄像头/路由器/WiFi/ONVIF 密码等）会自动存进本机加密保险库（AES 加密落盘，仅本设备可解密），并把对话记录里的密码明文自动打码成 ***——翻记录文件也看不到密码 ②保险库按客户隔离：清空会话后密码还在，重聊自动接上（不用再输一遍）；删除会话连带清除 ③AI 助手页 🧠 记忆弹窗新增凭据区：只看得到密码用途和设备，看不到密码值，可单条删除"),
    VersionLog("1.6.5", "2026-08-15", "①模型选择改下拉框：设置页点开直接选 AI 模型，选项由 DeepSeek 接口实时返回（如 deepseek-v4-flash 快又省 / deepseek-v4-pro 更强），不用再手打模型名；也可选『自定义模型…』手动填写 ②选中即生效：切换后 AI 助手马上用新模型，标题栏实时显示当前模型 ③免责声明与使用说明更新：主动操作（静默断网/流量审计/PCDN 检测/摄像头操作/root 命令等）需客户授权的提示更完整，并注明 AI 对话走付费接口、SOS 会真实发短信拨号"),
    VersionLog("1.6.4", "2026-08-15", "会话列表升级：①列表改两行布局——第一行标题完整显示（不再截断），第二行时间 + 清空/重命名/删除按钮，会话多了列表内滚动 ②会话标题可编辑：点 ✏️ 给会话起名字（最多 32 字，弹窗自动弹出键盘、直接打字整体替换），手动命名后新消息不再自动改标题；不命名的会话自动取第一句话前 24 字 ③代码质量：lint 再次清零（蓝牙/定位/存储等 5 处权限与兼容性标注）"),
    VersionLog("1.6.3", "2026-08-14", "AI 资料自动沉淀：①AI 自动把完整排查资料/报告写成 .md 档案（像开发文档一样沉淀知识，无需你吩咐）——短结论用记忆、长资料写 md 自动区分使用；清空会话后重新聊天时 AI 自动列出档案清单读回上下文（软件还会把档案清单直接附上提示词兜底）②清空会话加确认弹窗：清空后上下文重新开始、记忆与 md 档案保留；删除会话连带删除其记忆与档案（会提示数量）③代码质量：全量 lint 检查清零（定位权限显式检查/相机实验 API 标注/无摄像头无电话硬件兼容声明等 7 项）"),
    VersionLog("1.6.2", "2026-08-14", "AI 记忆与压缩：①重要信息先行记忆——AI 对话中发现网络拓扑/排查结论/用户偏好等会主动记住（也可说『记住…』），换新会话自动记得；不记密码密钥；删除会话时该会话的记忆一并清除（删除前会提示条数），AI 页新增 🧠 记忆管理按钮 ②智能压缩上下文——长对话更早历史自动压缩成要点摘要继续携带（不再粗暴丢弃），节省费用 ③上下文缓存命中统计：AI 页显示缓存命中率（DeepSeek 自动缓存，前缀稳定即命中，输入费用约 1/10）"),
    VersionLog("1.6.1", "2026-08-14", "AI 助手新增 root shell 能力（需 root）：AI 可执行任意 root 命令排查疑难问题；审批三档——关闭 / 每步审批（默认，每条命令弹窗确认）/ 本会话允许（危险）；档位按钮在『AI 诊断助手』标题后面，点击切换；危险命令执行前必须经你确认，重启 App 自动回到每步审批"),
    VersionLog("1.6.0", "2026-08-14", "①SOS 紧急求救（首页第一位）：🆘 一键求救（声光摩斯+短信群发一步齐发）、🔦 声光摩斯（手电闪烁+蜂鸣循环 SOS 码，可随时停）、📩 短信求救（自动附 GPS 定位+地图链接，短信模板可自己改）、📞 一键拨打紧急联系人；紧急联系人可预填 1-3 个，按钮全部大号醒目，紧急情况好点 ②首次安装一次性授权：定位/短信/电话/相机/蓝牙/通知所有权限一条说明+一键全部授权，不用每个功能页零散弹窗 ③AI 助手新增反馈与诊断工具（口述即可帮提交反馈/查诊断信息），连接状态随网络切换自动刷新"),
    VersionLog("1.5.1", "2026-08-14", "PCDN 检测重构（检出从严，宁可多标不漏检）：①特征层新增 BT DHT 协议探测（发协议包确认 P2P 节点）、主路由 UPnP 端口映射表核对（映射≥5 条或描述含 PCDN 关键词）、多 IP 设备网页身份识别（多镜头摄像头自动排除、路由器管理页增强嫌疑）②点击设备卡片可直接单独流量审计确认，无需整网重扫 ③流量审计新增 DNS 域名自报维度与借道还原（二级路由 NAT 后设备按源端口还原），判定门槛放宽为总分≥4 且至少 1 个特征命中即疑似（需人工复核）④反馈渠道：设置页新增『反馈与建议』一键提交服务器 + 『诊断信息』（设备/使用统计/崩溃日志可导出）；App 启动自动上报运行信息供优化分析 ⑤更新源迁至服务器（your-server.example:8090），反馈数据入库并提供网页管理后台"),
    VersionLog("1.5.0", "2026-08-14", "①PCDN 检测增强：新增『一台设备占用多个 IP（二级路由器特征）』识别——租户私接的路由器也会被标出并自动进入流量审计；流量审计叠加 IPv6 抓包（能看到藏在路由器后面的纯 IPv6 PCDN 设备）；判定改为综合评分（多个特征同时命中才判，刷抖音/看视频等正常高流量不再误报）②多网络适配：支持 PDA 插网线（有线网络）；局域网没外网时诊断自动绑定局域网接口——AI 对话走移动数据、局域网检测走 Wi-Fi/网线互不干扰；AI 助手新增网络上下文报告（局域网/移动数据/默认路由一目了然）与路由控制工具 ③设备 MAC 识别更全：部分特殊状态的设备不再漏识别"),
    VersionLog("1.4.6", "2026-08-14", "①修复部分机型（PDA 等）静默断不了 IPv6：设备未与目标通信时 IPv6 邻居表里没有目标记录，导致 IPv6 欺骗被跳过——现改用目标 MAC 直接计算稳定地址，任何机型都能断 IPv6 ②远程终端界面优化：命令输入行固定在底部（全屏终端、隐藏底部导航），输出再长也不会把输入框挤出屏幕，键盘弹出输入框不被遮挡 ③AI 助手新增路由追踪工具：可帮你追踪网络断在哪一跳"),
    VersionLog("1.4.5", "2026-08-14", "静默模式大修复与多项优化：①修复 IPv6 欺骗包校验和字节序错误——之前所有 IPv6 欺骗包都因校验和无效被目标设备直接丢弃，是断不掉 IPv6（抖音/微信等）的根本原因 ②修复欺骗包发到无效组播地址的问题 ③IPv6 断网升级为双向欺骗（同时骗目标与网关，覆盖路由器的多个 IPv6 地址与目标全部地址）④新增重连自动续静默：目标重连 Wi-Fi（隐私 MAC 轮换/换 IP）自动识别新身份重新静默 ⑤取消静默提速：恢复帧改后台并行发送，按钮不再卡顿几十秒 ⑥局域网唤醒优化：可从扫描的设备列表直接点选 MAC，不用手动输入"),
    VersionLog("1.4.4", "2026-08-13", "①局域网唤醒：输入电脑 MAC 一键远程开机（需电脑开启网络唤醒）②静默模式升级：标准/洪泛/信道阻塞 三档强度，对顽固设备可逐级加强；新增 IPv6 双重断网（NDP 欺骗），对走 IPv6 的 App 同样生效；静默/恢复过程有进度提示 ③界面修复：远程终端输入框不再被键盘挡住、功能页底部内容不被底部导航遮挡"),
    VersionLog("1.4.3", "2026-08-13", "更新记录优化：每个版本显示发布日期（如 1.4.2（2026-08-13）），历史版本发布时间一目了然"),
    VersionLog("1.4.2", "2026-08-13", "弱网检测升级：①新增 10 个主流平台（抖音/微信/B站/淘宝/京东/王者荣耀/腾讯视频/爱奇艺/快手/微博）快测——点平台立即测，或一键测全部对比各平台网络质量（丢包/时延/抖动）②批量测试边测边出，不用等全部测完 ③修复抖音/淘宝等平台禁 ping 导致的误报（自动改用端口探测）④AI 助手可指定平台测网络"),
    VersionLog("1.4.1", "2026-08-13", "修复兼容性：Android 13 及以下系统上打开设备发现/摄像头扫描会闪退的问题（mDNS 服务解析 API 兼容），老系统 PDA/设备可正常使用"),
    VersionLog("1.4.0", "2026-08-13", "①报告导出：设备发现/诊断/PCDN 检测/流量审计/环路检测/ARP 检测的结果可点『导出』存成 txt 报告到手机下载目录，AI 助手也能直接导出 ②弱网检测：持续测网络稳不稳，看丢包/卡顿/抖动，定位断流卡顿根因（时长可调，无需 root）③SNMP 面板：查路由器/交换机等支持 SNMP 设备的系统信息（名称/描述/运行时长/位置），无需 root"),
    VersionLog("1.3.1", "2026-08-12", "按钮统一：分享/直连/AI 发送按钮改为带图标的蓝色大按钮（更大更好点，与全 App 主按钮风格一致）"),
    VersionLog("1.3.0", "2026-08-12", "①开屏 Logo：启动时显示品牌图标与名称，更专业 ②结果可分享：设备发现/PCDN 检测/流量审计/AI 对话/诊断 Ping/抓包统计 都能一键分享报告给别人 ③结果缓存：设备/PCDN 扫描结果自动保存，退出页面再进来还在 ④IP 地址校验：流量审计/远程终端等输入框格式校验更严格，防填错 ⑤摄像头跨网段直连：不同网段的摄像头可手动输入 IP 添加管理 ⑥BLE 扫描优化：厂商识别更全（支持中文设备名），扫描不易中断漏设备 ⑦界面细节统一：圆角与链接颜色全局一致"),
    VersionLog("1.2.8", "2026-08-12", "BLE 扫描厂商识别优化：设备名关键词（小米/华为/苹果/三星等 40+）+ 广播数据厂商 ID（Bluetooth SIG）双重识别，扫描结果直接显示厂商；AI 助手 scan_ble 同步返回厂商"),
    VersionLog("1.2.7", "2026-08-12", "亮暗模式彻底适配：首页顶部标题栏浅色模式改浅蓝渐变（深色模式保持深蓝），底部导航浅色模式改浅蓝白底+品牌蓝图标；Wi-Fi 雷达页改整页滚动（雷达图不再挤占 AP 列表）"),
    VersionLog("1.2.6", "2026-08-12", "UI 反馈优化：修复 AI 助手键盘弹出把顶部顶出屏幕；AI 回答时显示跳动省略号、调用工具时显示已用时长，审计/抓包按钮实时倒计时；Wi-Fi 分析页改整页滚动（AP 列表不再被挤成小区域）；底部导航改深蓝科技底；全面屏（挖孔/手势条）适配确认"),
    VersionLog("1.2.5", "2026-08-12", "新增：①Wi-Fi 分析给出已连接 Wi-Fi 的优化建议（信号/频段/信道优选——2.4G 推荐 1/6/11、5G 推荐具体信道）②Wi-Fi 雷达：雷达图实时找 AP（走动看信号强弱判断方位，支持 RTT 测距显示真实米数）③航空信号检测：查间歇性断网是不是 5G 用了与航空雷达共用的 DFS 信道④静默模式：双向 ARP 欺骗（同时骗目标与网关）让偷跑流量的设备静默断外网，测速更准；目标启用 ARP 防火墙也断网；仅限授权设备，进页面需确认免责声明，责任自负 ⑤AI 对话微信化：消息气泡加时间戳，会话列表显示最后消息预览与相对时间 ⑥更新记录只显示最近 5 条，历史版本折叠"),
    VersionLog("1.2.4", "2026-08-11", "修复 OTA 更新安装时提示『没有权限安装』：补齐安装权限声明，未开启时会自动跳到系统设置引导开启『安装未知应用』"),
    VersionLog("1.2.3", "2026-08-11", "修复 PCDN 全网检测在部分网络（设备响应慢）扫不到设备的问题——扫描超时预算太短被中断，现已延长，实测同网络下从 0 台恢复到 19 台"),
    VersionLog("1.2.2", "2026-08-11", "AI 助手工具优化：局域网扫描/PCDN 检测等耗时工具设置超时上限（如全网检测最长 120 秒），超时自动中断并提示，不再长时间卡住；修复 OTA 安装失败（调不起系统安装器）"),
    VersionLog("1.2.1", "2026-08-11", "补 root 状态提示：ARP 欺骗检测、网关/IP 冲突检测、PCDN 全网检测页面新增红色『未检测到 root 权限』提示——无 root 时明确告知哪些功能不能用、可换用什么替代功能"),
    VersionLog("1.2.0", "2026-08-11", "API Key 本机加密存储（Android Keystore AES-GCM，明文自动迁移，更安全）；关于页加系统兼容性说明（支持 Android 8.0+）"),
    VersionLog("1.1.0", "2026-08-11", "环路检测（安全审计）：tcpdump 抓包查广播风暴/环路，抓包时长可自己调；ARP 欺骗检测、PCDN 全网检测、AI 工具（detect_loop/detect_arp_spoof/detect_pcdn_network）同样支持时长自定义；修复环路检测抓包解析（此前统计不到广播帧）"),
    VersionLog("1.0.0", "2026-08-11", "正式签名版：新增远程终端（SSH/Telnet 交互式、ANSI 彩色）、二维码/条码（扫码+生成）、摄像头弱口令探测、AI 主动 ARP 扫描、AI 连接状态检测；修复扫码相机遮挡、设备发现实时显示、mDNS 阻塞；厂商识别完整化（4.6 万 OUI）"),
    VersionLog("0.1.1", "2026-08-11", "OTA 更新上线；PCDN 检测增强（9 特征 + IDC 目标）；nmap/tcpdump/arpspoof 内置免 Termux"),
    VersionLog("0.1.0", "2026-08-11", "首个版本：设备发现 / 网络诊断 / 抓包 / 摄像头 / AI 助手")
)

/** 字节数格式化为可读文本（B / KB / MB） */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
