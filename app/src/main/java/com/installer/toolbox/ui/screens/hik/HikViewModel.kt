package com.installer.toolbox.ui.screens.hik

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.installer.toolbox.core.isapi.HikDevice
import com.installer.toolbox.core.isapi.HikvisionApi
import com.installer.toolbox.core.isapi.HikvisionApi.Method
import com.installer.toolbox.core.isapi.OnvifClient
import com.installer.toolbox.core.isapi.OnvifClient.OnvifDeviceInfo
import com.installer.toolbox.core.isapi.OnvifDevice
import com.installer.toolbox.core.isapi.OnvifDiscovery
import com.installer.toolbox.core.isapi.PasswordProbe
import com.installer.toolbox.core.network.util.IpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 摄像头页协议模式 */
enum class CamMode {
    /** ONVIF WS-Discovery 自动发现（兼容海康/大华/seetong/宇视等） */
    ONVIF,
    /** 海康 SADP 私有协议发现 */
    SADP
}

/** ONVIF 设备列表项：发现结果 + 自动识别出的型号 */
data class OnvifDeviceItem(
    val device: OnvifDevice,
    /** 自动识别出的型号（识别中为 "识别中…"，失败为 "识别失败"） */
    val model: String = "识别中…",
    /** 匿名 GetDeviceInformation 结果（识别失败为 null） */
    val info: OnvifDeviceInfo? = null
)

/** 选中 ONVIF 设备的详情操作区状态 */
data class OnvifDetail(
    val device: OnvifDevice,
    /** 设备信息文本（"获取信息" 的结果） */
    val infoText: String = "",
    /** 该设备当前是否有操作进行中 */
    val busy: Boolean = false,
    /** 最近一次操作结果文案（成功/失败） */
    val lastResult: String? = null
)

/** 选中设备的详情操作区状态 */
data class HikDetail(
    val device: HikDevice,
    /** 设备信息文本（"获取设备信息" 的结果） */
    val infoText: String = "",
    /** 该设备当前是否有操作进行中 */
    val busy: Boolean = false,
    /** 最近一次操作结果文案（成功/失败） */
    val lastResult: String? = null
)

/**
 * 摄像头页 ViewModel：ONVIF 自动发现/配置操作 + 海康 SADP 发现/ISAPI 配置操作。
 *
 * 注意：底层是 gomobile 编译的 Go 库（阻塞 native 调用），ONVIF 也是网络 IO，
 * 所有网络操作必须放到 Dispatchers.IO，绝不能跑在主线程。
 */
class HikViewModel(application: Application) : AndroidViewModel(application) {

    // ===================== 海康 SADP 模式（原有逻辑保留） =====================

    /** 发现到的设备列表 */
    private val _devices = MutableStateFlow<List<HikDevice>>(emptyList())
    val devices: StateFlow<List<HikDevice>> = _devices.asStateFlow()

    /** 是否正在发现中 */
    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    /** 发现错误信息（null 表示成功） */
    private val _discoverError = MutableStateFlow<String?>(null)
    val discoverError: StateFlow<String?> = _discoverError.asStateFlow()

    /** 设备 IP → "用户名|密码"，保存账密便于复用 */
    private val _login = MutableStateFlow<Map<String, String>>(emptyMap())
    val login: StateFlow<Map<String, String>> = _login.asStateFlow()

    /** 当前选中设备的详情操作区状态 */
    private val _detail = MutableStateFlow<HikDetail?>(null)
    val detail: StateFlow<HikDetail?> = _detail.asStateFlow()

    /** 当前正在操作的设备 IP（null 表示空闲），用于防重复点击 */
    private val _busyDevice = MutableStateFlow<String?>(null)
    val busyDevice: StateFlow<String?> = _busyDevice.asStateFlow()

    /** SADP 组播发现局域网海康设备 */
    fun discover() {
        if (_discovering.value) return
        viewModelScope.launch {
            _discovering.value = true
            _discoverError.value = null
            try {
                val (list, err) = withContext(Dispatchers.IO) { HikvisionApi.discover(getApplication()) }
                _devices.value = list
                _discoverError.value = err
            } catch (e: Exception) {
                _discoverError.value = e.message ?: "发现失败"
            } finally {
                _discovering.value = false
            }
        }
    }

