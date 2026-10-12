package com.installer.toolbox.ui.screens.sos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.installer.toolbox.util.SosEngine
import kotlinx.coroutines.launch

/**
 * SOS 大按钮：渐变背景 + 圆角 + 阴影 + 主文字/副文字结构，紧急情况醒目好点。
 */
@Composable
private fun SosBigButton(
    text: String,
    sub: String,
    colors: List<Color>,
    height: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .shadow(6.dp, RoundedCornerShape(22.dp))
            .background(
                brush = if (enabled) {
                    Brush.verticalGradient(colors)
                } else {
                    Brush.verticalGradient(colors.map { it.copy(alpha = 0.45f) })
                },
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.92f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * SOS 紧急求救页（首页功能区第一）：
 * - 🆘 一键求救：声光摩斯 + 全部紧急联系人短信（含位置）一步齐发；
 * - 🔦 声光摩斯：手电闪烁 + 蜂鸣循环 SOS 码，可随时停止；
 * - 📩 短信求救：预填紧急联系人，短信含 GPS 位置 + 地图链接 + 自定义信息；
 * - 📞 拨打联系人：紧急情况电话最快。
 * 按钮全部大号醒目，紧急情况好点选；权限不足时顶部红卡提示并一键授权。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var morseOn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var showContactDialog by remember { mutableStateOf(false) }
    var contactText by remember { mutableStateOf(SosEngine.contacts(context).joinToString("、")) }
    var extraText by remember { mutableStateOf(SosEngine.extraInfo(context)) }
    var templateText by remember { mutableStateOf(SosEngine.smsTemplate(context)) }
    var busy by remember { mutableStateOf(false) }

    // 权限检查（短信/定位/电话/相机手电）
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val needSms = !has(Manifest.permission.SEND_SMS)
    val needLoc = !has(Manifest.permission.ACCESS_FINE_LOCATION)
    val needCall = !has(Manifest.permission.CALL_PHONE)
    val needCamera = !has(Manifest.permission.CAMERA)
    val missing = listOf(
        if (needSms) Manifest.permission.SEND_SMS else null,
        if (needLoc) Manifest.permission.ACCESS_FINE_LOCATION else null,
        if (needCall) Manifest.permission.CALL_PHONE else null,
        if (needCamera) Manifest.permission.CAMERA else null
    ).filterNotNull()

    // 一键授权（SOS 所需权限）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        status = "权限状态已更新：${if (it.values.all { v -> v }) "已全部授权" else "仍有权限未授权（见顶部提示）"}"
        statusOk = it.values.all { v -> v }
    }

    // 页面退出兜底停止摩斯（防手电常亮）
    DisposableEffect(Unit) {
        onDispose { scope.launch { SosEngine.stopMorse(context) } }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SOS 紧急求救") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 顶部警示横幅（红色渐变 + 大字）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF8E0000), Color(0xFFC62828), Color(0xFFE53935))),
                        RoundedCornerShape(18.dp)
                    )
                    .padding(vertical = 16.dp, horizontal = 14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "SOS 紧急求救",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "遇到紧急情况：直接按下方 🆘 一键求救",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // 权限提示卡（缺权限时红色）
            if (missing.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "⚠ 缺少权限：短信/定位/电话/手电，求救功能受限",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { permLauncher.launch(missing.toTypedArray()) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("一键授权全部权限")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 🆘 一键求救（最大按钮，红色渐变）
            SosBigButton(
                text = "🆘 一键求救",
                sub = "声光摩斯 + 短信群发，一步齐发",
                colors = listOf(Color(0xFFB71C1C), Color(0xFFD32F2F), Color(0xFFF44336)),
                height = 150,
                enabled = !busy,
                onClick = {
                    if (busy) return@SosBigButton
                    busy = true
                    status = null
                    scope.launch {
                        // 1) 声光摩斯启动
                        if (!SosEngine.morseRunning) {
                            morseOn = SosEngine.toggleMorse(context)
                        }
                        // 2) 群发短信（含位置）
                        val results = SosEngine.sendSosSms(context, extra = extraText)
                        val ok = results.values.count { it == "已发送" }
                        val fail = results.size - ok
                        status = "已触发声光摩斯 + 短信求救：成功 $ok 条" +
                            (if (fail > 0) "、失败 $fail 条（${results.filterValues { it != "已发送" }.values.joinToString("；")}）" else "")
                        statusOk = ok > 0
                        busy = false
                    }
                }
            )
            Spacer(Modifier.height(14.dp))

            // 🔦 声光摩斯（大黄按钮）
            SosBigButton(
                text = if (morseOn) "⏹ 停止摩斯信号" else "🔦 声光摩斯 SOS",
                sub = if (morseOn) "正在闪烁+蜂鸣（···---···），持续循环" else "手电闪烁 + 高频蜂鸣，持续循环直到停止",
                colors = if (morseOn) listOf(Color(0xFF5D4037), Color(0xFF7A5C00), Color(0xFF9E7A00))
                else listOf(Color(0xFFF57F17), Color(0xFFF9A825), Color(0xFFFFB300)),
                height = 96,
                enabled = true,
                onClick = {
                    morseOn = SosEngine.toggleMorse(context)
                    status = if (morseOn) "声光摩斯已启动（手电闪烁+高穿透蜂鸣，持续循环，点击停止）" else "声光摩斯已停止"
                    statusOk = true
                }
            )
            Spacer(Modifier.height(12.dp))

            // 📩 短信求救 + 📞 拨打联系人（并排大按钮）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SosBigButton(
                    text = "📩 短信求救",
                    sub = "含位置与地图链接",
                    colors = listOf(Color(0xFFE65100), Color(0xFFEF6C00), Color(0xFFFB8C00)),
                    height = 96,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (busy) return@SosBigButton
                        busy = true
                        status = null
                        scope.launch {
                            val results = SosEngine.sendSosSms(context, extra = extraText)
                            val ok = results.values.count { it == "已发送" }
                            val fail = results.size - ok
                            status = "短信求救：成功 $ok 条" +
                                (if (fail > 0) "、失败 $fail 条（${results.filterValues { it != "已发送" }.values.joinToString("；")}）" else "")
                            statusOk = ok > 0
                            busy = false
                        }
                    }
                )
                SosBigButton(
                    text = "📞 拨打联系人",
                    sub = "紧急情况电话最快",
                    colors = listOf(Color(0xFF0D47A1), Color(0xFF1565C0), Color(0xFF1E88E5)),
                    height = 96,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val first = SosEngine.contacts(context).firstOrNull()
                        if (first == null) {
                            status = "未设置紧急联系人，请先点下方『设置紧急联系人』"
                            statusOk = false
                        } else {
                            try {
                                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$first")))
                            } catch (e: Exception) {
                                status = "拨号失败：${e.message ?: "未授权电话权限"}"
                                statusOk = false
                            }
                        }
                    }
                )
            }
            Spacer(Modifier.height(12.dp))

            // 状态
            status?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (statusOk) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // 紧急联系人
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = "紧急联系人（预填，最多 3 个）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (SosEngine.contacts(context).isEmpty()) "未设置——求救前务必先设置！"
                        else SosEngine.contacts(context).joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (SosEngine.contacts(context).isEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            contactText = SosEngine.contacts(context).joinToString("、")
                            showContactDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("设置紧急联系人")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // 附加信息
            OutlinedTextField(
                value = extraText,
                onValueChange = { extraText = it.take(100) },
                label = { Text("附加信息（可选，如血型/病史/所在楼层）") },
                placeholder = { Text("随求救短信一起发出，最多 100 字") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { SosEngine.saveExtraInfo(context, extraText) }) {
                Text("保存附加信息")
            }
            Spacer(Modifier.height(10.dp))

            // 短信模板（默认示例，可编辑）
            OutlinedTextField(
                value = templateText,
                onValueChange = { templateText = it.take(300) },
                label = { Text("短信模板（可编辑）") },
                supportingText = { Text("{位置} 自动替换为 GPS 定位与地图链接；{附加} 替换为上方附加信息（为空时自动省略该句）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = {
                    SosEngine.saveSmsTemplate(context, templateText)
                    status = "短信模板已保存"
                    statusOk = true
                }) { Text("保存模板") }
                TextButton(onClick = {
                    templateText = SosEngine.DEFAULT_SMS_TEMPLATE
                    SosEngine.saveSmsTemplate(context, templateText)
                    status = "已恢复默认短信模板"
                    statusOk = true
                }) { Text("恢复默认") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "说明：求救短信与拨号为真实动作，仅在你点击大按钮时触发；短信内容自动附带 GPS 位置与地图链接（定位失败时注明）。声光摩斯持续循环直到点击停止，关闭本页也会自动停。建议先设置好紧急联系人并授权权限，紧急时直接按 🆘。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    // 联系人设置对话框
    if (showContactDialog) {
        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            title = { Text("设置紧急联系人") },
            text = {
                Column {
                    Text(
                        text = "输入 1-3 个手机号码，用顿号/逗号/换行分隔：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = contactText,
                        onValueChange = { contactText = it },
                        placeholder = { Text("如：13812345678、13987654321") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = contactText
                        .split(Regex("""[、，,\s]+"""))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .take(3)
                    SosEngine.saveContacts(context, list)
                    showContactDialog = false
                    status = if (list.isEmpty()) "未保存：请输入有效号码" else "已保存 ${list.size} 个紧急联系人：${list.joinToString("、")}"
                    statusOk = list.isNotEmpty()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showContactDialog = false }) { Text("取消") }
            }
        )
    }
}
