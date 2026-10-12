package com.installer.toolbox.ui.screens.diagnose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.installer.toolbox.ToolboxApp
import com.installer.toolbox.common.data.DiagReportEntity
import com.installer.toolbox.core.agent.RootShell
import com.installer.toolbox.core.network.diagnose.DnsTool
import com.installer.toolbox.core.network.diagnose.PingTool
import com.installer.toolbox.core.network.diagnose.PortScanner
import com.installer.toolbox.core.network.diagnose.TracerouteTool
import com.installer.toolbox.core.network.scan.LanInterfaceResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 诊断工具统一的 UI 状态 */
sealed interface DiagUiState<out T> {
    /** 未执行 */
    data object Idle : DiagUiState<Nothing>

    /** 执行中 */
    data object Loading : DiagUiState<Nothing>

    /** 成功 */
    data class Success<T>(val data: T) : DiagUiState<T>

    /** 失败 */
    data class Error(val message: String) : DiagUiState<Nothing>
}

/**
 * 网络诊断页 ViewModel：Ping / Traceroute / DNS / 端口扫描。
 * 每次执行完成后自动保存报告到 Room。
 */
class DiagnoseViewModel(application: Application) : AndroidViewModel(application) {

    private val database = (application as ToolboxApp).database

    /** 诊断目标 */
    private val _target = MutableStateFlow("192.168.1.1")
    val target: StateFlow<String> = _target.asStateFlow()

    private val _pingState =
        MutableStateFlow<DiagUiState<PingTool.PingResult>>(DiagUiState.Idle)
    val pingState: StateFlow<DiagUiState<PingTool.PingResult>> = _pingState.asStateFlow()

    /** TCP Ping 探测端口（默认 80，可切 443） */
    private val _pingPort = MutableStateFlow(80)
    val pingPort: StateFlow<Int> = _pingPort.asStateFlow()

    private val _traceState =
        MutableStateFlow<DiagUiState<List<TracerouteTool.TraceHop>>>(DiagUiState.Idle)
    val traceState: StateFlow<DiagUiState<List<TracerouteTool.TraceHop>>> = _traceState.asStateFlow()

    private val _dnsState =
        MutableStateFlow<DiagUiState<DnsTool.DnsResult>>(DiagUiState.Idle)
    val dnsState: StateFlow<DiagUiState<DnsTool.DnsResult>> = _dnsState.asStateFlow()

    private val _portState =
        MutableStateFlow<DiagUiState<PortScanner.PortScanResult>>(DiagUiState.Idle)
    val portState: StateFlow<DiagUiState<PortScanner.PortScanResult>> = _portState.asStateFlow()

    /** 运行日志（root 命令执行过程，供运行日志面板显示） */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun setTarget(value: String) {
        _target.value = value
    }

    /** 切换 TCP Ping 端口（80 / 443） */
    fun setPingPort(port: Int) {
        _pingPort.value = port
    }

