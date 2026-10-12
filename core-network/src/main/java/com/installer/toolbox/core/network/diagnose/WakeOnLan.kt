package com.installer.toolbox.core.network.diagnose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 局域网唤醒（Wake-on-LAN）工具：向目标设备 MAC 发送 magic packet（魔术包），远程开机。
 *
 * 使用前提：
 * - 目标设备 BIOS/网卡需开启 Wake-on-LAN（网络唤醒），且处于关机/休眠（S3/S5）状态；
 * - 本机与目标需在同一局域网：默认广播到 255.255.255.255，
 *   也可定向到子网广播地址（如 192.168.1.255，可避免跨 VLAN 被过滤）。
 *
 * 无 root 即可使用（UDP 广播无需特殊权限）。
 */
object WakeOnLan {

    /** MAC 地址格式（冒号分隔 6 组十六进制：XX:XX:XX:XX:XX:XX） */
    private val MAC_REGEX = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")

    /**
     * 发送唤醒包（magic packet）。
     *
     * 包结构：6 字节 0xFF + 目标 MAC 重复 16 次，共 102 字节；
     * 发送到 [broadcast]:[port]，套接字开启广播（setBroadcast(true)）。
     *
     * @param mac 目标网卡 MAC，格式 XX:XX:XX:XX:XX:XX（非法直接返回 false）
     * @param broadcast 广播地址，默认 255.255.255.255（也可传子网广播地址）
     * @param port 目标端口，默认 9（UDP，部分设备用 7）
     * @param bindIp 本机源地址（局域网接口 IP，非空时绑定该接口）——
     *   多网络时广播不从移动数据默认路由误发；null 保持系统默认
     * @return 是否发送成功；MAC 非法或发送异常均返回 false（不抛异常）
     */
    suspend fun wake(
        mac: String,
        broadcast: String = "255.255.255.255",
        port: Int = 9,
        bindIp: String? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            val clean = mac.trim()
            // 1. 校验 MAC 格式（非法直接返回 false）
            if (!MAC_REGEX.matches(clean)) return@withContext false
            try {
                // 2. 构造 magic packet：6×0xFF + 16×MAC（共 102 字节）
                val macBytes = clean.split(":").map { it.toInt(16).toByte() }
                val payload = ByteArray(6) { 0xFF.toByte() } +
                    ByteArray(16) { i -> macBytes[i % 6] }
                // 3. 广播发送（开启 broadcast 标志；bindIp 非空时绑定局域网接口）
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    if (bindIp != null) {
                        socket.bind(InetSocketAddress(bindIp, 0))
                    }
                    socket.send(
                        DatagramPacket(
                            payload,
                            payload.size,
                            InetAddress.getByName(broadcast),
                            port
                        )
                    )
                }
                true
            } catch (e: Exception) {
                false // 发送异常：不抛出，返回 false
            }
        }
}
