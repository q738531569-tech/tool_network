package com.installer.toolbox.core.network.discovery

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * MAC OUI 厂商识别（两级查询）。
 *
 * 1. 内置精简中文表：覆盖装维常见厂商（快速路径，无 Context 也能用）；
 * 2. 完整 IEEE OUI 库：assets/nmap_data/nmap-mac-prefixes（约 4.6 万条，与内置 nmap 共用），
 *    首次 init 时懒加载并缓存，英文厂商名经 [toChinese] 映射为常见中文品牌，未映射的显示英文原名。
 *
 * 随机 MAC：Android 10+ 默认按 Wi-Fi 网络随机化 MAC（本地管理地址，第一字节 bit1=1，
 * 通常 02:xx:xx… 或 x2/x6/xA/xE 开头），其 OUI 不代表真实厂商、无法识别——
 * 用 [isRandomMac] 识别并在 UI 提示"隐私保护"，避免误导用户以为是识别失败。
 */
object MacVendorTable {

    /** assets 中完整 OUI 库路径（与内置 nmap 数据共用同一文件） */
    private const val ASSET_OUI = "nmap_data/nmap-mac-prefixes"

    /** 完整库缓存：紧凑前缀（6 位大写无冒号）→ 英文厂商名；null 表示未加载/加载失败 */
    @Volatile
    private var fullTable: Map<String, String>? = null

    /** 应用 Context（init 时保存，vendorOf 兜底懒加载用） */
    @Volatile
    private var appContext: Context? = null