    /**
     * root 时解析局域网接口 IP，供诊断工具绑定源地址。
     * 无 root 或解析失败返回 null，保持系统默认路由行为（与现状一致）。
     */
    private fun lanBindIp(): String? =
        if (RootShell.isAvailable()) {
            try {
                LanInterfaceResolver.resolve(RootShell::run)?.ip
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

    /** 在 root 命令日志收集下执行诊断任务（每次执行前清空日志、执行后恢复 null） */
    private suspend fun <T> collectLogs(block: suspend () -> T): T {
        _logs.value = emptyList()
        RootShell.logListener = { line -> _logs.value = _logs.value + line }
        return try {
            block()
        } finally {
            RootShell.logListener = null
        }
    }

    /** 执行 TCP Ping */
    fun runPing() {
        val host = _target.value.trim()
        if (host.isEmpty()) return
        viewModelScope.launch {
            _pingState.value = DiagUiState.Loading
            try {
                val result = collectLogs { PingTool.ping(host, port = _pingPort.value, bindIp = lanBindIp()) }
                _pingState.value = DiagUiState.Success(result)
                saveReport(
                    type = "ping",
                    target = host,
                    summary = "丢包率 ${result.lossPercent}% · 平均 ${result.avgMs ?: "-"}ms",
                    detail = formatPing(result)
                )
            } catch (e: Exception) {
                _pingState.value = DiagUiState.Error(e.message ?: "Ping 失败")
            }
        }
    }

    /** 执行变通 Traceroute */
    fun runTrace() {
        val host = _target.value.trim()
        if (host.isEmpty()) return
        viewModelScope.launch {
            _traceState.value = DiagUiState.Loading
            try {
                val hops = collectLogs { TracerouteTool.trace(host, bindIp = lanBindIp()) }
                _traceState.value = DiagUiState.Success(hops)
                val reached = hops.any { it.reached }
                saveReport(
                    type = "traceroute",
                    target = host,
                    summary = if (reached) "已到达（${hops.size} 跳）" else "未到达（${hops.size} 跳后放弃）",
                    detail = formatTrace(hops)
                )
            } catch (e: Exception) {
                _traceState.value = DiagUiState.Error(e.message ?: "Traceroute 失败")
            }
        }
    }

    /** 执行 DNS 查询 */
    fun runDns() {
        val host = _target.value.trim()
        if (host.isEmpty()) return
        viewModelScope.launch {
            _dnsState.value = DiagUiState.Loading
            try {
                val result = collectLogs { DnsTool.query(host, bindIp = lanBindIp()) }
                _dnsState.value = DiagUiState.Success(result)
                saveReport(
                    type = "dns",
                    target = host,
                    summary = "${result.records.size} 条记录 · 耗时 ${result.elapsedMs}ms",
                    detail = formatDns(result)
                )
            } catch (e: Exception) {
                _dnsState.value = DiagUiState.Error(e.message ?: "DNS 查询失败")
            }
        }
    }

    /** 执行端口扫描 */
    fun runPortScan() {
        val host = _target.value.trim()
        if (host.isEmpty()) return
        viewModelScope.launch {
            _portState.value = DiagUiState.Loading
            try {
                val result = collectLogs { PortScanner.scan(host, bindIp = lanBindIp()) }
                _portState.value = DiagUiState.Success(result)
                saveReport(
                    type = "portscan",
                    target = host,
                    summary = "发现 ${result.openPorts.size} 个开放端口 · 耗时 ${result.elapsedMs}ms",
                    detail = formatPortScan(result)
                )
            } catch (e: Exception) {
                _portState.value = DiagUiState.Error(e.message ?: "端口扫描失败")
            }
        }
    }

    /** 保存诊断报告到 Room（失败不影响结果展示） */
    private suspend fun saveReport(type: String, target: String, summary: String, detail: String) {
        try {
            database.diagReportDao().insert(
                DiagReportEntity(
                    type = type,
                    target = target,
                    summary = summary,
                    detail = detail,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            // 忽略保存失败
        }
    }

    /** Ping 完整报告文本（分享/导出/存 Room 共用一份，含标题与时间） */
    fun formatPing(r: PingTool.PingResult): String = buildString {
        appendLine("装维通 · Ping 诊断")
        appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
        appendLine("目标：${r.target}（端口 ${_pingPort.value}）")
        appendLine("发送/接收：${r.sent}/${r.received}  丢包率：${r.lossPercent}%")
        appendLine("延迟：平均 ${r.avgMs ?: "-"}ms  最小 ${r.minMs ?: "-"}ms  最大 ${r.maxMs ?: "-"}ms")
    }

    /** Traceroute 报告文本（导出用） */
    fun formatTrace(hops: List<TracerouteTool.TraceHop>): String = buildString {
        hops.forEach { h ->
            val ip = h.ip.ifBlank { "未知" }
            appendLine("第 ${h.hop} 跳：$ip ${h.elapsedMs}ms ${if (h.reached) "（到达）" else "（超时）"}")
        }
    }

    /** DNS 报告文本（导出用） */
    fun formatDns(r: DnsTool.DnsResult): String = buildString {
        appendLine("目标：${r.host} · 状态：${r.status} · 耗时 ${r.elapsedMs}ms")
        r.records.forEach { rec ->
            appendLine("${rec.type} ${rec.name} -> ${rec.rdata}")
        }
    }

    /** 端口扫描报告文本（导出用） */
    fun formatPortScan(r: PortScanner.PortScanResult): String = buildString {
        appendLine("目标：${r.target} · 扫描 ${r.scannedCount} 个端口 · 耗时 ${r.elapsedMs}ms")
        r.openPorts.forEach { p ->
            appendLine("端口 ${p.port} 开放${p.service?.let { "（$it）" } ?: ""}")
        }
    }
}
