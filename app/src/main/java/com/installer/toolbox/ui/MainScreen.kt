package com.installer.toolbox.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.installer.toolbox.common.util.UsageStats
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.ota.ApkUpdater
import com.installer.toolbox.ota.DEFAULT_UPDATE_URL
import com.installer.toolbox.ota.UpdateChecker
import com.installer.toolbox.ota.UpdateInfo
import com.installer.toolbox.ui.navigation.Routes
import com.installer.toolbox.ui.navigation.TopLevelDestination
import kotlinx.coroutines.launch
import com.installer.toolbox.ui.screens.ai.AiScreen
import com.installer.toolbox.ui.screens.capture.CaptureScreen
import com.installer.toolbox.ui.screens.diagnose.DiagnoseScreen
import com.installer.toolbox.ui.screens.discovery.DeviceDiscoveryScreen
import com.installer.toolbox.ui.screens.hik.HikScreen
import com.installer.toolbox.ui.screens.home.HomeScreen
import com.installer.toolbox.ui.screens.remote.RemoteControlScreen
import com.installer.toolbox.ui.screens.settings.SettingsScreen
import com.installer.toolbox.ui.screens.tools.ArpDetectScreen
import com.installer.toolbox.ui.screens.tools.BarcodeScreen
import com.installer.toolbox.ui.screens.tools.CableTestScreen
import com.installer.toolbox.ui.screens.tools.ModemWebScreen
import com.installer.toolbox.ui.screens.tools.BleScanScreen
import com.installer.toolbox.ui.screens.settings.DiagnosticInfoScreen
import com.installer.toolbox.ui.screens.settings.FeedbackScreen
import com.installer.toolbox.ui.screens.sos.SosScreen
import com.installer.toolbox.ui.screens.tools.GatewayConflictScreen
import com.installer.toolbox.ui.screens.tools.LoopDetectScreen
import com.installer.toolbox.ui.screens.tools.NmapScanScreen
import com.installer.toolbox.ui.screens.tools.PcdnDetectScreen
import com.installer.toolbox.ui.screens.tools.RemoteShellScreen
import com.installer.toolbox.ui.screens.tools.SilenceScreen
import com.installer.toolbox.ui.screens.tools.SnmpScreen
import com.installer.toolbox.ui.screens.tools.SubnetCalcScreen
import com.installer.toolbox.ui.screens.tools.TrafficAuditScreen
import com.installer.toolbox.ui.screens.tools.WakeOnLanScreen
import com.installer.toolbox.ui.screens.tools.WeakNetworkScreen
import com.installer.toolbox.ui.screens.wifi.AviationSignalScreen
import com.installer.toolbox.ui.screens.wifi.WifiAnalyzeScreen
import com.installer.toolbox.ui.screens.wifi.WifiRadarScreen

