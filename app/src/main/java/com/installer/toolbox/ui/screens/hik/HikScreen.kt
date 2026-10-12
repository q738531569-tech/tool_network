package com.installer.toolbox.ui.screens.hik

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.installer.toolbox.core.isapi.HikDevice
import com.installer.toolbox.core.isapi.OnvifDevice

/** 操作成功提示色 */
private val SuccessColor = Color(0xFF2E7D32)

/** 未激活徽章底色（橙） */
private val UnactivatedBg = Color(0xFFFFF3E0)

/** 未激活徽章字色（橙） */
private val UnactivatedFg = Color(0xFFEF6C00)

/** 已激活徽章底色（绿） */
private val ActivatedBg = Color(0xFFE8F5E9)

/**
 * 摄像头页：ONVIF 自动发现 + 海康 SADP 双模式。
 * 设备卡片列表 + 展开式详情操作区。
 */
@Composable
fun HikScreen(viewModel: HikViewModel = viewModel()) {
    val mode by viewModel.mode.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // —— 标题区 ——
        Spacer(Modifier.height(12.dp))
        Text("摄像头", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "ONVIF 自动发现（兼容海康/大华/seetong/宇视等）+ 海康专属发现",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "相关功能：找不到摄像头？先用『设备发现』扫一遍同网段设备。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))

        // —— 模式切换 ——
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == CamMode.ONVIF,
                onClick = { viewModel.switchMode(CamMode.ONVIF) },
                label = { Text("ONVIF 自动发现") }
            )
            FilterChip(
                selected = mode == CamMode.SADP,
                onClick = { viewModel.switchMode(CamMode.SADP) },
                label = { Text("海康专属发现") }
            )
        }
        Spacer(Modifier.height(12.dp))

        when (mode) {
            CamMode.ONVIF -> OnvifModeContent(viewModel)
            CamMode.SADP -> SadpModeContent(viewModel)
        }
    }
}

/** 海康 SADP 模式内容（原有海康发现/操作逻辑，保持不变） */
@Composable
private fun SadpModeContent(viewModel: HikViewModel) {
    val devices by viewModel.devices.collectAsState()
    val discovering by viewModel.discovering.collectAsState()
    val discoverError by viewModel.discoverError.collectAsState()
    val login by viewModel.login.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val busyDevice by viewModel.busyDevice.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = viewModel::discover, enabled = !discovering) {
            Text(if (discovering) "发现中…" else "开始发现")
        }
        discoverError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // —— 设备列表（空状态或 LazyColumn）——
        if (devices.isEmpty() && !discovering) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "未发现海康设备，请确认手机与摄像头在同一网段",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(devices, key = { it.serial.ifBlank { it.ip } }) { device ->
                    HikDeviceCard(
                        device = device,
                        savedLogin = login[device.ip],
                        // detail 只对当前操作设备生效，避免多张卡片串状态
                        detail = detail?.takeIf { it.device.ip == device.ip },
                        busy = busyDevice != null,
                        onLogin = { user, pass -> viewModel.login(device.ip, user, pass) },
                        onLoadInfo = { viewModel.loadInfo(device) },
                        onChangeIp = { ip, mask, gw, dhcp -> viewModel.changeIp(device, ip, mask, gw, dhcp) },
                        onChangePassword = { viewModel.changePassword(device, it) },
                        onReboot = { viewModel.reboot(device) },
                        onFactoryReset = { viewModel.factoryReset(device) },
                        onResetCode = { viewModel.resetCode(device, it) },
                        onActivate = { viewModel.sendSADPActivate(device, it) },
                        onResetPassword = { c, p -> viewModel.sadpResetPassword(device, c, p) }
                    )
                }
            }
        }
    }
}

