package com.installer.toolbox.core.isapi

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.text.Regex

/**
 * ONVIF 设备（WS-Discovery 发现结果）。
 */
data class OnvifDevice(
    /** 设备 IP（从 XAddrs 提取） */
    val ip: String,
    /** 设备服务地址，如 http://192.168.10.20/onvif/device_service */
    val xaddr: String,
    /** 设备类型，如 dn:NetworkVideoTransmitter */
    val types: String
)

/**
 * ONVIF WS-Discovery 发现器（Kotlin 实现）。
 *
 * 协议：UDP 组播 239.255.255.250:3702 发送 Probe（WS-Discovery SOAP 消息），
 * 设备回 ProbeMatch（含 XAddrs = 设备服务地址）。
 * 需要 MulticastLock（Android 默认过滤组播）。
 */
object OnvifDiscovery {

    private const val MULTICAST_ADDR = "239.255.255.250"
    private const val PORT = 3702

    /** 发现结果：设备列表 + 错误信息（null 表示成功） */
    data class OnvifResult(val devices: List<OnvifDevice>, val error: String?)

    /** 组播 Probe 消息模板（UUID 占位） */
    private const val PROBE_TEMPLATE =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
        "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
        "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" " +
        "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
        "<e:Header>" +
        "<w:MessageID>uuid:__UUID__</w:MessageID>" +
        "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
        "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>" +
        "</e:Header>" +
        "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>" +
        "</e:Envelope>"

    /**
     * WS-Discovery 组播发现局域网内 ONVIF 设备（兼容海康/大华/seetong/宇视等）。
     * 需要定位权限（Wi-Fi 扫描）。
     */
    fun discover(context: Context, timeoutMs: Long = 6000): OnvifResult {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val localIp = wifiLocalIp(wifi) ?: return OnvifResult(emptyList(), "未获取到 Wi-Fi 局域网 IP")

        val lock = wifi.createMulticastLock("toolbox-onvif-lock").apply { setReferenceCounted(false) }
        lock.acquire()
        return try {
            val socket = MulticastSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(localIp, 0))
            val group = InetAddress.getByName(MULTICAST_ADDR)
            try {
                NetworkInterface.getByInetAddress(InetAddress.getByName(localIp))
                    ?.let { socket.setNetworkInterface(it) }
            } catch (_: Exception) {
            }
            socket.joinGroup(group)

            val probe = PROBE_TEMPLATE.replace("__UUID__", UUID.randomUUID().toString())
            val bytes = probe.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(group, PORT)))
            socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT)))

            socket.soTimeout = timeoutMs.toInt()
            val buf = ByteArray(65535)
            // 按 XAddrs 去重
            val deviceMap = LinkedHashMap<String, OnvifDevice>()
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val xml = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parseProbeMatch(xml)?.let { d ->
                    if (d.xaddr.isNotBlank()) deviceMap[d.xaddr] = d
                }
            }
            OnvifResult(deviceMap.values.toList(), null)
        } catch (e: Exception) {
            OnvifResult(emptyList(), "ONVIF 发现失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }
    }

    /** 解析 ProbeMatch 响应，提取 XAddrs 与 Types */
    private fun parseProbeMatch(xml: String): OnvifDevice? {
        if (!xml.contains("ProbeMatch")) return null
        val xaddr = Regex("<(?:[\\w-]+:)?XAddrs(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?XAddrs>")
            .find(xml)?.groupValues?.get(1)?.trim() ?: return null
        val types = Regex("<(?:[\\w-]+:)?Types(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?Types>")
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        val ip = Regex("http://([\\d.]+)[:/]").find(xaddr)?.groupValues?.get(1) ?: ""
        return OnvifDevice(ip = ip, xaddr = xaddr, types = types)
    }

    /** 从 WifiManager 取当前局域网 IPv4（小端 int 反转） */
    private fun wifiLocalIp(wifi: WifiManager): String? {
        return try {
            val info = wifi.connectionInfo ?: return null
            val ip = info.ipAddress
            if (ip == 0) return null
            val net = Integer.reverseBytes(ip)
            "${(net ushr 24) and 0xFF}.${(net ushr 16) and 0xFF}.${(net ushr 8) and 0xFF}.${net and 0xFF}"
        } catch (e: Exception) {
            null
        }
    }
}
