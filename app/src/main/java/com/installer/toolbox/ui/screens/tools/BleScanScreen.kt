package com.installer.toolbox.ui.screens.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.installer.toolbox.core.network.scan.BleScanner
import com.installer.toolbox.core.network.scan.BleScanner.BleScanResult
import kotlinx.coroutines.launch

/**
 * BLE 扫描页：请求蓝牙/定位权限后扫描周边低功耗蓝牙设备，
 * 展示名称/地址/RSSI/厂商数据（按信号强度排序）。
 */
@Composable
fun BleScanScreen() {
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BleScanResult?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 按系统版本决定运行时权限：Android 12+ 用蓝牙权限，Android 11 及以下用定位权限
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var permissionOk by remember { mutableStateOf(checkBlePermission(context) == null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionOk = checkBlePermission(context) == null
    }
    // 首次进入且缺权限时自动弹请求
    LaunchedEffect(Unit) {
        if (!permissionOk) permissionLauncher.launch(permissions)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "BLE 扫描",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "扫描周边蓝牙低功耗（BLE）设备：名称、地址、信号强度（RSSI）、厂商数据，排查物联网设备与干扰。需要蓝牙和定位权限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (!permissionOk) {
                    permissionLauncher.launch(permissions)
                } else {
                    scope.launch {
                        scanning = true
                        result = BleScanner.scan(context.applicationContext)
                        scanning = false
                    }
                }
            },
            enabled = !scanning
        ) {
            Text(if (scanning) "扫描中…" else "开始扫描")
        }
        Spacer(Modifier.height(12.dp))

        if (!permissionOk) {
            Text(
                text = "缺少蓝牙/定位权限，请点击\"开始扫描\"或到系统设置中授予权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
        }

        result?.let { r ->
            r.error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            if (r.devices.isEmpty() && r.error == null) {
                Text(
                    text = "未发现 BLE 设备（可把设备靠近手机后重试）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            r.devices.forEach { d ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = d.name ?: "未知设备",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "地址：${d.address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 厂商（识别到显示主色徽章式，未知不显示）
                        d.vendor?.let { vendor ->
                            Text(
                                text = "厂商：$vendor",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "信号：${d.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d.rssi > -70) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        d.manufacturerData?.let { mfg ->
                            Text(
                                text = "厂商数据：$mfg",
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

/** 检查 BLE 扫描所需权限是否齐全：返回 null 表示已具备 */
private fun checkBlePermission(context: Context): String? {
    val app = context.applicationContext
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when {
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ->
                "缺少 BLUETOOTH_SCAN"
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ->
                "缺少 BLUETOOTH_CONNECT"
            else -> null
        }
    } else {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            "缺少 ACCESS_FINE_LOCATION"
        } else {
            null
        }
    }
}
