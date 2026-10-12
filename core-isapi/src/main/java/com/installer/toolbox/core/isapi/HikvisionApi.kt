package com.installer.toolbox.core.isapi

import android.content.Context
import mobile.Mobile
import org.json.JSONObject

/**
 * 海康威视设备（SADP 发现结果）。
 * 字段与 hikvision-tooling 的 sadp.Device JSON 对应。
 */
data class HikDevice(
    /** 设备序列号 */
    val serial: String,
    /** 设备类型/型号，如 DS-2CD3T47EDWD */
    val deviceType: String,
    val mac: String,
    val ip: String,
    val mask: String,
    val gateway: String,
    /** 固件版本 */
    val firmware: String,
    /** 是否已激活（"true"/"false"） */
    val activated: String,
    val httpPort: Int,
    /** DHCP 开关（"true"/"false"） */
    val dhcp: String
)

/**
 * 海康设备操作 API 封装。
 *
 * 底层是 gomobile 编译的 Go 库（mobile.Mobile），所有方法返回
 * {"ok":true,"result":"..."} 或 {"ok":false,"error":"..."} 的 JSON。
 * 注意：Go 的 int 参数在 Java 侧是 long。
 */
object HikvisionApi {

    /** ISAPI 请求方法 */
    enum class Method { GET, PUT, POST }

    /** 统一响应 */
    data class HikResult(val ok: Boolean, val error: String?, val result: String?) {
        val isSuccess: Boolean get() = ok
    }

    /** 解析 Go 层返回的 JSON 包装 */
    private fun parse(raw: String): HikResult {
        return try {
            val obj = JSONObject(raw)
            HikResult(
                ok = obj.optBoolean("ok"),
                error = obj.optString("error").ifBlank { null },
                result = obj.optString("result").ifBlank { null }
            )
        } catch (e: Exception) {
            HikResult(false, "响应解析失败：${e.message}", null)
        }
    }

    /**
     * SADP 组播发现局域网内海康设备（Kotlin 实现，规避 Android netlink 限制）。
     * 需要定位权限。
     * @return 设备列表 + 错误信息（null 表示成功）
     */
    fun discover(context: Context, timeoutSec: Long = 6): Pair<List<HikDevice>, String?> {
        val result = SadpDiscoverer.discover(context, timeoutMs = timeoutSec * 1000)
        return result.devices to result.error
    }

    /**
     * 通用 ISAPI 请求（Digest 认证由 Go 层处理）。
     * @return result 为 {"status":HTTP状态码,"body":响应体XML}
     */
    fun isapi(
        method: Method,
        host: String,
        port: Long = 80,
        path: String,
        body: String = "",
        username: String,
        password: String,
        insecure: Boolean = true,
        timeoutSec: Long = 8
    ): HikResult {
        val raw = when (method) {
            Method.GET -> Mobile.isapiGet(host, port, path, username, password, insecure, timeoutSec)
            Method.PUT -> Mobile.isapiPut(host, port, path, body, username, password, insecure, timeoutSec)
            Method.POST -> Mobile.isapiPost(host, port, path, body, username, password, insecure, timeoutSec)
        }
        return parse(raw)
    }

    /** 从 isapi() 的 result 中取 HTTP 状态码 */
    fun statusOf(r: HikResult): Int {
        return try {
            JSONObject(r.result ?: "{}").optInt("status", -1)
        } catch (e: Exception) {
            -1
        }
    }

    /** 从 isapi() 的 result 中取响应体（XML） */
    fun bodyOf(r: HikResult): String {
        return try {
            JSONObject(r.result ?: "{}").optString("body", "")
        } catch (e: Exception) {
            ""
        }
    }

    /** 获取设备信息（/ISAPI/System/deviceInfo），result 为解析后的 JSON 对象字符串 */
    fun deviceInfo(
        host: String,
        port: Long = 80,
        username: String,
        password: String,
        insecure: Boolean = true,
        timeoutSec: Long = 8
    ): HikResult {
        return parse(Mobile.isapiDeviceInfo(host, port, username, password, insecure, timeoutSec))
    }

    /**
     * 发送 SADP 命令（激活/改 IP/改密码/重启/恢复出厂/查询等）。
     * 命令名：activate / update / reboot / restore / resetpassword /
     * securitycode / exchangecode / inquiry。
     * @param optsJson 参数 JSON，键见下：
     *   targetIp, targetMac, password, code, newIp, newMask, newGateway,
     *   newPort(long), dhcp(bool), timeoutSeconds(long), unicast(bool)
     */
    fun command(cmdName: String, optsJson: JSONObject = JSONObject()): HikResult {
        return parse(Mobile.sadpCommand(cmdName, optsJson.toString()))
    }

    /** 生成旧固件（<5.3.0）密码重置码；serial 去掉型号前缀，date 格式 YYYYMMDD */
    fun resetCode(serial: String, date: String): String {
        return Mobile.generateResetCode(serial, date)
    }
}