/** ONVIF 模式内容：发现按钮 + 自动识别设备卡片列表 */
@Composable
private fun OnvifModeContent(viewModel: HikViewModel) {
    val devices by viewModel.onvifDevices.collectAsState()
    val discovering by viewModel.onvifDiscovering.collectAsState()
    val onvifError by viewModel.onvifError.collectAsState()
    val login by viewModel.onvifLogin.collectAsState()
    val detail by viewModel.onvifDetail.collectAsState()
    val busyDevice by viewModel.onvifBusy.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 直连状态提升到本层：收起再展开保留输入值（AnimatedVisibility 内的 remember 会随收起丢失）
        var showManual by remember { mutableStateOf(false) }
        var manualIp by remember { mutableStateOf("") }
        var manualPort by remember { mutableStateOf("") }
        var manualMsg by remember { mutableStateOf<String?>(null) }
        // 操作行：开始发现 + 直连跨网段入口（折叠展开，省屏幕空间给设备列表）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = viewModel::discoverOnvif, enabled = !discovering) {
                Text(if (discovering) "发现中…" else "开始发现")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showManual = !showManual }) {
                Icon(
                    imageVector = if (showManual) Icons.Filled.ExpandLess else Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (showManual) "收起直连" else "直连跨网段")
            }
        }
        // 直连展开区（默认收起：跨网段摄像头才需要，不给常用流程添堵）
        AnimatedVisibility(visible = showManual) {
            Column {
                Text(
                    text = "同局域网另一网段（经路由器互连）的摄像头广播发现扫不到，可手动输入 IP 直连管理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("摄像头 IP") },
                        placeholder = { Text("如 192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("端口") },
                        placeholder = { Text("80") },
                        singleLine = true,
                        modifier = Modifier.width(100.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val ip = manualIp.trim()
                            if (ip.isEmpty()) {
                                manualMsg = "请填写摄像头 IP"
                                return@Button
                            }
                            manualMsg = viewModel.addManualOnvif(ip, manualPort.toIntOrNull()?.coerceIn(1, 65535) ?: 80)
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("直连")
                    }
                }
                manualMsg?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
        onvifError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // —— 设备列表（空状态或 LazyColumn）——
        if (devices.isEmpty() && !discovering) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "未发现 ONVIF 设备（摄像头需支持 ONVIF（通用摄像头协议）且与手机同网段）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(devices, key = { it.device.xaddr }) { item ->
                    OnvifDeviceCard(
                        item = item,
                        savedLogin = login[item.device.ip],
                        // detail 只对当前操作设备生效，避免多张卡片串状态
                        detail = detail?.takeIf { it.device.ip == item.device.ip },
                        busy = busyDevice != null,
                        onLogin = { user, pass -> viewModel.onvifLogin(item.device.ip, user, pass) },
                        onLoadInfo = { viewModel.onvifLoadInfo(item.device) },
                        onProbePassword = { viewModel.onvifProbePassword(item.device) },
                        onChangeIp = { ip, mask, gw, dhcp -> viewModel.onvifChangeIp(item.device, ip, mask, gw, dhcp) },
                        onChangePassword = { viewModel.onvifChangePassword(item.device, it) },
                        onReboot = { viewModel.onvifReboot(item.device) },
                        onFactoryReset = { viewModel.onvifFactoryReset(item.device) }
                    )
                }
            }
        }
    }
}

