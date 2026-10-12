package com.installer.toolbox.core.network.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * BLE 设备扫描器（标准 Android API，无需 root）。
 *
 * 用 BluetoothLeScanner + ScanCallback 扫描周边低功耗蓝牙设备，
 * 用 suspendCancellableCoroutine 把回调包装成挂起函数，durationMs 超时自动停止。
 * 结果按 RSSI 降序排列并按 address 去重（后到的结果覆盖旧的，保留最新信号）。
 *
 * 权限要求：
 * - Android 12+（API 31+）：BLUETOOTH_SCAN / BLUETOOTH_CONNECT 运行时权限；
 * - Android 11 及以下：ACCESS_FINE_LOCATION（系统要求 BLE 扫描必须持有定位权限）。
 */
object BleScanner {

    /** BLE 设备信息 */
    data class BleDevice(
        val address: String,
        val name: String?,
        val rssi: Int,
        val manufacturerData: String?,
        /** 识别厂商（设备名关键词优先，其次 manufacturerData 的 Company ID）；未知为 null */
        val vendor: String?
    )

    /** 扫描结果；error 非空表示扫描失败（权限/蓝牙关闭/硬件不支持等） */
    data class BleScanResult(
        val devices: List<BleDevice>,
        val error: String?
    )

    /**
     * 扫描周边 BLE 设备（运行在 IO 线程）。
     *
     * 权限已在入口 permissionError() 用 checkSelfPermission 检查并提前返回，
     * 静态分析跨函数识别不到，故函数级抑制 MissingPermission（与 SosEngine 惯例一致）。
     *
     * @param context 用于获取 BluetoothManager 与权限检查
     * @param durationMs 扫描时长，超时自动停止并返回已收集的设备
     */
    @SuppressLint("MissingPermission")
    suspend fun scan(context: Context, durationMs: Long = 8000): BleScanResult =
        withContext(Dispatchers.IO) {
            val permErr = permissionError(context)
            if (permErr != null) return@withContext BleScanResult(emptyList(), permErr)

            val app = context.applicationContext
            val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null) {
                return@withContext BleScanResult(emptyList(), "设备不支持蓝牙（无 BluetoothAdapter）")
            }
            if (!adapter.isEnabled) {
                return@withContext BleScanResult(emptyList(), "蓝牙未开启，请先在系统设置中打开蓝牙")
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                return@withContext BleScanResult(emptyList(), "无法获取 BLE 扫描器（设备可能不支持低功耗蓝牙）")
            }

            // 收集结果：address → 设备（后到覆盖，保留最新 RSSI），实现按地址去重
            val found = LinkedHashMap<String, BleDevice>()
            var failReason: String? = null