    /** 保存设备账密（key 为设备 IP） */
    fun login(ip: String, user: String, pass: String) {
        _login.value = _login.value + (ip to "$user|$pass")
    }

    /** 读取设备已保存的账密（返回 用户名、密码） */
    private fun credOf(ip: String): Pair<String, String>? {
        val raw = _login.value[ip] ?: return null
        val parts = raw.split("|", limit = 2)
        return if (parts.size == 2 && parts[1].isNotEmpty()) parts[0] to parts[1] else null
    }

    /** 获取设备信息（ISAPI deviceInfo），JSON 格式化后写入 detail.infoText */
    fun loadInfo(device: HikDevice) {
        val cred = credOf(device.ip)
        if (cred == null) {
            _detail.value = HikDetail(device, lastResult = "请先保存账密")
            return
        }
        if (!startBusy(device)) return
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    HikvisionApi.deviceInfo(
                        host = device.ip,
                        port = device.httpPort.toLong(),
                        username = cred.first,
                        password = cred.second
                    )
                }
                if (!r.ok) {
                    // gomobile 层认证失败时 ok=false（HTTP 401 同样归为认证失败）
                    finishBusy(device, HikDetail(device, infoText = "用户名或密码错误"))
                } else {
                    finishBusy(device, HikDetail(device, infoText = formatDeviceInfo(r.result)))
                }
            } catch (e: Exception) {
                finishBusy(device, HikDetail(device, lastResult = "获取设备信息失败：${e.message}"))
            }
        }
    }

    /** 修改设备 IP / 切换 DHCP（ISAPI PUT /ISAPI/Network/interfaces/1/ipv4） */
    fun changeIp(device: HikDevice, newIp: String, mask: String, gateway: String, dhcp: Boolean) {
        if (!dhcp && (newIp.isBlank() || mask.isBlank() || gateway.isBlank())) {
            _detail.value = HikDetail(device, lastResult = "请填写完整的 IP/掩码/网关")
            return
        }
        val body = if (dhcp) DHCP_BODY else buildIpXml(newIp, mask, gateway)
        runIsapi(
            device, Method.PUT, "/ISAPI/Network/interfaces/1/ipv4", body,
            successText = { "改 IP 成功（HTTP $it）" },
            failText = { "改 IP 失败（HTTP $it）" }
        )
    }

    /** 修改设备密码（ISAPI PUT /ISAPI/Security/users/1） */
    fun changePassword(device: HikDevice, newPassword: String) {
        if (newPassword.isBlank()) {
            _detail.value = HikDetail(device, lastResult = "请输入新密码")
            return
        }
        runIsapi(
            device, Method.PUT, "/ISAPI/Security/users/1", buildPasswordXml(newPassword),
            successText = { "改密码成功（HTTP $it）" },
            failText = { "改密码失败（HTTP $it）" }
        )
    }

    /** 重启设备（ISAPI PUT /ISAPI/System/reboot，成功会断连） */
    fun reboot(device: HikDevice) {
        runIsapi(
            device, Method.PUT, "/ISAPI/System/reboot", "",
            successText = { "已发送重启指令" },
            failText = { "重启指令发送失败（HTTP $it）" }
        )
    }

    /** 恢复出厂设置（ISAPI PUT /ISAPI/System/factory） */
    fun factoryReset(device: HikDevice) {
        runIsapi(
            device, Method.PUT, "/ISAPI/System/factory", "",
            successText = { "恢复出厂成功（HTTP $it）" },
            failText = { "恢复出厂失败（HTTP $it）" }
        )
    }

    /** 生成旧固件（<5.3.0）密码重置码：serial 去型号前缀（仅保留数字）+ 日期 YYYYMMDD */
    fun resetCode(device: HikDevice, date: String) {
        val d = date.trim()
        if (!d.matches(Regex("\\d{8}"))) {
            _detail.value = HikDetail(device, lastResult = "日期格式应为 YYYYMMDD（8 位数字）")
            return
        }
        if (!startBusy(device)) return
        viewModelScope.launch {
            try {
                val code = withContext(Dispatchers.IO) {
                    // 序列号去掉型号前缀：仅保留数字部分
                    val digits = device.serial.filter { it.isDigit() }
                    HikvisionApi.resetCode(digits.ifEmpty { device.serial }, d)
                }
                finishBusy(device, HikDetail(device, lastResult = "重置码：$code"))
            } catch (e: Exception) {
                finishBusy(device, HikDetail(device, lastResult = "生成重置码失败：${e.message}"))
            }
        }
    }

    /** SADP 激活未激活设备 */
    fun sendSADPActivate(device: HikDevice, password: String) {
        if (password.isBlank()) {
            _detail.value = HikDetail(device, lastResult = "请输入激活密码")
            return
        }
        if (!startBusy(device)) return
        viewModelScope.launch {
            try {
                val opts = JSONObject()
                    .put("targetIp", device.ip)
                    .put("password", password)
                    .put("timeoutSeconds", 6L)
                val r = withContext(Dispatchers.IO) { HikvisionApi.command("activate", opts) }
                val msg = if (r.ok) "激活成功" else "激活失败：${r.error ?: "未知错误"}"
                finishBusy(device, HikDetail(device, lastResult = msg))
            } catch (e: Exception) {
                finishBusy(device, HikDetail(device, lastResult = "激活失败：${e.message}"))
            }
        }
    }

    /**
     * SADP 重置密码（免旧密码）：输入安全码 + 新密码。
     * 安全码获取：旧固件（<5.3.0）用"序列号+设备日期"生成（见 resetCode）；
     * 新固件需设备/NVR 屏幕上显示的验证码。
     */
    fun sadpResetPassword(device: HikDevice, code: String, newPassword: String) {
        if (code.isBlank() || newPassword.isBlank()) {
            _detail.value = HikDetail(device, lastResult = "请输入安全码和新密码")
            return
        }
        if (device.mac.isBlank()) {
            _detail.value = HikDetail(device, lastResult = "未获取到设备 MAC，无法重置（可先重新发现）")
            return
        }
        if (!startBusy(device)) return
        viewModelScope.launch {
            try {
                val opts = JSONObject()
                    .put("targetIp", device.ip)
                    .put("targetMac", device.mac)
                    .put("code", code)
                    .put("password", newPassword)
                    .put("timeoutSeconds", 6L)
                val r = withContext(Dispatchers.IO) { HikvisionApi.command("resetpassword", opts) }
                val msg = if (r.ok) "重置密码成功" else "重置失败：${r.error ?: "未知错误"}"
                finishBusy(device, HikDetail(device, lastResult = msg))
            } catch (e: Exception) {
                finishBusy(device, HikDetail(device, lastResult = "重置失败：${e.message}"))
            }
        }
    }

    // ===================== ONVIF 模式 =====================

    /** 当前协议模式（默认 ONVIF 自动发现） */
    private val _mode = MutableStateFlow(CamMode.ONVIF)
    val mode: StateFlow<CamMode> = _mode.asStateFlow()

    /** ONVIF 发现的设备列表（含自动识别的型号） */
    private val _onvifDevices = MutableStateFlow<List<OnvifDeviceItem>>(emptyList())
    val onvifDevices: StateFlow<List<OnvifDeviceItem>> = _onvifDevices.asStateFlow()

    /** 是否正在 ONVIF 发现中 */
    private val _onvifDiscovering = MutableStateFlow(false)
    val onvifDiscovering: StateFlow<Boolean> = _onvifDiscovering.asStateFlow()

    /** ONVIF 发现错误信息（null 表示成功） */
    private val _onvifError = MutableStateFlow<String?>(null)
    val onvifError: StateFlow<String?> = _onvifError.asStateFlow()

    /** ONVIF 设备 IP → "用户名|密码"（与 SADP 的 login map 分开） */
    private val _onvifLogin = MutableStateFlow<Map<String, String>>(emptyMap())
    val onvifLogin: StateFlow<Map<String, String>> = _onvifLogin.asStateFlow()

    /** 当前选中 ONVIF 设备的详情操作区状态 */
    private val _onvifDetail = MutableStateFlow<OnvifDetail?>(null)
    val onvifDetail: StateFlow<OnvifDetail?> = _onvifDetail.asStateFlow()

    /** 当前正在操作的 ONVIF 设备 IP（null 表示空闲），用于防重复点击 */
    private val _onvifBusy = MutableStateFlow<String?>(null)
    val onvifBusy: StateFlow<String?> = _onvifBusy.asStateFlow()

    /** 切换发现协议 */
    fun switchMode(mode: CamMode) {
        _mode.value = mode
    }

    /** ONVIF 组播发现 + 自动对每台设备匿名识别型号（并发） */
    /**
     * 手动直连跨网段摄像头：输入 IP（可选端口）直接探测 ONVIF，加入设备列表可管理。
     * 适用于摄像头不在当前 Wi-Fi 网段（同局域网另一网段经路由器互连）的场景。
     *
     * @return 结果文案（成功/失败，供 UI 提示）
     */
    fun addManualOnvif(ip: String, port: Int = 80): String {
        if (!IpUtil.isValidIp(ip)) return "IP 格式不正确（需为点分十进制，如 192.168.1.100）"
        if (_onvifDevices.value.any { it.device.ip == ip }) return "该 IP 已在设备列表中"
        viewModelScope.launch {
            _onvifError.value = null
            val device = OnvifDevice(
                ip = ip,
                xaddr = "http://$ip:$port/onvif/device_service",
                types = "手动添加"
            )
            // 先加入列表（型号"识别中…"），再识别
            _onvifDevices.value = _onvifDevices.value + OnvifDeviceItem(device = device)
            val info = withContext(Dispatchers.IO) {
                runCatching { OnvifClient.deviceInfo(ip, port) }.getOrNull()
            }
            _onvifDevices.value = _onvifDevices.value.map {
                if (it.device.ip == ip) {
                    OnvifDeviceItem(
                        device,
                        model = info?.model?.ifBlank { "未知型号" } ?: "识别失败（确认 IP/端口可达）",
                        info = info
                    )
                } else it
            }
        }
        return "已添加 $ip（识别型号中…）"
    }

    fun discoverOnvif() {
        if (_onvifDiscovering.value) return
        viewModelScope.launch {
            _onvifDiscovering.value = true
            _onvifError.value = null
            _onvifDetail.value = null
            try {
                val found = withContext(Dispatchers.IO) { OnvifDiscovery.discover(getApplication()) }
                val items = found.devices.map { OnvifDeviceItem(device = it) }
                // 先展示设备列表（型号显示"识别中…"），随后自动识别
                _onvifDevices.value = items
                _onvifError.value = found.error

                if (items.isNotEmpty()) {
                    // 并发匿名 GetDeviceInformation 识别型号（每台失败不影响其他设备）
                    val identified = withContext(Dispatchers.IO) {
                        coroutineScope {
                            items.map { item ->
                                async { item.device.ip to runCatching { OnvifClient.deviceInfo(item.device.ip) }.getOrNull() }
                            }.map { it.await() }
                        }
                    }
                    _onvifDevices.value = items.map { item ->
                        val info = identified.firstOrNull { it.first == item.device.ip }?.second
                        if (info != null) {
                            OnvifDeviceItem(item.device, model = info.model.ifBlank { "未知型号" }, info = info)
                        } else {
                            OnvifDeviceItem(item.device, model = "识别失败")
                        }
                    }
                }
            } catch (e: Exception) {
                _onvifError.value = e.message ?: "ONVIF 发现失败"
            } finally {
                _onvifDiscovering.value = false
            }
        }
    }

    /** 保存 ONVIF 设备账密（key 为设备 IP） */
    fun onvifLogin(ip: String, user: String, pass: String) {
        _onvifLogin.value = _onvifLogin.value + (ip to "$user|$pass")
    }

    /** 读取 ONVIF 设备已保存的账密（返回 用户名、密码） */
    private fun onvifCredOf(ip: String): Pair<String, String>? {
        val raw = _onvifLogin.value[ip] ?: return null
        val parts = raw.split("|", limit = 2)
        return if (parts.size == 2 && parts[1].isNotEmpty()) parts[0] to parts[1] else null
    }

    /** 获取 ONVIF 设备信息（匿名可读），同步列表识别结果并写入详情 */
    fun onvifLoadInfo(device: OnvifDevice) {
        if (!startOnvifBusy(device)) return
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { OnvifClient.deviceInfo(device.ip) }
                if (info == null) {
                    finishOnvifBusy(device, OnvifDetail(device, lastResult = "获取设备信息失败（设备可能不支持匿名读取）"))
                } else {
                    // 同步列表里的型号/信息
                    _onvifDevices.value = _onvifDevices.value.map {
                        if (it.device.ip == device.ip) OnvifDeviceItem(it.device, info.model.ifBlank { "未知型号" }, info) else it
                    }
                    finishOnvifBusy(device, OnvifDetail(device, infoText = formatOnvifInfo(info)))
                }
            } catch (e: Exception) {
                finishOnvifBusy(device, OnvifDetail(device, lastResult = "获取设备信息失败：${e.message}"))
            }
        }
    }

    /**
     * ONVIF 弱口令探测（非爆破）：逐个试常见口令（≤20 组，组间隔 500ms），
     * 成功即显示账密并提示改密；全部未中则如实告知。
     */
    fun onvifProbePassword(device: OnvifDevice) {
        if (!startOnvifBusy(device)) return
        viewModelScope.launch {
            // ONVIF 端口从 xaddr 提取（http://ip:port/onvif/device_service），无端口默认 80
            val port = Regex("http://[^:]+:(\\d+)").find(device.xaddr)?.groupValues?.get(1)?.toIntOrNull() ?: 80
            val result = withContext(Dispatchers.IO) {
                PasswordProbe.probeOnvif(device.ip, port)
            }
            val msg = if (result != null) {
                if (result.password.isEmpty()) {
                    "⚠ 弱口令探测成功：用户名「${result.username}」，密码为空（设备未设密码）——建议立即设置密码！"
                } else {
                    "⚠ 弱口令探测成功：用户名「${result.username}」，密码「${result.password}」——建议立即修改！"
                }
            } else {
                "弱口令探测完成：常见口令均未匹配（共试 ${PasswordProbe.credentialCount} 组，非爆破）"
            }
            finishOnvifBusy(device, OnvifDetail(device, lastResult = msg))
        }
    }

    /** 修改 ONVIF 设备 IP：先 getInterfaces 取接口 token，再 SetNetworkInterfaces */
    fun onvifChangeIp(device: OnvifDevice, newIp: String, mask: String, gateway: String, dhcp: Boolean) {
        if (!dhcp && (newIp.isBlank() || mask.isBlank() || gateway.isBlank())) {
            _onvifDetail.value = OnvifDetail(device, lastResult = "请填写完整的 IP/掩码/网关")
            return
        }
        val cred = onvifCredOf(device.ip)
        if (cred == null) {
            _onvifDetail.value = OnvifDetail(device, lastResult = "请先保存账密")
            return
        }
        if (!startOnvifBusy(device)) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val ifaces = OnvifClient.getInterfaces(device.ip, user = cred.first, password = cred.second)
                    val token = ifaces.firstOrNull { it.token.isNotBlank() }?.token
                    if (token == null) {
                        OnvifClient.OnvifResult(false, "未获取到网络接口 token", null)
                    } else {
                        OnvifClient.setNetworkInterfaces(
                            host = device.ip,
                            user = cred.first,
                            password = cred.second,
                            token = token,
                            ip = newIp,
                            mask = mask,
                            gateway = gateway,
                            dhcp = dhcp
                        )
                    }
                }
                val msg = if (result.ok) "改 IP 成功（设备 IP 已变更）" else "改 IP 失败：${result.error ?: "未知错误"}"
                finishOnvifBusy(device, OnvifDetail(device, lastResult = msg))
            } catch (e: Exception) {
                finishOnvifBusy(device, OnvifDetail(device, lastResult = "改 IP 失败：${e.message}"))
            }
        }
    }

    /** 修改 ONVIF 设备密码（SetUser） */
    fun onvifChangePassword(device: OnvifDevice, newPassword: String) {
        if (newPassword.isBlank()) {
            _onvifDetail.value = OnvifDetail(device, lastResult = "请输入新密码")
            return
        }
        val cred = onvifCredOf(device.ip)
        if (cred == null) {
            _onvifDetail.value = OnvifDetail(device, lastResult = "请先保存账密")
            return
        }
        if (!startOnvifBusy(device)) return
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    OnvifClient.setUserPassword(device.ip, user = cred.first, password = cred.second, newPassword = newPassword)
                }
                val msg = if (r.ok) "改密码成功" else "改密码失败：${r.error ?: "未知错误"}"
                finishOnvifBusy(device, OnvifDetail(device, lastResult = msg))
            } catch (e: Exception) {
                finishOnvifBusy(device, OnvifDetail(device, lastResult = "改密码失败：${e.message}"))
            }
        }
    }

    /** 重启 ONVIF 设备 */
    fun onvifReboot(device: OnvifDevice) {
        runOnvifOp(device, "重启") { cred ->
            OnvifClient.reboot(device.ip, user = cred.first, password = cred.second)
        }
    }

    /** 恢复出厂设置（清空所有配置，UI 已做二次确认） */
    fun onvifFactoryReset(device: OnvifDevice) {
        runOnvifOp(device, "恢复出厂") { cred ->
            OnvifClient.factoryReset(device.ip, user = cred.first, password = cred.second)
        }
    }

    /** 通用 ONVIF 认证操作执行：IO 线程 + busy 管理 + 结果文案 */
    private fun runOnvifOp(
        device: OnvifDevice,
        opName: String,
        op: suspend (Pair<String, String>) -> OnvifClient.OnvifResult
    ) {
        val cred = onvifCredOf(device.ip)
        if (cred == null) {
            _onvifDetail.value = OnvifDetail(device, lastResult = "请先保存账密")
            return
        }
        if (!startOnvifBusy(device)) return
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { op(cred) }
                val msg = if (r.ok) "$opName 指令已发送" else "$opName 失败：${r.error ?: "未知错误"}"
                finishOnvifBusy(device, OnvifDetail(device, lastResult = msg))
            } catch (e: Exception) {
                finishOnvifBusy(device, OnvifDetail(device, lastResult = "$opName 失败：${e.message}"))
            }
        }
    }

    /** 标记 ONVIF 设备开始操作；已有设备在忙则返回 false（防重复点击） */
    private fun startOnvifBusy(device: OnvifDevice): Boolean {
        if (_onvifBusy.value != null) return false
        _onvifBusy.value = device.ip
        val cur = _onvifDetail.value
        // 保留旧的 infoText，清空上一次操作结果
        _onvifDetail.value = (cur ?: OnvifDetail(device)).copy(busy = true, lastResult = null)
        return true
    }

    /** ONVIF 操作结束：清忙标记并写入结果 */
    private fun finishOnvifBusy(device: OnvifDevice, result: OnvifDetail) {
        if (_onvifBusy.value == device.ip) _onvifBusy.value = null
        _onvifDetail.value = result.copy(busy = false)
    }

    /** 把 ONVIF 设备信息格式化为中文键值文本 */
    private fun formatOnvifInfo(info: OnvifDeviceInfo): String = buildString {
        listOf(
            "厂商" to info.manufacturer,
            "型号" to info.model,
            "固件" to info.firmware,
            "序列号" to info.serial
        ).forEach { (label, value) ->
            append(label).append("：").append(value.ifBlank { "未知" }).append('\n')
        }
    }.trimEnd()

    /** 标记设备开始操作；已有设备在忙则返回 false（防重复点击） */
    private fun startBusy(device: HikDevice): Boolean {
        if (_busyDevice.value != null) return false
        _busyDevice.value = device.ip
        val cur = _detail.value
        // 保留旧的 infoText，清空上一次操作结果
        _detail.value = (cur ?: HikDetail(device)).copy(busy = true, lastResult = null)
        return true
    }

    /** 操作结束：清忙标记并写入结果 */
    private fun finishBusy(device: HikDevice, result: HikDetail) {
        if (_busyDevice.value == device.ip) _busyDevice.value = null
        _detail.value = result.copy(busy = false)
    }

    /** 通用 ISAPI 请求执行：IO 线程 + busy 管理 + 结果文案 */
    private fun runIsapi(
        device: HikDevice,
        method: Method,
        path: String,
        body: String,
        successText: (Int) -> String,
        failText: (Int) -> String
    ) {
        val cred = credOf(device.ip)
        if (cred == null) {
            _detail.value = HikDetail(device, lastResult = "请先保存账密")
            return
        }
        if (!startBusy(device)) return
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    HikvisionApi.isapi(
                        method = method,
                        host = device.ip,
                        port = device.httpPort.toLong(),
                        path = path,
                        body = body,
                        username = cred.first,
                        password = cred.second
                    )
                }
                val status = HikvisionApi.statusOf(r)
                val msg = when {
                    r.ok && status in 200..299 -> successText(status)
                    r.ok -> failText(status)
                    status > 0 -> failText(status)
                    else -> r.error ?: "操作失败"
                }
                finishBusy(device, HikDetail(device, lastResult = msg))
            } catch (e: Exception) {
                finishBusy(device, HikDetail(device, lastResult = "操作失败：${e.message}"))
            }
        }
    }

    /** 把 deviceInfo 返回的 JSON 格式化为中文键值文本 */
    private fun formatDeviceInfo(json: String?): String {
        if (json.isNullOrBlank()) return "无设备信息"
        return try {
            val o = JSONObject(json)
            buildString {
                listOf(
                    "设备名称" to "deviceName",
                    "型号" to "model",
                    "序列号" to "serialNumber",
                    "固件版本" to "firmwareVersion",
                    "MAC 地址" to "macAddress"
                ).forEach { (label, key) ->
                    append(label).append("：").append(o.optString(key).ifBlank { "未知" }).append('\n')
                }
            }.trimEnd()
        } catch (e: Exception) {
            "信息解析失败：${e.message}"
        }
    }

    /** 构建静态 IP 修改 XML */
    private fun buildIpXml(ip: String, mask: String, gateway: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <NetworkInterface xmlns="http://www.hikvision.com/ver20/XMLSchema">
          <id>1</id>
          <ipAddress>
            <ipVersion>v4</ipVersion>
            <addressingType>static</addressingType>
            <ipAddress>$ip</ipAddress>
            <subnetMask>$mask</subnetMask>
            <DefaultGateway><ipAddress>$gateway</ipAddress></DefaultGateway>
          </ipAddress>
        </NetworkInterface>
    """.trimIndent()

    /** DHCP 模式 XML（省略 IP 子节点，body 简化） */
    private val DHCP_BODY: String = """<?xml version="1.0" encoding="UTF-8"?>
<NetworkInterface xmlns="http://www.hikvision.com/ver20/XMLSchema"><id>1</id><ipAddress><ipVersion>v4</ipVersion><addressingType>dhcp</addressingType></ipAddress></NetworkInterface>"""

    /** 构建修改密码 XML */
    private fun buildPasswordXml(newPassword: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <User xmlns="http://www.hikvision.com/ver20/XMLSchema">
          <id>1</id>
          <userName>admin</userName>
          <password>$newPassword</password>
        </User>
    """.trimIndent()
}