/** ONVIF 设备卡片：IP + 自动识别型号/厂商/固件/序列号，可展开详情操作区 */
@Composable
private fun OnvifDeviceCard(
    item: OnvifDeviceItem,
    savedLogin: String?,
    detail: OnvifDetail?,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    onLoadInfo: () -> Unit,
    onProbePassword: () -> Unit,
    onChangeIp: (String, String, String, Boolean) -> Unit,
    onChangePassword: (String) -> Unit,
    onReboot: () -> Unit,
    onFactoryReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val info = item.info

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 信息区：点击切换展开状态（clickable 不覆盖操作区，避免输入框点击误触发收起）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.device.ip,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                InfoLine("型号", item.model)
                InfoLine("厂商", info?.manufacturer ?: "未知")
                InfoLine("固件", info?.firmware ?: "未知")
                InfoLine("序列号", info?.serial?.takeLast(8)?.let { "…$it" } ?: "未知")
            }

            // 详情操作区
            AnimatedVisibility(visible = expanded) {
                OnvifDetailPanel(
                    device = item.device,
                    savedLogin = savedLogin,
                    detail = detail,
                    busy = busy,
                    expanded = expanded,
                    onLogin = onLogin,
                    onLoadInfo = onLoadInfo,
                    onProbePassword = onProbePassword,
                    onChangeIp = onChangeIp,
                    onChangePassword = onChangePassword,
                    onReboot = onReboot,
                    onFactoryReset = onFactoryReset
                )
            }
        }
    }
}