            // 超时返回 null 视为正常结束；onScanFailed 提前 resume 则带错误返回
            withTimeoutOrNull(durationMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val callback = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            val device = result.device ?: return
                            val addr = device.address
                            val mfg = result.scanRecord?.manufacturerSpecificData?.let { md ->
                                (0 until md.size()).map { md.keyAt(it) }.sorted()
                                    .joinToString("; ") { key ->
                                        val bytes = md[key] ?: return@joinToString ""
                                        "$key=${bytes.joinToString("") { b ->
                                            (b.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                                        }}"
                                    }
                            }
                            // vendor 解析包 try/catch：任何意外异常不得中断设备收集（否则后续设备丢失）
                            val vendor = try {
                                vendorOf(device.name, firstCompanyId(result))
                            } catch (e: Exception) {
                                null
                            }
                            found[addr] = BleDevice(
                                address = addr,
                                name = device.name,
                                rssi = result.rssi,
                                manufacturerData = mfg?.ifBlank { null },
                                vendor = vendor
                            )
                        }

                        override fun onScanFailed(errorCode: Int) {
                            failReason = "扫描失败（错误码 $errorCode）：蓝牙可能被占用或系统限制"
                            scanner.stopScan(this)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    // startScan 可能因蓝牙状态异常抛 SecurityException/IllegalStateException，
                    // 捕获后立即结束，避免协程挂到超时
                    var started = false
                    try {
                        scanner.startScan(callback)
                        started = true
                    } catch (e: Exception) {
                        failReason = "启动扫描失败：${e.message ?: e.javaClass.simpleName}"
                        if (cont.isActive) cont.resume(Unit)
                    }
                    if (started) {
                        // 协程取消（超时 / 页面销毁）时停止扫描，避免回调泄漏
                        cont.invokeOnCancellation { scanner.stopScan(callback) }
                    }
                }
            }

            val devices = found.values.sortedByDescending { it.rssi }
            BleScanResult(devices, failReason)
        }

    /**
     * 权限检查：返回缺失权限的错误文案，全部具备返回 null。
     * 按系统版本区分：Android 12+ 用蓝牙运行时权限，Android 11 及以下用定位权限。
     */
    private fun permissionError(context: Context): String? {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when {
                ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ->
                    "缺少蓝牙扫描权限（BLUETOOTH_SCAN），请授予权限后重试"
                ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ->
                    "缺少蓝牙连接权限（BLUETOOTH_CONNECT），请授予权限后重试"
                else -> null
            }
        } else {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                "缺少定位权限（ACCESS_FINE_LOCATION）：Android 11 及以下系统要求 BLE 扫描必须持有定位权限"
            } else {
                null
            }
        }
    }

    // ==================== 厂商识别 ====================

    /**
     * 设备名关键词 → 中文厂商（优先匹配；覆盖小米/华为/苹果等常见 BLE 设备名）。
     * 顺序在前者优先（避免 "mi" 误匹配 "amazon"/"dimension" 等——用单词边界敏感词）。
     */
    private val NAME_KEYWORDS = listOf(
        "airpods" to "苹果", "apple watch" to "苹果", "iphone" to "苹果", "ipad" to "苹果", "macbook" to "苹果", "apple" to "苹果",
        "huawei" to "华为", "honor" to "荣耀", "honor band" to "荣耀",
        "xiaomi" to "小米", "mi band" to "小米", "mi smart" to "小米", "mi watch" to "小米", "redmi" to "红米", "mijia" to "米家", "lumi" to "米家",
        "samsung" to "三星", "galaxy" to "三星", "gear fit" to "三星",
        "sony" to "索尼",
        "oppo" to "OPPO", "vivo" to "vivo", "oneplus" to "一加", "realme" to "真我", "iqoo" to "iQOO",
        "amazfit" to "华米", "huami" to "华米", "zepp" to "华米",
        "midea" to "美的", "gree" to "格力", "xiaodu" to "小度", "tmall genie" to "天猫精灵",
        "broadlink" to "博联", "smartmi" to "智米", "yeelight" to "Yeelight", "aqara" to "绿米", "qingping" to "青萍",
        "dji" to "大疆", "garmin" to "佳明", "fitbit" to "Fitbit", "polar" to "Polar",
        "ikey" to "IKEA", "tradfri" to "宜家",
        "philips" to "飞利浦", "bosch" to "博世", "logitech" to "罗技", "jbl" to "JBL", "anker" to "Anker", "soundcore" to "Anker",
        "bose" to "Bose", "beats" to "Beats", "marshall" to "马歇尔", "sonos" to "Sonos",
        // 中文设备名关键词（覆盖国产设备中文广播名，如"小米电视/华为手环"）
        "小米" to "小米", "红米" to "红米", "米家" to "米家", "华为" to "华为", "荣耀" to "荣耀",
        "三星" to "三星", "索尼" to "索尼", "苹果" to "苹果", "美的" to "美的", "格力" to "格力", "海尔" to "海尔",
        "大疆" to "大疆", "小度" to "小度", "天猫精灵" to "天猫精灵", "博联" to "博联", "绿米" to "绿米", "智米" to "智米"
    )

    /** manufacturerData 的 Company ID（Bluetooth SIG 分配）→ 厂商；常见映射 */
    private val COMPANY_IDS = mapOf(
        0x004C to "苹果",     // Apple
        0x0006 to "微软",     // Microsoft
        0x0075 to "三星",     // Samsung
        0x000E to "诺基亚",   // Nokia
        0x012D to "索尼",     // Sony
        0x022E to "华为",     // Huawei
        0x00E0 to "Google",   // Google
        0x0002 to "Intel",
        0x0019 to "联想",     // Lenovo? 0x0019 = Hewlett-Packard；联想另有
        0x0012 to "戴尔",     // Dell
        0x0059 to "Nordic",   // Nordic Semiconductor（很多手环/小设备用，非最终品牌）
        0x0047 to "德州仪器", // TI
        0x000D to "德州仪器", // TI
        0x005A to "Dialog",   // Dialog（瑞昱方案常用）
        0x0067 to "意法半导体", // ST
        0x009E to "CSR",      // Cambridge Silicon Radio
        0x0119 to "Jabra",
        0x0121 to "Wahoo",
        0x0157 to "Fitbit",
        0x013B to "Garmin",
        0x007E to "高通",     // Qualcomm
        0x000A to "Broadcom",
        0x0040 to "NXP",
        0x005F to "MediaTek", // 联发科
        0x01F5 to "Nordic"    // nRF52840 等
    )

    /** 解析厂商：设备名关键词优先，其次 manufacturerData 的 Company ID */
    fun vendorOf(name: String?, companyId: Int?): String? {
        val n = name?.lowercase()?.trim() ?: ""
        if (n.isNotEmpty()) {
            NAME_KEYWORDS.firstOrNull { (kw, _) -> n.contains(kw) }?.second?.let { return it }
        }
        return companyId?.let { COMPANY_IDS[it] }
    }

    /** 取 ScanResult 广播中的第一个 Company ID（manufacturerData 的 key，无则 null） */
    private fun firstCompanyId(result: ScanResult): Int? {
        return try {
            result.scanRecord?.manufacturerSpecificData?.let { md ->
                if (md.size() > 0) md.keyAt(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
