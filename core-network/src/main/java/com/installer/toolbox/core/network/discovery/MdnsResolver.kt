package com.installer.toolbox.core.network.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * mDNS 设备名发现（最小实现，无第三方依赖）。
 *
 * 两条通道：
 * 1. **主动反查**：向 224.0.0.251:5353 发 "x.x.x.x.in-addr.arpa" 的 PTR 查询（QU 置位请求单播响应），
 *    avahi / Android mDNSResponder 等设备会用自身设备名单播回复；
 * 2. **被动公告监听**：绑定 5353 组播端口，解析设备**周期性公告**里的 A 记录
 *    （公告格式如 `A 192.168.100.117` + `PTR xiaomi-0ccdb4b2f679.local`）——
 *    多数嵌入式设备（小米盒子/智能家居）不响应反查但会主动公告，此通道兜底。
 *
 * 实测结论（真机验证）：Android 上 MulticastSocket(5353) 绑定**可能被系统 mDNS 占用而失败**，
 * 此时自动降级为随机端口主动反查（只能收到 QU 单播响应）。
 * 对"随机 MAC 手机"（厂商无法从 MAC 识别）尤其有用——设备名直接体现品牌型号。
 */
class MdnsResolver(private val context: Context? = null) {

    /** mDNS 组播地址（RFC 6762） */
    private val mdnsGroup: InetAddress = InetAddress.getByName("224.0.0.251")

    /**
     * 反查 + 公告监听合并的设备名发现。
     *
     * @param ips 待反查的 IP 列表（仅主动通道使用）
     * @param timeoutMs 总等待时间（毫秒）；被动公告间隔可达数十秒，适当放宽可提高命中
     * @return IP → 设备名（已去除 ".local" 后缀）；未发现的设备不出现
     */
    fun resolve(ips: List<String>, timeoutMs: Long = 3000): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (ips.isEmpty()) return result