/** 单行设备信息（MAC/型号/序列号/固件） */
@Composable
private fun InfoLine(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** ONVIF 设备详情操作区：账密 / 获取信息 / 改 IP / 改密码 / 重启 / 恢复出厂 / 结果 */
@Composable
private fun OnvifDetailPanel(
    device: OnvifDevice,
    savedLogin: String?,
    detail: OnvifDetail?,
    busy: Boolean,
    expanded: Boolean,
    onLogin: (String, String) -> Unit,
    onLoadInfo: () -> Unit,
    onProbePassword: () -> Unit,
    onChangeIp: (String, String, String, Boolean) -> Unit,
    onChangePassword: (String) -> Unit,
    onReboot: () -> Unit,
    onFactoryReset: () -> Unit
) {
    // 二次确认弹窗状态：放函数顶层（Column 外），供操作区与弹窗共用
    var showFactoryConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        // —— 账密区：预填已保存账密，展开时重新初始化 ——
        var user by remember(device.ip, expanded) { mutableStateOf(savedLogin?.substringBefore('|') ?: "") }
        var pass by remember(device.ip, expanded) { mutableStateOf(savedLogin?.substringAfter('|', "") ?: "") }
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { onLogin(user, pass) }, enabled = !busy) { Text("保存账密") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onLoadInfo, enabled = !busy) { Text("获取信息") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onProbePassword, enabled = !busy) { Text("弱口令探测") }
        }
        // 设备信息文本
        detail?.infoText?.takeIf { it.isNotBlank() }?.let { info ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // —— 改 IP 区 ——
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("改 IP", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        var newIp by remember(device.ip, expanded) { mutableStateOf("") }
        var mask by remember(device.ip, expanded) { mutableStateOf("") }
        var gateway by remember(device.ip, expanded) { mutableStateOf("") }
        var dhcp by remember(device.ip, expanded) { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newIp,
                onValueChange = { newIp = it },
                label = { Text("新 IP") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = mask,
                onValueChange = { mask = it },
                label = { Text("掩码") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = gateway,
            onValueChange = { gateway = it },
            label = { Text("网关") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DHCP", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = dhcp, onCheckedChange = { dhcp = it }, enabled = !busy)
            Spacer(Modifier.weight(1f))
            Button(onClick = { onChangeIp(newIp, mask, gateway, dhcp) }, enabled = !busy) {
                Text("应用")
            }
        }

        // —— 改密码区 ——
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("改密码", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        var newPwd by remember(device.ip, expanded) { mutableStateOf("") }
        OutlinedTextField(
            value = newPwd,
            onValueChange = { newPwd = it },
            label = { Text("新密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onChangePassword(newPwd) }, enabled = !busy) { Text("修改") }

        // —— 操作按钮行：重启 / 恢复出厂（二次确认）——
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReboot, enabled = !busy) { Text("重启") }
            OutlinedButton(
                onClick = { showFactoryConfirm = true },
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("恢复出厂") }
        }

        // —— 操作结果：成功绿 / 失败红 ——
        detail?.lastResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            val isError = result.contains("失败") || result.contains("错误") || result.contains("请先")
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else SuccessColor
            )
        }
    }

    // —— 恢复出厂二次确认弹窗 ——
    if (showFactoryConfirm) {
        AlertDialog(
            onDismissRequest = { showFactoryConfirm = false },
            title = { Text("恢复出厂设置") },
            text = { Text("确定要将设备 ${device.ip} 恢复出厂设置吗？该操作会清除所有配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFactoryConfirm = false
                        onFactoryReset()
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 单台设备卡片：信息区可点击展开/收起详情操作区 */
@Composable
private fun HikDeviceCard(
    device: HikDevice,
    savedLogin: String?,
    detail: HikDetail?,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    onLoadInfo: () -> Unit,
    onChangeIp: (String, String, String, Boolean) -> Unit,
    onChangePassword: (String) -> Unit,
    onReboot: () -> Unit,
    onFactoryReset: () -> Unit,
    onResetCode: (String) -> Unit,
    onActivate: (String) -> Unit,
    onResetPassword: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activated = device.activated == "true"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 信息区：点击切换展开状态（clickable 不覆盖操作区，避免输入框点击误触发收起）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.ip,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    // 激活状态徽章
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (activated) "已激活" else "未激活",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = if (activated) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = ActivatedBg,
                                labelColor = SuccessColor
                            )
                        } else {
                            AssistChipDefaults.assistChipColors(
                                containerColor = UnactivatedBg,
                                labelColor = UnactivatedFg
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                InfoLine("MAC", device.mac)
                InfoLine("型号", device.deviceType)
                InfoLine("序列号", "…${device.serial.takeLast(8)}")
                InfoLine("固件", device.firmware)
            }

            // 详情操作区
            AnimatedVisibility(visible = expanded) {
                DeviceDetailPanel(
                    device = device,
                    savedLogin = savedLogin,
                    detail = detail,
                    busy = busy,
                    expanded = expanded,
                    onLogin = onLogin,
                    onLoadInfo = onLoadInfo,
                    onChangeIp = onChangeIp,
                    onChangePassword = onChangePassword,
                    onReboot = onReboot,
                    onFactoryReset = onFactoryReset,
                    onResetCode = onResetCode,
                    onActivate = onActivate,
                    onResetPassword = onResetPassword
                )
            }
        }
    }
}

/** 设备详情操作区：账密 / 改 IP / 改密码 / 操作按钮 / 激活 / 结果 */
@Composable
private fun DeviceDetailPanel(
    device: HikDevice,
    savedLogin: String?,
    detail: HikDetail?,
    busy: Boolean,
    expanded: Boolean,
    onLogin: (String, String) -> Unit,
    onLoadInfo: () -> Unit,
    onChangeIp: (String, String, String, Boolean) -> Unit,
    onChangePassword: (String) -> Unit,
    onReboot: () -> Unit,
    onFactoryReset: () -> Unit,
    onResetCode: (String) -> Unit,
    onActivate: (String) -> Unit,
    onResetPassword: (String, String) -> Unit
) {
    // 二次确认弹窗状态：放函数顶层（Column 外），供操作区与弹窗共用
    var showFactoryConfirm by remember { mutableStateOf(false) }
    var showResetCode by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        // —— 账密区：预填已保存账密，展开时重新初始化 ——
        var user by remember(device.ip, expanded) { mutableStateOf(savedLogin?.substringBefore('|') ?: "") }
        var pass by remember(device.ip, expanded) { mutableStateOf(savedLogin?.substringAfter('|', "") ?: "") }
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { onLogin(user, pass) }, enabled = !busy) { Text("保存账密") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onLoadInfo, enabled = !busy) { Text("获取设备信息") }
        }
        // 设备信息文本
        detail?.infoText?.takeIf { it.isNotBlank() }?.let { info ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // —— 改 IP 区 ——
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("改 IP", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        var newIp by remember(device.ip, expanded) { mutableStateOf("") }
        var mask by remember(device.ip, expanded) { mutableStateOf("") }
        var gateway by remember(device.ip, expanded) { mutableStateOf("") }
        var dhcp by remember(device.ip, expanded) { mutableStateOf(device.dhcp == "true") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newIp,
                onValueChange = { newIp = it },
                label = { Text("新 IP") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = mask,
                onValueChange = { mask = it },
                label = { Text("掩码") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = gateway,
            onValueChange = { gateway = it },
            label = { Text("网关") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DHCP", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = dhcp, onCheckedChange = { dhcp = it }, enabled = !busy)
            Spacer(Modifier.weight(1f))
            Button(onClick = { onChangeIp(newIp, mask, gateway, dhcp) }, enabled = !busy) {
                Text("应用")
            }
        }

        // —— 改密码区 ——
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("改密码", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        var newPwd by remember(device.ip, expanded) { mutableStateOf("") }
        OutlinedTextField(
            value = newPwd,
            onValueChange = { newPwd = it },
            label = { Text("新密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onChangePassword(newPwd) }, enabled = !busy) { Text("修改") }

        // —— 操作按钮行：重启 / 恢复出厂（二次确认）/ 生成重置码 ——
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReboot, enabled = !busy) { Text("重启") }
            OutlinedButton(
                onClick = { showFactoryConfirm = true },
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("恢复出厂") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showResetCode = !showResetCode }, enabled = !busy) {
            Text(if (showResetCode) "收起重置码" else "生成重置码")
        }
        // 重置码：日期输入 + 生成
        AnimatedVisibility(visible = showResetCode) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                var date by remember(device.ip, expanded) { mutableStateOf("") }
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日期（YYYYMMDD）") },
                    placeholder = { Text("如 20240101") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onResetCode(date) }, enabled = !busy) { Text("生成") }
            }
        }

        // —— 免旧密码重置密码（安全码 + 新密码） ——
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            text = "免旧密码重置密码：输入安全码 + 新密码（新固件安全码看设备/NVR 屏幕验证码；旧固件用上方\"生成重置码\"）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        var secCode by remember(device.ip, expanded) { mutableStateOf("") }
        var resetPwd by remember(device.ip, expanded) { mutableStateOf("") }
        OutlinedTextField(
            value = secCode,
            onValueChange = { secCode = it },
            label = { Text("安全码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = resetPwd,
            onValueChange = { resetPwd = it },
            label = { Text("新密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onResetPassword(secCode, resetPwd) }, enabled = !busy) { Text("重置密码") }

        // —— 未激活设备：SADP 激活区 ——
        if (device.activated != "true") {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = "设备未激活，可设置密码后通过 SADP 激活",
                style = MaterialTheme.typography.bodySmall,
                color = UnactivatedFg
            )
            Spacer(Modifier.height(8.dp))
            var actPwd by remember(device.ip, expanded) { mutableStateOf("") }
            OutlinedTextField(
                value = actPwd,
                onValueChange = { actPwd = it },
                label = { Text("激活密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onActivate(actPwd) }, enabled = !busy) { Text("激活") }
        }

        // —— 操作结果：成功绿 / 失败红 ——
        detail?.lastResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            val isError = result.contains("失败") || result.contains("错误") || result.contains("请先")
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else SuccessColor
            )
        }
    }

    // —— 恢复出厂二次确认弹窗 ——
    if (showFactoryConfirm) {
        AlertDialog(
            onDismissRequest = { showFactoryConfirm = false },
            title = { Text("恢复出厂设置") },
            text = { Text("确定要将设备 ${device.ip} 恢复出厂设置吗？该操作会清除所有配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFactoryConfirm = false
                        onFactoryReset()
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryConfirm = false }) { Text("取消") }
            }
        )
    }
}
