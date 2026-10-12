package com.installer.toolbox.ui.screens.discovery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.installer.toolbox.ToolboxApp
import com.installer.toolbox.common.data.DeviceEntity
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.discovery.DeviceScanner
import com.installer.toolbox.core.network.discovery.HostInfo
import com.installer.toolbox.util.ResultCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 设备发现页 UI 状态 */
sealed interface DiscoveryUiState {
    /** 初始状态 */
    data object Idle : DiscoveryUiState

    /** 扫描中 */
    data object Scanning : DiscoveryUiState

    /** 扫描完成 */
    data class Success(val devices: List<HostInfo>) : DiscoveryUiState

    /** 扫描失败 */
    data class Error(val message: String) : DiscoveryUiState
}

/**
 * 设备发现页 ViewModel：驱动扫描、维护进度与结果。
 */
class DeviceDiscoveryViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = DeviceScanner(application)
    private val database = (application as ToolboxApp).database

    private val _uiState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Idle)
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    /** 扫描进度 0..1 */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** 扫描过程中已发现的设备（实时逐台追加，扫描完成即完整列表） */
    private val _foundDevices = MutableStateFlow<List<HostInfo>>(emptyList())
    val foundDevices: StateFlow<List<HostInfo>> = _foundDevices.asStateFlow()

    /** 运行日志（root 命令执行过程，供运行日志面板显示） */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** 当前扫描网段 */
    private val _subnet = MutableStateFlow("")
    val subnet: StateFlow<String> = _subnet.asStateFlow()

    init {
        // 自动取当前 Wi-Fi 网段
        _subnet.value = DeviceScanner.currentSubnet(application, RootShell::run)
    }

    /** 开始扫描当前网段 */
    fun startScan() {
        if (_uiState.value is DiscoveryUiState.Scanning) return
        viewModelScope.launch {
            _uiState.value = DiscoveryUiState.Scanning
            _progress.value = 0f
            _foundDevices.value = emptyList()
            // 运行日志：清空上一轮，执行期间收集 root 命令过程（便于反馈改进）
            _logs.value = emptyList()
            RootShell.logListener = { line -> _logs.value = _logs.value + line }
            try {
                val devices = scanner.scan(
                    subnet = _subnet.value,
                    rootRun = if (RootShell.isAvailable()) RootShell::run else null,
                    onProgress = { done, total ->
                        _progress.value = done.toFloat() / total.toFloat()
                    },
                    onHost = { host ->
                        // 实时逐台追加到 UI（扫出一个显示一个，避免长时间无反馈）
                        _foundDevices.value = _foundDevices.value + host
                    }
                )
                // 结果缓存（弱网现场/离线回看）
                saveResult(devices)
                _uiState.value = DiscoveryUiState.Success(devices)
            } catch (e: Exception) {
                _uiState.value = DiscoveryUiState.Error(e.message ?: "扫描失败")
            } finally {
                RootShell.logListener = null
            }
        }
    }

    /** 保存设备结果到本地缓存（弱网现场/离线回看） */
    private fun saveResult(devices: List<HostInfo>) {
        val json = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("subnet", _subnet.value)
            put(
                "devices",
                JSONArray().apply {
                    devices.forEach { h ->
                        put(
                            JSONObject().apply {
                                put("ip", h.ip)
                                put("mac", h.mac ?: "")
                                put("vendor", h.vendor ?: "")
                                put("hostname", h.hostname ?: "")
                                put("ports", h.openPorts.joinToString(","))
                            }
                        )
                    }
                }
            )
        }.toString()
        ResultCache.save(getApplication(), ResultCache.KEY_DEVICES, json)
    }

    /** 是否有缓存的设备结果 */
    fun hasCachedResult(): Boolean =
        ResultCache.has(getApplication(), ResultCache.KEY_DEVICES)

    /** 缓存时间文案（MM-dd HH:mm） */
    fun cachedTimeText(): String =
        ResultCache.timeText(getApplication(), ResultCache.KEY_DEVICES)

    /** 恢复上次缓存的设备结果（弱网/离线回看） */
    fun loadCachedResult() {
        val json = ResultCache.load(getApplication(), ResultCache.KEY_DEVICES) ?: return
        try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("devices") ?: return
            val devices = (0 until arr.length()).mapNotNull { i ->
                val d = arr.getJSONObject(i)
                val ports = d.optString("ports").split(",").mapNotNull { it.toIntOrNull() }
                HostInfo(
                    ip = d.optString("ip"),
                    mac = d.optString("mac").ifEmpty { null },
                    vendor = d.optString("vendor").ifEmpty { null },
                    hostname = d.optString("hostname").ifEmpty { null },
                    openPorts = ports,
                    lastSeen = obj.optLong("time")
                )
            }
            _uiState.value = DiscoveryUiState.Success(devices)
        } catch (e: Exception) {
            // 缓存损坏则忽略
        }
    }

    /** 保存设备到本地清单（收藏） */
    fun saveToFavorites(host: HostInfo) {
        viewModelScope.launch {
            try {
                database.deviceDao().upsert(
                    DeviceEntity(
                        id = host.mac?.takeIf { it.isNotBlank() } ?: host.ip,
                        ip = host.ip,
                        mac = host.mac.orEmpty(),
                        vendor = host.vendor.orEmpty(),
                        hostname = host.hostname.orEmpty(),
                        openPorts = host.openPorts.joinToString(","),
                        lastSeen = host.lastSeen
                    )
                )
            } catch (e: Exception) {
                // 保存失败静默处理，不影响扫描结果展示
            }
        }
    }
}
