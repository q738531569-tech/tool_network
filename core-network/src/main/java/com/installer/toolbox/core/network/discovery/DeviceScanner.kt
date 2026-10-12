package com.installer.toolbox.core.network.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.installer.toolbox.core.network.scan.LanInterfaceResolver
import com.installer.toolbox.core.network.util.ArpTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * 局域网设备扫描器（M1 核心功能）。
 *
 * 原理：对 /24 网段 1-254 全部主机做并发 TCP connect 探测（常见端口），
 * 存活主机再读 /proc/net/arp 匹配 MAC，用 OUI 表识别厂商。
 * 无 root 无法发原始 ARP 包，这是 Android 上的标准变通方案。
 *
 * 设备名增强：传入 context 时，扫描完成后对全部存活 IP 做一次 mDNS 反查
 * （[MdnsResolver]），随机 MAC 的手机等设备也能拿到真实设备名。
 */
class DeviceScanner(private val context: Context? = null) {

    /** 常见探测端口 */
    private val probePorts = listOf(80, 443, 22, 23, 8080)

    /** 单端口连接超时（毫秒） */
    private val connectTimeoutMs = 400

    /** 并发探测上限（Semaphore 限流） */
    private val concurrency = 64

    /** mDNS 公告并行监听时长（毫秒）：公告间隔可达数十秒，窗口尽量拉长 */
    private val announceListenMs = 20_000L

    /** mDNS 主动反查等待时长（毫秒） */
    private val activeResolveMs = 4_000L

    /** 后台协程作用域：mDNS 监听/公告收集与扫描并行，**不阻塞主扫描流程**
     * （coroutineScope { async } 会等待子协程完成，曾致扫描被 mDNS 20s 阻塞） */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 扫描 /24 网段（如 "192.168.1.0/24"）。
     * @param subnet 网段字符串
     * @param rootRun root 命令执行通道；非 null 时用 ARP 发现（ping 触发 ARP 解析→读 ARP 表），
     *               能扫到端口全关/禁 ping 的设备（链路层响应）；null 退回 TCP 端口探测
     * @param enableMdns 是否做 mDNS 设备名增强（默认 true）；特征扫描等
     *   "只要设备清单不要设备名" 的场景传 false，避免 mDNS 等待拖慢返回
     * @param onProgress 进度回调（已完成数, 总数）。注意：须保持为最后一个参数
     *   （尾 lambda 语法绑定最后一个参数，特征扫描等场景用命名参数调用）
     * @return 在线主机列表，按 IP 排序
     */
    suspend fun scan(
        subnet: String,
        rootRun: ((String) -> String)? = null,
        enableMdns: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        /** 每发现一台在线设备时回调（实时逐台展示用，IO 线程触发） */
        onHost: (HostInfo) -> Unit = {}
    ): List<HostInfo> = withContext(Dispatchers.IO) {
        val base = subnetBase(subnet)
        val total = 254
        // mDNS 公告监听与扫描并行启动（被动通道，覆盖设备公告周期；
        // 嵌入式设备不响应反查但会主动公告，公告的 A 记录带设备名）
        val announceDeferred = if (context != null && enableMdns) {
            backgroundScope.async { MdnsResolver(context).resolve(emptyList(), announceListenMs) }
        } else {
            null
        }
        // NsdManager 系统通道：拿到注册常见服务（airplay/投屏/打印等）的设备名
        val nsdDeferred = if (context != null && enableMdns) {
            backgroundScope.async { NsdDiscoverer(context).discover(announceListenMs) }
        } else {
            null
        }
        val found = if (rootRun != null) {
            runRootArpScan(base, rootRun, onProgress, onHost)
        } else {
            tcpProbeScan(base, onProgress, onHost)
        }
        // 合并 mDNS 设备名：主动反查（在线 IP）+ 并行公告监听
        if (context != null && enableMdns && found.isNotEmpty()) {
            val resolver = MdnsResolver(context)
            val names = mutableMapOf<String, String>()
            try {
                names.putAll(resolver.resolve(found.map { it.ip }, activeResolveMs))
            } catch (e: Exception) {
            }
            try {
                names.putAll(withTimeoutOrNull(3_000L) { announceDeferred?.await() }.orEmpty())
            } catch (e: Exception) {
            }
            try {
                names.putAll(withTimeoutOrNull(3_000L) { nsdDeferred?.await() }.orEmpty())
            } catch (e: Exception) {
            }
            if (names.isNotEmpty()) {
                return@withContext found.map { host ->
                    val name = names[host.ip]
                    if (name != null && host.hostname == null) {
                        host.copy(hostname = name)
                    } else {
                        host
                    }
                }
            }
        }
        found
    }

