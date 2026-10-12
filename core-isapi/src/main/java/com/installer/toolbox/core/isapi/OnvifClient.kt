package com.installer.toolbox.core.isapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * ONVIF Device Service 客户端（SOAP + WS-Security UsernameToken 认证）。
 *
 * 协议已在真实 seetong 摄像头验证：
 * - GetDeviceInformation 匿名可读；
 * - 配置类操作需 WS-Security PasswordDigest 认证（默认账密 admin/admin）。
 */
object OnvifClient {

    /** 设备信息（GetDeviceInformation 结果） */
    data class OnvifDeviceInfo(
        val manufacturer: String,
        val model: String,
        val firmware: String,
        val serial: String
    )

    /** 网络接口信息（GetNetworkInterfaces 结果，改 IP 需要 token） */
    data class OnvifInterface(
        val token: String,
        val ip: String,
        val prefix: Int,
        val gateway: String,
        val dhcp: Boolean
    )

    /** 统一调用结果 */
    data class OnvifResult(val ok: Boolean, val error: String?, val raw: String?) {
        val isSuccess: Boolean get() = ok
    }

    private const val DEVICE_NS = "http://www.onvif.org/ver10/device/wsdl"
    private const val SOAP_XML = "application/soap+xml; charset=utf-8"

    private val client by lazy {
        OkHttpClient.Builder()
            // 局域网直连：必须绕过系统代理（手机常配 127.0.0.1:8080 代理，会拦截摄像头访问）
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 获取设备信息（匿名可读） */
    suspend fun deviceInfo(host: String, port: Int = 80): OnvifDeviceInfo? = withContext(Dispatchers.IO) {
        val body = "<tds:GetDeviceInformation xmlns:tds=\"$DEVICE_NS\"/>"
        val resp = call(host, port, "$DEVICE_NS/GetDeviceInformation", body, null, null)
        if (!resp.ok) return@withContext null
        try {
            OnvifDeviceInfo(
                manufacturer = fieldValue(resp.raw.orEmpty(), "Manufacturer"),
                model = fieldValue(resp.raw.orEmpty(), "Model"),
                firmware = fieldValue(resp.raw.orEmpty(), "FirmwareVersion").trim(),
                serial = fieldValue(resp.raw.orEmpty(), "SerialNumber")
            )
        } catch (e: Exception) {
            android.util.Log.e("OnvifClient", "deviceInfo 失败: ${e.message}", e)
            null
        }
    }

    /** 获取网络接口（返回第一个 IPv4 接口；获取失败返回 null） */
    suspend fun getInterfaces(host: String, port: Int = 80, user: String, password: String): List<OnvifInterface> =
        withContext(Dispatchers.IO) {
            val body = "<tds:GetNetworkInterfaces xmlns:tds=\"$DEVICE_NS\"/>"
            val resp = call(host, port, "$DEVICE_NS/GetNetworkInterfaces", body, user, password)
            if (!resp.ok) return@withContext emptyList()
            parseInterfaces(resp.raw ?: "")
        }

    /**
     * 修改 IP（SetNetworkInterfaces）。
     * @param token 接口 token（getInterfaces 获取）
     * @param ip 新 IP；mask 形如 255.255.255.0；gateway 网关
     * @param dhcp true 时改用 DHCP
     */
    suspend fun setNetworkInterfaces(
        host: String, port: Int = 80, user: String, password: String,
        token: String, ip: String, mask: String, gateway: String, dhcp: Boolean
    ): OnvifResult = withContext(Dispatchers.IO) {
        val prefix = maskToPrefix(mask)
        val manual = if (dhcp) "" else
            "<tt:Manual><tt:Address><tt:Type>IPv4</tt:Type>" +
            "<tt:IPv4Address>$ip</tt:IPv4Address>" +
            "<tt:PrefixLength>$prefix</tt:PrefixLength></tt:Address></tt:Manual>"
        val body = "<tds:SetNetworkInterfaces xmlns:tds=\"$DEVICE_NS\">" +
            "<tds:InterfaceToken>$token</tds:InterfaceToken>" +
            "<tds:NetworkInterface><tt:Enabled>true</tt:Enabled>" +
            "<tt:IPv4 xmlns:tt=\"http://www.onvif.org/ver10/schema\">$manual<tt:DHCP>$dhcp</tt:DHCP></tt:IPv4>" +
            "</tds:NetworkInterface></tds:SetNetworkInterfaces>"
        call(host, port, "$DEVICE_NS/SetNetworkInterfaces", body, user, password)
    }

    /** 修改用户密码（SetUser，改 admin 密码） */
    suspend fun setUserPassword(
        host: String, port: Int = 80, user: String, password: String, newPassword: String
    ): OnvifResult = withContext(Dispatchers.IO) {
        val body = "<tds:SetUser xmlns:tds=\"$DEVICE_NS\">" +
            "<tds:User><tt:Username xmlns:tt=\"http://www.onvif.org/ver10/schema\">$user</tt:Username>" +
            "<tt:Password xmlns:tt=\"http://www.onvif.org/ver10/schema\">$newPassword</tt:Password>" +
            "<tt:UserLevel xmlns:tt=\"http://www.onvif.org/ver10/schema\">Administrator</tt:UserLevel>" +
            "</tds:User></tds:SetUser>"
        call(host, port, "$DEVICE_NS/SetUser", body, user, password)
    }

    /** 重启设备（SystemReboot） */
    suspend fun reboot(host: String, port: Int = 80, user: String, password: String): OnvifResult =
        withContext(Dispatchers.IO) {
            val body = "<tds:SystemReboot xmlns:tds=\"$DEVICE_NS\"/>"
            call(host, port, "$DEVICE_NS/SystemReboot", body, user, password)
        }

    /** 恢复出厂（SetSystemFactoryDefault，Hard = 全部默认，会清空配置） */
    suspend fun factoryReset(host: String, port: Int = 80, user: String, password: String): OnvifResult =
        withContext(Dispatchers.IO) {
            val body = "<tds:SetSystemFactoryDefault xmlns:tds=\"$DEVICE_NS\">" +
                "<tds:FactoryDefaultType>Hard</tds:FactoryDefaultType></tds:SetSystemFactoryDefault>"
            call(host, port, "$DEVICE_NS/SetSystemFactoryDefault", body, user, password)
        }

    /** 通用 SOAP 调用；credentials 为 null 时不带认证头 */
    private fun call(
        host: String, port: Int, action: String, soapBody: String,
        user: String?, password: String?
    ): OnvifResult {
        return try {
            val soap = buildSoap(action, soapBody, user, password)
            val request = Request.Builder()
                .url("http://$host:$port/onvif/device_service")
                .header("Content-Type", SOAP_XML)
                .post(soap.toRequestBody(SOAP_XML.toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    OnvifResult(false, "HTTP ${resp.code}：${resp.message}", raw)
                } else if (raw.contains("<.*Fault.*>".toRegex()) || raw.contains("Fault")) {
                    // SOAP Fault（多为认证失败或操作不被支持）
                    OnvifResult(false, extractFault(raw) ?: "ONVIF 返回错误", raw)
                } else {
                    OnvifResult(true, null, raw)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OnvifClient", "call 失败: ${e.message}", e)
            OnvifResult(false, e.message ?: e.javaClass.simpleName, null)
        }
    }

    /** 构造 SOAP Envelope（含 WS-Security UsernameToken PasswordDigest） */
    private fun buildSoap(action: String, body: String, user: String?, password: String?): String {
        val security = if (user != null && password != null) {
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(nonce + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8))
            val digestB64 = Base64.getEncoder().encodeToString(digest)
            val nonceB64 = Base64.getEncoder().encodeToString(nonce)
            "<Security xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">" +
            "<UsernameToken><Username>$user</Username>" +
            "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">$digestB64</Password>" +
            "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">$nonceB64</Nonce>" +
            "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">$created</Created>" +
            "</UsernameToken></Security>"
        } else ""
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://www.w3.org/2005/08/addressing\">" +
            "<s:Header><a:Action s:mustUnderstand=\"1\">$action</a:Action>$security</s:Header>" +
            "<s:Body>$body</s:Body></s:Envelope>"
    }

    /** 解析 GetNetworkInterfaces 响应中的接口（正则按块提取，简单可靠） */
    private fun parseInterfaces(xml: String): List<OnvifInterface> {
        val blockRegex = Regex(
            "<(?:[\\w-]+:)?NetworkInterfaces\\s+token=\"([^\"]+)\"[^>]*>(.*?)</(?:[\\w-]+:)?NetworkInterfaces>",
            RegexOption.DOT_MATCHES_ALL
        )
        val result = mutableListOf<OnvifInterface>()
        for (m in blockRegex.findAll(xml)) {
            val token = m.groupValues[1]
            val block = m.groupValues[2]
            // Manual 地址块中的 IPv4（DefaultGateway 下也有 IPv4Address，需排除）
            val manualBlock = Regex("<(?:[\\w-]+:)?Manual[^>]*>(.*?)</(?:[\\w-]+:)?Manual>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1) ?: block
            val ip = Regex("<(?:[\\w-]+:)?IPv4Address[^>]*>(.*?)</(?:[\\w-]+:)?IPv4Address>")
                .find(manualBlock)?.groupValues?.get(1) ?: ""
            val prefix = Regex("<(?:[\\w-]+:)?PrefixLength[^>]*>(.*?)</(?:[\\w-]+:)?PrefixLength>")
                .find(manualBlock)?.groupValues?.get(1)?.toIntOrNull() ?: 24
            val dhcp = Regex("<(?:[\\w-]+:)?DHCP[^>]*>\\s*true\\s*<").containsMatchIn(block)
            // 网关：DefaultGateway 块内的 IPv4Address
            val gwBlock = Regex("<(?:[\\w-]+:)?DefaultGateway[^>]*>(.*?)</(?:[\\w-]+:)?DefaultGateway>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
            val gateway = gwBlock?.let {
                Regex("<(?:[\\w-]+:)?IPv4Address[^>]*>(.*?)</(?:[\\w-]+:)?IPv4Address>").find(it)?.groupValues?.get(1)
            }.orEmpty()
            if (token.isNotBlank()) {
                result.add(OnvifInterface(token, ip, prefix, gateway, dhcp))
            }
        }
        return result
    }

    /** 提取指定 XML 标签的文本内容（忽略命名空间前缀） */
    private fun fieldValue(xml: String, tag: String): String {
        return Regex("<(?:[\\w-]+:)?$tag[^>]*>(.*?)</(?:[\\w-]+:)?$tag>")
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 掩码转前缀长度：255.255.255.0 → 24 */
    private fun maskToPrefix(mask: String): Int {
        val parts = mask.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return 24
        var bits = 0
        for (p in parts) {
            bits += Integer.bitCount(p)
        }
        return if (bits in 0..32) bits else 24
    }

    /** 提取 SOAP Fault 描述 */
    private fun extractFault(xml: String): String? {
        return Regex("<(?:[\\w-]+:)?Reason[^>]*>.*?<(?:[\\w-]+:)?Text[^>]*>(.*?)</(?:[\\w-]+:)?Text>.*?</(?:[\\w-]+:)?Reason>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
            ?: Regex("<(?:[\\w-]+:)?faultstring[^>]*>(.*?)</(?:[\\w-]+:)?faultstring>")
                .find(xml)?.groupValues?.get(1)?.trim()
    }
}
