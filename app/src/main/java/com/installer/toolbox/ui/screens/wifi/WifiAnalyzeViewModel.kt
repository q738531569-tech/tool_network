package com.installer.toolbox.ui.screens.wifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.installer.toolbox.core.network.wifi.WifiAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Wi-Fi 分析页 UI 状态 */
sealed interface WifiUiState {
    /** 未扫描 */
    data object Idle : WifiUiState

    /** 扫描中 */
    data object Scanning : WifiUiState

    /** 扫描完成 */
    data class Success(val aps: List<WifiAnalyzer.ApInfo>) : WifiUiState

    /** 扫描失败（含未授权） */
    data class Error(val message: String) : WifiUiState
}

/**
 * Wi-Fi 分析页 ViewModel：当前连接信息 + 周边 AP 扫描。
 */
class WifiAnalyzeViewModel(application: Application) : AndroidViewModel(application) {

    /** 当前连接信息（可能为 null：未连接或权限不足） */
    private val _connected = MutableStateFlow<WifiAnalyzer.ConnectedWifi?>(null)
    val connected: StateFlow<WifiAnalyzer.ConnectedWifi?> = _connected.asStateFlow()

    private val _uiState = MutableStateFlow<WifiUiState>(WifiUiState.Idle)
    val uiState: StateFlow<WifiUiState> = _uiState.asStateFlow()

    /** 已连接 Wi-Fi 的优化建议（扫描后根据周边 AP 生成） */
    private val _suggestions = MutableStateFlow<List<WifiAnalyzer.WifiSuggestion>>(emptyList())
    val suggestions: StateFlow<List<WifiAnalyzer.WifiSuggestion>> = _suggestions.asStateFlow()

    /** 刷新当前连接信息（应在授权后调用） */
    fun refreshConnected() {
        _connected.value = WifiAnalyzer.currentConnection(getApplication())
    }

    /** 扫描周边 AP；未授权会进入 Error 状态并给出提示 */
    fun startScan() {
        if (_uiState.value is WifiUiState.Scanning) return
        viewModelScope.launch {
            _uiState.value = WifiUiState.Scanning
            try {
                val aps = WifiAnalyzer.scanAround(getApplication())
                _uiState.value = WifiUiState.Success(aps)
                // 扫描后刷新连接信息并生成优化建议
                val conn = WifiAnalyzer.currentConnection(getApplication())
                _connected.value = conn
                _suggestions.value = conn?.let { WifiAnalyzer.suggestions(it, aps) }.orEmpty()
            } catch (e: Exception) {
                _uiState.value = WifiUiState.Error(e.message ?: "扫描失败")
            }
        }
    }
}