    /** 无 root：并发 TCP 端口探测 + ARP 表读 MAC/厂商（原标准流程） */
    private suspend fun tcpProbeScan(
        base: String,
        onProgress: (done: Int, total: Int) -> Unit,
        onHost: (HostInfo) -> Unit
    ): List<HostInfo> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(concurrency)
        val arpTable = readArpTable()
        val total = 254

        val found = coroutineScope {
            (1..254).map { host ->
                async {
                    semaphore.withPermit {
                        val ip = "$base$host"
                        val openPorts = probeHost(ip)
                        onProgress(host, total)
                        if (openPorts.isEmpty()) {
                            null
                        } else {
                            buildHostInfo(ip, openPorts, arpTable).also { onHost(it) }
                        }
                    }
                }
            }.awaitAll()
        }
        found.filterNotNull().sortedBy { it.ip }.let { initial ->
            // 探测完成后 ARP 表已被 TCP 探测填充（connect 会触发 ARP 解析），
            // 重新读取并合并，补全扫描开始时尚未缓存的 MAC/厂商信息
            val freshArp = readArpTable()
            initial.map { host ->
                val mac = freshArp[host.ip] ?: host.mac
                if (mac != null && mac != host.mac) {
                    host.copy(
                        mac = mac,
                        vendor = MacVendorTable.vendorOf(mac),
                        randomMac = MacVendorTable.isRandomMac(mac)
                    )
                } else {
                    host
                }
            }
        }
    }

    /**
     * root 模式：并发 ping 全网段触发 ARP 解析（ARP 是链路层，即使设备禁 ping/端口全关也会
     * 响应 ARP，AP 代理单播时也生效），读 ARP 表拿 IP+MAC，再对发现的设备做轻量端口探测补充服务信息。
     */
    private suspend fun runRootArpScan(
        base: String,
        rootRun: (String) -> String,
        onProgress: (done: Int, total: Int) -> Unit,
        onHost: (HostInfo) -> Unit
    ): List<HostInfo> = withContext(Dispatchers.IO) {
        // 分批（8 批 × 32 台）ping 触发 ARP 解析：每批 ping 完立即读 ARP 表并回调新设备，
        // 实现"扫出一个显示一个"的实时效果（一次性等全部会让人以为卡死）。
        // 注意：必须 root 读 ARP 表——app 进程读 /proc/net/arp 受 SELinux 限制（表现为 MAC 全未知）
        val semaphore = Semaphore(concurrency)
        val result = mutableListOf<HostInfo>()
        val seenIp = mutableSetOf<String>()
        val BATCH = 32
        for (batchStart in 1..254 step BATCH) {
            val batchEnd = minOf(batchStart + BATCH - 1, 254)
            // Kotlin 生成字面 IP，避开 sh 变量/命令替换转义坑；& 后台并发 + wait
            val cmds = (batchStart..batchEnd).joinToString(" & ") {
                "ping -c 1 -W 1 ${base}$it >/dev/null 2>&1"
            }
            val out = rootRun("$cmds & wait; sleep 0.5; cat /proc/net/arp")
            val arp: Map<String, String> = parseArpTable(out)
            // filterKeys 直接产 Map，避免 entries.filter 的类型推断歧义
            val fresh = arp.filterKeys { it.startsWith(base) && it !in seenIp }
            if (fresh.isNotEmpty()) {
                seenIp += fresh.keys
                val hosts = coroutineScope {
                    fresh.map { (ip, mac) ->
                        async {
                            semaphore.withPermit {
                                onProgress(1, 1)
                                buildHostInfo(ip, probeHost(ip), mapOf(ip to mac))
                            }
                        }
                    }.awaitAll()
                }
                hosts.forEach {
                    result.add(it)
                    onHost(it)
                }
            }
        }
        result.sortedBy { it.ip }
    }

    /**
     * 解析 ARP 表文本（root 输出格式：IP ... Flags MAC ...）。
     * 放宽：交给 [ArpTable.parse]，只要 MAC 合法且非全零即收录，不再要求 Flags == 0x2
     * （0x0 未确认条目也带历史真实 MAC，避免被攻击过/未确认设备 MAC 丢失）。
     */
    private fun parseArpTable(text: String): Map<String, String> = ArpTable.parse(text)

    /** 解析 "192.168.1.0/24" → "192.168.1." */
    private fun subnetBase(subnet: String): String {
        val prefix = subnet.substringBefore("/").trim()
        val parts = prefix.split(".")
        require(parts.size == 4) { "无效网段：$subnet" }
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    /** 探测主机的常见端口，返回开放端口列表 */
    private fun probeHost(ip: String): List<Int> {
        val open = mutableListOf<Int>()
        for (port in probePorts) {
            if (isPortOpen(ip, port)) open.add(port)
        }
        return open
    }

    /** 单端口 TCP connect 探测 */
    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 组装主机信息：MAC → 厂商 → 主机名 → PCDN 疑似标记 */
    private fun buildHostInfo(
        ip: String,
        openPorts: List<Int>,
        arpTable: Map<String, String>
    ): HostInfo {
        val mac = arpTable[ip]
        val hostname = resolveHostname(ip)
        return HostInfo(
            ip = ip,
            mac = mac,
            vendor = MacVendorTable.vendorOf(mac),
            hostname = hostname,
            openPorts = openPorts,
            lastSeen = System.currentTimeMillis(),
            pcdn = MacVendorTable.isPcdn(hostname, openPorts),
            randomMac = MacVendorTable.isRandomMac(mac)
        )
    }

    /**
     * 读取 /proc/net/arp，返回 IP → MAC 映射。
     * 放宽：交给 [ArpTable.parse]，只要 MAC 合法且非全零即收录（不再要求 0x2）。
     */
    private fun readArpTable(): Map<String, String> = try {
        ArpTable.parse(File("/proc/net/arp").readText())
    } catch (e: Exception) {
        emptyMap()
    }

    /** 尽力反向解析主机名；失败或解析结果就是 IP 本身时返回 null */
    private fun resolveHostname(ip: String): String? {
        return try {
            val host = InetAddress.getByName(ip).canonicalHostName
            if (host.isBlank() || host == ip) null else host
        } catch (e: UnknownHostException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** 自动取当前 Wi-Fi 所在 /24 网段；未连接 Wi-Fi 时回退 192.168.1.0/24 */
        fun currentSubnet(context: Context): String {
            return try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wifi.connectionInfo ?: return "192.168.1.0/24"
                val ip = info.ipAddress
                if (ip == 0) {
                    "192.168.1.0/24"
                } else {
                    // ipAddress 为小端整数，先反转再取网络号（/24）
                    // 注意：0xFFFFFF00 超 Int 范围会被推断为 Long，需 .toInt() 保持 Int 位运算
                    val net = Integer.reverseBytes(ip) and 0xFFFFFF00.toInt()
                    "${(net ushr 24) and 0xFF}.${(net ushr 16) and 0xFF}.${(net ushr 8) and 0xFF}.0/24"
                }
            } catch (e: Exception) {
                "192.168.1.0/24"
            }
        }

        /**
         * root 版：优先用 [LanInterfaceResolver] 解析局域网接口所在网段
         * （支持 PDA 有线底座 br0/eth0 与"局域网无外网、默认路由在移动数据"的场景），
         * 解析失败回退 Wi-Fi 版。
         */
        fun currentSubnet(context: Context, rootRun: (String) -> String): String {
            val lan = try {
                LanInterfaceResolver.resolve(rootRun)
            } catch (e: Exception) {
                null
            }
            if (lan != null) return lan.subnet
            return currentSubnet(context)
        }
    }
}