/**
 * 主界面：Scaffold + 底部 3 tab（首页 / AI 助手 / 设置）+ NavHost。
 *
 * 二级功能页（device_discovery / diagnosis / wifi / capture / camera）从首页进入，
 * 走导航返回栈返回，不占用底部 tab；底部 tab 按"所属顶级"高亮。
 */
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 功能使用统计：页面路由变化时计数（本地存储，诊断信息页展示）
    LaunchedEffect(currentRoute) {
        currentRoute?.let { UsageStats.count(context, it) }
    }

    // ---- 首次安装统一授权（用户要求：所有权限一次性提示，避免各功能页零散弹窗）----
    // 首次启动弹一次说明框 → 一键申请全部运行时权限 → 记录已提示（拒绝也可稍后在功能页单独授权）
    var showFirstPerm by remember { mutableStateOf(false) }
    val firstPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .edit().putBoolean("first_perm_done", true).apply()
        showFirstPerm = false
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("first_perm_done", false)) {
            showFirstPerm = true
        }
    }

    // ---- 启动检查更新（配置了更新源才查，静默失败不打扰）----
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("ota", Context.MODE_PRIVATE)
        val ignored = prefs.getString("ignored_version", null)
        val info = UpdateChecker.checkUpdate(DEFAULT_UPDATE_URL).getOrNull() ?: return@LaunchedEffect
        val cur = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        }.getOrDefault("0")
        if (UpdateChecker.isNewer(cur, info.latestVersion) && info.latestVersion != ignored) {
            updateInfo = info
        }
    }

    Scaffold(
        bottomBar = {
            // 远程终端页隐藏底部导航：终端全屏显示（命令输入行在最底部、不再被导航栏遮挡），
            // 返回走页面顶部返回键；其余页面底部导航照常显示
            if (currentRoute != Routes.REMOTE_SHELL) {
                // 底部导航：跟随系统亮暗（浅色=浅蓝白底+蓝图标；深色=深蓝底+白图标）
                val isDarkNav = isSystemInDarkTheme()
                val navContainer = if (isDarkNav) Color(0xFF15233F) else MaterialTheme.colorScheme.surfaceContainer
                NavigationBar(
                    containerColor = navContainer
                ) {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = isDestinationSelected(currentRoute, destination),
                            onClick = {
                                navController.navigate(destination.route) {
                                    // 回退到起始页并复用状态，避免重复入栈
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            // 图标色跟随亮暗：浅色=品牌蓝图标+浅蓝指示器；深色=白图标+亮蓝指示器
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = if (isDarkNav) Color.White else MaterialTheme.colorScheme.primary,
                                selectedTextColor = if (isDarkNav) Color.White else MaterialTheme.colorScheme.primary,
                                indicatorColor = if (isDarkNav) Color(0xFF1E6FD9) else MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = if (isDarkNav) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = if (isDarkNav) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            // 统一为所有页面（含二级功能页）应用 Scaffold 的 contentPadding：
            // bottom 已含底部导航栏（NavigationBar）高度，二级页内容底部不会被导航栏遮挡；
            // consumeWindowInsets 防止自带 Scaffold 的二级页在内层重复叠加系统 insets
            // （避免 edge-to-edge 设备上底部多出导航条高度/内容错位）。
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            // ---- 三个顶级 tab ----
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable(TopLevelDestination.AI.route) {
                AiScreen(onNavigateToSettings = {
                    navController.navigate(TopLevelDestination.SETTINGS.route)
                })
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(onNavigate = { route -> navController.navigate(route) })
            }

            // ---- 二级功能页（首页进入，返回键回首页）----
            composable(Routes.DEVICE_DISCOVERY) { DeviceDiscoveryScreen() }
            composable(Routes.DIAGNOSIS) { DiagnoseScreen() }
            composable(Routes.WIFI) { WifiAnalyzeScreen() }
            composable(Routes.WIFI_RADAR) { WifiRadarScreen() }
            composable(Routes.AVIATION_SIGNAL) { AviationSignalScreen() }
            composable(Routes.CAPTURE) { CaptureScreen() }
            composable(Routes.CAMERA) { HikScreen() }
            composable(Routes.SUBNET_CALC) { SubnetCalcScreen() }
            composable(Routes.ARP_DETECT) { ArpDetectScreen() }
            composable(Routes.GATEWAY_CONFLICT) { GatewayConflictScreen() }
            composable(Routes.BLE_SCAN) { BleScanScreen() }
            composable(Routes.NMAP_SCAN) { NmapScanScreen() }
            composable(Routes.PCDN_DETECT) {
                PcdnDetectScreen(
                    onNavigateToAudit = { ip, passthrough ->
                        navController.navigate(
                            "traffic_audit?ip=${android.net.Uri.encode(ip)}" +
                                "&mode=${if (passthrough) "passthrough" else ""}"
                        )
                    }
                )
            }
            composable(
                route = Routes.TRAFFIC_AUDIT,
                arguments = listOf(
                    navArgument("ip") { defaultValue = "" },
                    navArgument("mode") { defaultValue = "" }
                )
            ) { backStackEntry ->
                TrafficAuditScreen(
                    // 过滤路由占位符字面值（首页入口不带参跳转时防 "{ip}" 泄漏进输入框）
                    initialIp = backStackEntry.arguments?.getString("ip")
                        ?.takeIf { it.isNotBlank() && it != "{ip}" },
                    // 二级路由卡片跳转时自动切『借道审计』模式
                    initialPassthrough = backStackEntry.arguments?.getString("mode") == "passthrough"
                )
            }
            composable(Routes.LOOP_DETECT) { LoopDetectScreen() }
            composable(Routes.SILENCE) { SilenceScreen() }
            composable(Routes.BARCODE_SCAN) { BarcodeScreen(initialTab = 0) }
            // 首页右上角快捷扫码：barcode_scan?quick=1 直进扫码识别页
            composable(
                route = Routes.BARCODE_SCAN + "?quick={quick}",
                arguments = listOf(androidx.navigation.navArgument("quick") { defaultValue = "0" })
            ) { entry ->
                BarcodeScreen(
                    initialTab = if (entry.arguments?.getString("quick") == "1") 2 else 0
                )
            }
            composable(Routes.REMOTE_SHELL) { RemoteShellScreen() }
            composable(Routes.REMOTE_CONTROL) { RemoteControlScreen() }
            composable(Routes.WEAK_NETWORK) { WeakNetworkScreen() }
            composable(Routes.SNMP) { SnmpScreen() }
            composable(Routes.CABLE_TEST) { CableTestScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.MODEM_WEB) { ModemWebScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.WAKE_ON_LAN) { WakeOnLanScreen() }
            // ---- 设置页二级页（反馈与诊断信息）----
            composable(Routes.FEEDBACK) { FeedbackScreen() }
            composable(Routes.DIAGNOSTIC_INFO) { DiagnosticInfoScreen() }
            // ---- SOS 紧急求救（首页第一位）----
            composable(Routes.SOS) { SosScreen() }
        }
    }

    // ---- 首次安装统一授权弹窗（所有运行时权限一次说明+一键申请）----
    if (showFirstPerm) {
        AlertDialog(
            onDismissRequest = {
                context.getSharedPreferences("app", Context.MODE_PRIVATE)
                    .edit().putBoolean("first_perm_done", true).apply()
                showFirstPerm = false
            },
            title = { Text("授权说明（首次安装一次性提示）") },
            text = {
                Text(
                    "装维通为完整使用各项功能，需要以下权限：\n\n" +
                        "· 定位：SOS 求救位置、Wi-Fi 扫描\n" +
                        "· 短信发送：SOS 紧急求救短信（仅在你点击求救按钮时发送）\n" +
                        "· 电话拨打：SOS 一键拨打紧急联系人（仅点击时触发）\n" +
                        "· 相机/手电：SOS 声光摩斯闪光、扫码\n" +
                        "· 蓝牙：BLE 设备扫描\n" +
                        "· 通知：更新/诊断提示\n\n" +
                        "点『一键全部授权』可一次完成；拒绝后各功能页内仍可单独授权。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    firstPermLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.CAMERA,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.POST_NOTIFICATIONS,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                }) { Text("一键全部授权") }
            },
            dismissButton = {
                TextButton(onClick = {
                    context.getSharedPreferences("app", Context.MODE_PRIVATE)
                        .edit().putBoolean("first_perm_done", true).apply()
                    showFirstPerm = false
                }) { Text("暂不授权") }
            }
        )
    }

    // ---- 更新弹窗（启动检查发现新版本时弹出）----
    updateInfo?.let { info ->        AlertDialog(
            onDismissRequest = { if (!updating) updateInfo = null },
            title = { Text("发现新版本 ${info.latestVersion}") },
            text = {
                Column {
                    Text(info.changelog ?: "本次更新内容未知")
                    Spacer(Modifier.height(8.dp))
                    updateProgress?.let { (done, total) ->
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "下载中 ${done / 1024}KB / ${total / 1024}KB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    updateResult?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !updating,
                    onClick = {
                        scope.launch {
                            updating = true
                            updateResult = null
                            val apk = ApkUpdater.download(context, DEFAULT_UPDATE_URL, info, { d, t -> updateProgress = d to t })
                            if (apk == null) {
                                updateResult = "下载失败，请检查更新源与网络后重试"
                            } else {
                                updateResult = ApkUpdater.install(context, apk, RootShell::run)
                            }
                            updating = false
                        }
                    }
                ) { Text(if (updating) "处理中…" else "下载并安装") }
            },
            dismissButton = {
                TextButton(
                    enabled = !updating,
                    onClick = {
                        context.getSharedPreferences("ota", Context.MODE_PRIVATE).edit()
                            .putString("ignored_version", info.latestVersion).apply()
                        updateInfo = null
                    }
                ) { Text("忽略此版本") }
            }
        )
    }
}

/**
 * 底部 tab 选中态：
 * - 当前路由等于该顶级路由 → 选中；
 * - 当前是归属该顶级的二级路由（如 device_discovery 属 HOME、feedback 属 SETTINGS）→ 该顶级高亮。
 */
private fun isDestinationSelected(currentRoute: String?, destination: TopLevelDestination): Boolean {
    if (currentRoute == null) return false
    if (currentRoute == destination.route) return true
    return when (destination) {
        TopLevelDestination.HOME -> currentRoute in Routes.HOME_SECONDARY
        TopLevelDestination.SETTINGS -> currentRoute in Routes.SETTINGS_SECONDARY
        TopLevelDestination.AI -> false
    }
}