    /**
     * 初始化完整厂商库（解析 assets 中的 nmap-mac-prefixes）。
     * 在 Application.onCreate 调用；重复调用幂等；失败不抛异常（回退内置小表）。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (fullTable == null) {
            synchronized(this) {
                if (fullTable == null) {
                    fullTable = loadFullTable(context.applicationContext)
                }
            }
        }
    }

    /** 从 assets 解析完整 OUI 库（"前缀 厂商"每行，跳过注释/空行/格式异常行）；失败返回 null */
    private fun loadFullTable(context: Context): Map<String, String>? {
        return try {
            context.assets.open(ASSET_OUI).use { input ->
                val map = HashMap<String, String>(50_000)
                BufferedReader(InputStreamReader(input)).useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                        val parts = trimmed.split(Regex("\\s+"), limit = 2)
                        if (parts.size < 2) return@forEach
                        val prefix = parts[0]
                        // 前缀须为 6 位十六进制（如 "009EC8"）
                        if (prefix.length != 6 || prefix.any { c ->
                                c !in '0'..'9' && c !in 'A'..'F' && c !in 'a'..'f'
                            }) return@forEach
                        map[prefix.uppercase()] = parts[1]
                    }
                }
                map
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据 MAC 地址识别厂商。
     * 支持 "4C:BD:8F:12:34:56" 与 "4cbd8f123456" 两种格式；未知返回 null。
     */
    fun vendorOf(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        val upper = mac.trim().uppercase()
        val prefix = if (upper.contains(":")) {
            if (upper.length < 8) return null
            upper.substring(0, 8)
        } else {
            if (upper.length < 6) return null
            val hex = upper.substring(0, 6)
            "${hex.substring(0, 2)}:${hex.substring(2, 4)}:${hex.substring(4, 6)}"
        }
        // 1. 内置中文表（快速路径）
        TABLE[prefix]?.let { return it }
        // 2. 完整 IEEE OUI 库（未加载则用保存的 Context 兜底懒加载）
        val full = fullTable ?: run {
            appContext?.let { init(it) }
            fullTable
        }
        if (full != null) {
            full[prefix.replace(":", "")]?.let { return toChinese(it) }
        }
        return null
    }

    /**
     * 是否本地管理地址（随机 MAC）。
     * 第一字节 bit1（0x02）置位即本地管理——Android 10+ 默认按网络随机化的 MAC
     * 通常 02:xx:xx… 或 x2/x6/xA/xE 开头，其 OUI 不代表真实厂商，无法识别厂商。
     */
    fun isRandomMac(mac: String?): Boolean {
        if (mac.isNullOrBlank()) return false
        val first = mac.trim().split(":", limit = 2).firstOrNull()?.toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }

    /** 英文厂商名 → 常见中文品牌（顺序敏感：特例/长词在前，短词按 token 匹配防误伤） */
    private fun toChinese(vendor: String): String {
        val v = vendor.lowercase()
        // 长词关键词直接 contains 匹配（不易误伤，如 Huawei/Honor/Xiaomi）
        for ((keyword, zh) in CHINESE_VENDOR_CONTAINS) {
            if (v.contains(keyword)) return zh
        }
        // 短词关键词按空白分词匹配（避免 Aztek/Naztec 被误判为中兴、Netcleus 被误判为 TCL）
        val tokens = v.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for ((keyword, zh) in CHINESE_VENDOR_TOKEN) {
            if (tokens.any { it == keyword || it.startsWith(keyword) }) return zh
        }
        return vendor
    }

    /** 长词关键词（≥4 字符，contains 匹配），顺序即优先级：特例在前（huawei-3com 先于 huawei） */
    private val CHINESE_VENDOR_CONTAINS: List<Pair<String, String>> = listOf(
        "huawei-3com" to "新华三",
        "huawei" to "华为",
        "honor" to "荣耀",
        "xiaomi" to "小米",
        "oppo" to "OPPO",
        "oneplus" to "一加",
        "realme" to "realme",
        "vivo mobile" to "vivo",
        "imilab" to "创米科技",
        "samsung" to "三星",
        "apple" to "苹果",
        "lenovo" to "联想",
        "motorola" to "摩托罗拉",
        "nokia" to "诺基亚",
        "sony ericsson" to "索尼爱立信",
        "sony" to "索尼",
        "lg electronics" to "LG",
        "google" to "谷歌",
        "foxconn" to "富士康",
        "tp-link" to "TP-LINK",
        "tplink" to "TP-LINK",
        "hikvision" to "海康威视",
        "dahua" to "大华",
        "fiberhome" to "烽火",
        "skyworth" to "创维",
        "tenda" to "腾达",
        "mercury" to "水星",
        "h3c" to "新华三",
        "ruijie" to "锐捷",
        "zyxel" to "合勤",
        "asus" to "华硕",
        "acer" to "宏碁",
        "dell" to "戴尔",
        "hewlett" to "惠普",
        "intel" to "英特尔",
        "qualcomm" to "高通",
        "mediatek" to "联发科",
        "broadcom" to "博通",
        "realtek" to "瑞昱",
        "espressif" to "乐鑫",
        "texas instruments" to "德州仪器",
        "midea" to "美的",
        "gree" to "格力",
        "hisense" to "海信",
        "konka" to "康佳",
        "jdcloud" to "京东云",
        "playcloud" to "玩客云",
        "netcore" to "磊科",
        "universal scientific" to "环旭电子",
        "alibaba" to "阿里巴巴",
        "tencent" to "腾讯",
        "bytedance" to "字节跳动"
    )

    /** 短词关键词（按空白分词匹配，防 contains 误伤），顺序即优先级 */
    private val CHINESE_VENDOR_TOKEN: List<Pair<String, String>> = listOf(
        "zte" to "中兴",
        "tcl" to "TCL",
        "hp" to "惠普"
    )

    /**
     * PCDN 特征关键词（主机名，匹配小写）。
     * 玩客云/京东云无线宝/迅雷赚钱宝/网心云等 P2P CDN 设备的主机名常见词。
     */
    private val PCDN_HOST_KEYWORDS = listOf(
        "thunder", "xunlei", "wangxin", "jdc", "jdcloud", "playcloud",
        "cdnode", "pcdn", "walkcloud", "xiazaibao", "netcore", "赚钱宝", "玩客云", "网心"
    )

    /** PCDN 特征端口（常见 P2P CDN 服务端口，启发式，仅供参考） */
    private val PCDN_PORTS = setOf(10881, 10999, 27015, 27016, 27017, 27018, 27019, 28015, 8800, 22222)

    /** 判定是否疑似 PCDN 设备：主机名含关键词 或 开放了特征端口 */
    fun isPcdn(hostname: String?, openPorts: List<Int>): Boolean {
        val h = hostname?.lowercase().orEmpty()
        if (PCDN_HOST_KEYWORDS.any { h.contains(it) }) return true
        return openPorts.any { it in PCDN_PORTS }
    }

    /** 内置精简中文表（快速路径 + 无 Context 时的兜底） */
    private val TABLE: Map<String, String> = mapOf(
        // 海康威视（摄像头/录像机）
        "4C:BD:8F" to "海康威视",
        "28:57:BE" to "海康威视",
        "54:C4:15" to "海康威视",
        "44:19:B6" to "海康威视",
        "C4:2F:90" to "海康威视",
        // 华为（终端/光猫/路由器）
        "00:E0:FC" to "华为",
        "E0:5A:1B" to "华为",
        "A4:1F:72" to "华为",
        // TP-LINK（路由器）
        "50:FA:84" to "TP-LINK",
        "D8:07:B6" to "TP-LINK",
        "14:CF:92" to "TP-LINK",
        "30:B4:9E" to "TP-LINK",
        "A0:63:91" to "TP-LINK",
        // 小米（手机/智能家居）
        "64:09:80" to "小米",
        "F0:B4:29" to "小米",
        // 中兴（光猫/路由器）
        "38:2C:4A" to "中兴",
        "98:00:6A" to "中兴",
        // 苹果（手机/平板/电脑）
        "A4:83:E7" to "苹果",
        "F0:18:98" to "苹果",
        "F4:0F:24" to "苹果",
        "C8:69:CD" to "苹果",
        "3C:15:C2" to "苹果",
        // 三星（手机/电视）
        "A8:7C:01" to "三星",
        "C8:94:BB" to "三星",
        "5C:0A:5B" to "三星",
        // 戴尔（电脑）
        "B8:AC:6F" to "戴尔",
        "F8:BC:12" to "戴尔",
        "18:03:73" to "戴尔",
        // 惠普（电脑/打印机）
        "9C:B6:54" to "惠普",
        "3C:D9:2B" to "惠普",
        "18:67:B0" to "惠普",
        // 联想（电脑）
        "54:EE:75" to "联想",
        "34:23:87" to "联想"
    )
}
