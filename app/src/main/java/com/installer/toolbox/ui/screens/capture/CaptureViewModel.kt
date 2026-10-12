package com.installer.toolbox.ui.screens.capture

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.installer.toolbox.core.capture.CaptureBus
import com.installer.toolbox.core.capture.PacketCaptureService
import com.installer.toolbox.core.capture.StormState
import com.installer.toolbox.core.capture.TrafficSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 抓包分析页 ViewModel：
 * - 订阅 [CaptureBus] 的快照流，维护最新快照与风暴状态；
 * - 提供 [startAfterVpnPermission]（VPN 授权通过后由 Screen 调用）与 [stop]。
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    /** 抓包服务是否在运行 */
    private val _isRunning = MutableStateFlow(PacketCaptureService.isRunning())
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 最新统计快照（未抓包或首个周期未到时为 null） */
    private val _latest = MutableStateFlow<TrafficSnapshot?>(null)
    val latest: StateFlow<TrafficSnapshot?> = _latest.asStateFlow()

    /** 当前风暴状态 */
    private val _storm = MutableStateFlow(StormState.NORMAL)
    val storm: StateFlow<StormState> = _storm.asStateFlow()

    init {
        viewModelScope.launch {
            CaptureBus.flow.collect { snapshot ->
                _latest.value = snapshot
                _storm.value = snapshot.stormState
            }
        }
    }

    /**
     * VPN 授权通过后真正启动前台抓包服务。
     * 由 Screen 在 VpnService.prepare() 结果（或已授权）后调用。
     */
    fun startAfterVpnPermission() {
        PacketCaptureService.start(getApplication())
        _isRunning.value = true
    }

    /** 停止抓包服务（VpnService 需显式停止命令，见 PacketCaptureService.stop） */
    fun stop() {
        PacketCaptureService.stop(getApplication())
        _isRunning.value = false
    }
}