        // 通道一：被动模式（绑 5353 收反查响应 + 公告）。绑定失败返回 null → 走主动
        val passive = context?.let { tryPassiveResolve(it, ips, timeoutMs) }
        if (passive != null) {
            result.putAll(passive)
        } else {
            // 通道二：随机端口主动反查（只能收 QU 单播响应）
            result.putAll(activeResolve(ips, timeoutMs))
        }
        return result
    }

    /**
     * 被动模式：绑定 5353 + 加组 + MulticastLock，发反查查询并监听公告。
     * 同时收集：in-addr.arpa PTR 反查响应、公告中的 A 记录（设备名 → IP）。
     *
     * @return 解析结果；绑定失败（系统占用 5353 等）返回 null 表示需降级
     */
    private fun tryPassiveResolve(
        context: Context,
        ips: List<String>,
        timeoutMs: Long
    ): Map<String, String>? {
        val lock = acquireMulticastLock(context) ?: return null
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(5353).apply {
                reuseAddress = true
                joinGroup(mdnsGroup)
            }
        } catch (e: Exception) {
            // 5353 被系统 mDNS 独占 → 降级主动模式
            return null
        }
        val result = mutableMapOf<String, String>()
        try {
            // 发反查查询（QU 置位；组播响应会到 5353，也能被本 socket 收到）
            ips.forEach { ip ->
                try {
                    socket!!.send(buildReverseQuery(ip))
                } catch (e: Exception) {
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                socket.soTimeout = minOf(remain, 500L).toInt()
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                collectNames(packet.data, packet.length, result)
            }
        } catch (e: Exception) {
            // 收包异常静默忽略，返回已收集结果
        } finally {
            try {
                socket?.leaveGroup(mdnsGroup)
            } catch (e: Exception) {
            }
            try {
                socket?.close()
            } catch (e: Exception) {
            }
            try {
                lock.release()
            } catch (e: Exception) {
            }
        }
        return result
    }

    /**
     * 主动模式：随机端口（绕开 5353 冲突），QU 置位请求单播响应。
     * 仅能收到尊重 QU 位的设备响应；绑定 5353 失败时兜底。
     */
    private fun activeResolve(ips: List<String>, timeoutMs: Long): Map<String, String> {
        if (ips.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            ips.forEach { ip -> socket!!.send(buildReverseQuery(ip)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                socket.soTimeout = minOf(remain, 500L).toInt()
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                collectNames(packet.data, packet.length, result)
            }
        } catch (e: Exception) {
            // mDNS 不可用（无网络等）静默降级，不影响扫描主流程
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
            }
        }
        return result
    }

    /**
     * 遍历报文记录，收集两类设备名信息：
     * - PTR 记录且名称以 ".in-addr.arpa" 结尾：反查响应（记录名反解 IP，rdata 为设备名）；
     * - A 记录：公告（记录名即设备名，rdata 为 IPv4）。仅收集私网 IP 防公网记录混入。
     */
    private fun collectNames(data: ByteArray, length: Int, result: MutableMap<String, String>) {
        forEachRecord(data, length) { name, type, reader, rdataStart, rdlen ->
            when {
                // 反查响应：PTR 名称 = x.x.x.x.in-addr.arpa，rdata = 设备名
                type == 12 && name.endsWith(".in-addr.arpa") -> {
                    val host = reader.readName()
                    val ip = name.removeSuffix(".in-addr.arpa").split(".").reversed().joinToString(".")
                    val devName = cleanName(host)
                    if (devName.isNotEmpty()) result[ip] = devName
                }
                // 公告：A 记录名称 = 设备名.local，rdata = 4 字节 IPv4
                type == 1 && rdlen == 4 -> {
                    if (rdataStart + 4 <= length) {
                        val ip = listOf(0, 1, 2, 3)
                            .joinToString(".") { (data[rdataStart + it].toInt() and 0xFF).toString() }
                        if (isPrivateIp(ip)) {
                            val devName = cleanName(name)
                            if (devName.isNotEmpty() && !devName.startsWith("_")) {
                                result[ip] = devName
                            }
                        }
                    }
                }
            }
        }
    }

    /** 遍历 DNS 报文的 Question 之后所有记录（Answer + Authority + Additional） */
    private fun forEachRecord(
        data: ByteArray,
        length: Int,
        block: (name: String, type: Int, reader: DnsReader, rdataStart: Int, rdlen: Int) -> Unit
    ) {
        if (length < 12) return
        val reader = DnsReader(data, length)
        reader.pos = 2
        reader.u16() // flags
        val qd = reader.u16()
        val an = reader.u16()
        val ns = reader.u16()
        val ar = reader.u16()
        repeat(qd) {
            reader.readName()
            reader.pos += 4
        }
        fun readRecords(count: Int) {
            repeat(count) {
                val name = reader.readName()
                val type = reader.u16()
                reader.pos += 2 // class
                reader.pos += 4 // ttl
                val rdlen = reader.u16()
                val rdataStart = reader.pos
                block(name, type, reader, rdataStart, rdlen)
                // 恢复位置（block 内可能读 rdata 名称推进了 pos）
                reader.pos = rdataStart + rdlen
            }
        }
        readRecords(an)
        readRecords(ns)
        readRecords(ar)
    }

    /** 构造 in-addr.arpa PTR 查询报文（QU 置位请求单播响应） */
    private fun buildReverseQuery(ip: String): DatagramPacket {
        val parts = ip.split(".")
        require(parts.size == 4) { "无效 IP：$ip" }
        val name = "${parts[3]}.${parts[2]}.${parts[1]}.${parts[0]}.in-addr.arpa"

        val out = ByteArrayOutputStream()
        val buf = DataOutputStream(out)
        // Header：ID=0（mDNS 规范），flags=0（标准查询），QD=1
        buf.writeShort(0)
        buf.writeShort(0)
        buf.writeShort(1)
        buf.writeShort(0)
        buf.writeShort(0)
        buf.writeShort(0)
        name.split(".").forEach { label ->
            buf.writeByte(label.length)
            buf.writeBytes(label)
        }
        buf.writeByte(0)
        buf.writeShort(12)            // PTR
        buf.writeShort(0x8001)        // IN + QU 位（0x8000）
        buf.flush()
        return DatagramPacket(out.toByteArray(), out.size(), mdnsGroup, 5353)
    }

    /** 设备名清洗：去 ".local" 后缀与收尾点 */
    private fun cleanName(name: String): String =
        name.removeSuffix(".local").removeSuffix(".").trim()

    /** 私网 IP 判断（公告中的 A 记录只保留局域网地址） */
    private fun isPrivateIp(ip: String): Boolean {
        return ip.startsWith("10.") ||
            ip.startsWith("192.168.") ||
            (ip.startsWith("172.") && (ip.split(".")[1].toIntOrNull() ?: 0) in 16..31)
    }

    /** 获取 MulticastLock；失败（非 Wi-Fi 环境等）返回 null */
    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("toolbox-mdns")
            lock.setReferenceCounted(false)
            lock.acquire()
            lock
        } catch (e: Exception) {
            null
        }
    }

    /**
     * DNS 报文读取器：支持压缩名称指针（0xC0 指针）。
     * 响应中的名称大量使用压缩指针，必须正确处理。
     */
    private class DnsReader(private val data: ByteArray, private val length: Int) {
        var pos = 0

        fun u8(): Int {
            if (pos >= length) throw IndexOutOfBoundsException()
            return data[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        /**
         * 读取点分名称，处理压缩指针。
         * 指针跳转只影响读取，不影响后续 pos（pos 指向指针之后）。
         */
        fun readName(): String {
            val sb = StringBuilder()
            var p = pos
            var jumped = false
            var jumps = 0
            while (true) {
                if (p >= length) break
                val len = data[p].toInt() and 0xFF
                when {
                    len == 0 -> {
                        p++
                        if (!jumped) pos = p
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        if (p + 1 >= length) break
                        val ptr = ((len and 0x3F) shl 8) or (data[p + 1].toInt() and 0xFF)
                        if (!jumped) {
                            pos = p + 2
                            jumped = true
                        }
                        p = ptr
                        if (++jumps > 20) break
                    }
                    else -> {
                        if (p + 1 + len > length) break
                        if (sb.isNotEmpty()) sb.append('.')
                        for (i in 0 until len) sb.append((data[p + 1 + i].toInt() and 0xFF).toChar())
                        p += 1 + len
                    }
                }
            }
            if (!jumped) pos = p
            return sb.toString()
        }
    }
}
