package com.installer.toolbox.core.isapi

import android.content.Context
import android.net.wifi.WifiManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * SADP（Search Active Devices Protocol）组播发现器，Kotlin 实现。
 *
 * 为什么不用 Go 库的 Discover：Android 应用沙箱禁止 netlink 路由查询
 * （Go 库枚举网络接口时抛 "route ip+net: netlinkrib: permission denied"），
 * 因此改用 UDP 组播 + MulticastLock 在 Kotlin 侧实现（协议来自
 * hikvision-tooling 的 internal/sadp，MIT）。
 */
object SadpDiscoverer {

    /** SADP 组播地址与端口 */
    private const val MULTICAST_ADDR = "239.255.255.250"
    private const val PORT = 37020

    /** 发现结果：设备列表 + 错误信息（null 表示成功） */
    data class SadpResult(val devices: List<HikDevice>, val error: String?)

    /**
     * SADP 组播发现局域网内海康设备。
     * 需要 Android 9+ 定位权限（Wi-Fi 扫描）与 MulticastLock。
     */
    fun discover(context: Context, timeoutMs: Long = 6000): SadpResult {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val localIp = wifiLocalIp(wifi) ?: return SadpResult(emptyList(), "未获取到 Wi-Fi 局域网 IP")

        // Android 默认过滤组播，必须持有 MulticastLock 才能收到组播回包
        val lock = wifi.createMulticastLock("toolbox-sadp-lock").apply { setReferenceCounted(false) }
        lock.acquire()
        return try {
            val socket = MulticastSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(localIp, 0))
            // 加入组播组（绑定到 Wi-Fi 接口）
            val group = InetAddress.getByName(MULTICAST_ADDR)
            try {
                NetworkInterface.getByInetAddress(InetAddress.getByName(localIp))
                    ?.let { socket.setNetworkInterface(it) }
            } catch (_: Exception) {
                // 接口设置失败不致命，使用默认接口
            }
            socket.joinGroup(group)

            val uuid = UUID.randomUUID().toString()
            val probes = listOf(
                """<?xml version="1.0" encoding="utf-8"?><Probe><Uuid>$uuid</Uuid><Types>inquiry</Types></Probe>""",
                """<?xml version="1.0" encoding="utf-8"?><Probe><Uuid>$uuid</Uuid><Types>inquiry_v32</Types></Probe>"""
            )
            val mcastTarget = InetSocketAddress(group, PORT)
            val bcastTarget = InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT)
            probes.forEach { p ->
                val bytes = p.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, mcastTarget))
                socket.send(DatagramPacket(bytes, bytes.size, bcastTarget))
            }

            socket.soTimeout = timeoutMs.toInt()
            val buf = ByteArray(65535)
            // 按 MAC 去重
            val deviceMap = LinkedHashMap<String, HikDevice>()
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val xml = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parseProbeMatch(xml)?.let { d ->
                    if (d.mac.isNotBlank()) deviceMap[d.mac] = d
                }
            }
            SadpResult(deviceMap.values.toList(), null)
        } catch (e: Exception) {
            SadpResult(emptyList(), "SADP 发现失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }
    }

    /** 解析 SADP ProbeMatch 响应 XML；非 ProbeMatch 返回 null */
    private fun parseProbeMatch(xml: String): HikDevice? {
        if (!xml.contains("ProbeMatch")) return null
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            val values = HashMap<String, String>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    values[parser.name] = parser.nextText()
                }
                event = parser.next()
            }
            HikDevice(
                serial = values["DeviceSN"].orEmpty(),
                deviceType = values["DeviceType"].orEmpty(),
                mac = values["MAC"].orEmpty().uppercase().replace("-", ":"),
                ip = values["IPv4Address"].orEmpty(),
                mask = values["IPv4SubnetMask"].orEmpty(),
                gateway = values["IPv4Gateway"].orEmpty(),
                firmware = values["SoftwareVersion"].orEmpty(),
                activated = values["Activated"].orEmpty(),
                httpPort = values["HttpPort"]?.toIntOrNull() ?: 80,
                dhcp = values["DHCP"].orEmpty()
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 从 WifiManager 取当前局域网 IPv4（小端 int 反转，同 DeviceScanner 逻辑） */
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
