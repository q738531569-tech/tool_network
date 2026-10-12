package com.installer.toolbox.core.network.discovery

import android.os.Build
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * NsdManager 设备名发现（Android 系统 mDNS 通道）。
 *
 * 与自研 [MdnsResolver] 互补：NsdManager 走系统 mdnsd，**不受 5353 端口抢占
 * 与 MulticastLock 限制**，能拿到注册了常见服务类型（_airplay/_raop/_googlecast 等）
 * 的设备：实例名/主机名 + IPv4。机顶盒/电视/Chromecast/打印机等常注册这些服务。
 *
 * 局限：只能发现"注册了已知服务类型"的设备；手机一般不注册 → 手机靠 [MdnsResolver] 反查。
 */
class NsdDiscoverer(private val context: Context) {

    /** 常见服务类型：覆盖电视/盒子/投屏/打印机/智能家居 */
    private val defaultTypes = listOf(
        "_airplay._tcp",
        "_raop._tcp",
        "_googlecast._tcp",
        "_http._tcp",
        "_ipp._tcp",
        "_lyra-mdns._udp"
    )

    /**
     * 监听指定服务类型一段时间，收集 设备名 → IPv4。
     *
     * @param durationMs 监听时长（毫秒）
     * @param serviceTypes 服务类型列表；默认 [defaultTypes]
     * @return IP → 设备名（主机名去除 ".local"）；无发现返回空 Map
     */
    suspend fun discover(
        durationMs: Long,
        serviceTypes: List<String> = defaultTypes
    ): Map<String, String> = withContext(Dispatchers.Main) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val result = ConcurrentHashMap<String, String>()
        // 保存 listener 强引用：NsdManager 回调为弱引用，stop 时还需要原 listener
        val discoveryListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()

        serviceTypes.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    // 解析服务拿到主机名与 IP（resolve 是异步的，回调需强引用）
                    try {
                        nsd.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                }

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    // 设备名 = 服务实例名（如 "xiaomi-0ccdb4b2f679"），host 只有 IP
                                    val name = info.serviceName.removeSuffix(".local").trim()
                                    if (name.isEmpty() || name.startsWith("_")) return
                                    // hostAddresses（List）是 Android 14+ 才有；13 及以下用 getHostAddress 单个（兼容 PDA/老设备）
                                    @Suppress("DEPRECATION")
                                    val addresses = if (Build.VERSION.SDK_INT >= 34) {
                                        info.hostAddresses
                                    } else {
                                        val single = info.host
                                        if (single != null) listOf(single) else emptyList()
                                    }
                                    addresses.forEach { addr ->
                                        val ip = addr.hostAddress
                                        // 只要 IPv4（mDNS 反查/公告通道都按 IPv4 匹配）
                                        if (ip != null && ip.contains('.')) {
                                            result[ip] = name
                                        }
                                    }
                                }
                            }
                        )
                    } catch (e: Exception) {
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                }

                override fun onDiscoveryStopped(serviceType: String) {
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                }
            }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                discoveryListeners[type] = listener
            } catch (e: Exception) {
                // 单个类型失败不影响其他
            }
        }

        delay(durationMs)

        // 停止监听（用保存的 listener）
        discoveryListeners.forEach { (type, listener) ->
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (e: Exception) {
            }
        }
        result.toMap()
    }
}
