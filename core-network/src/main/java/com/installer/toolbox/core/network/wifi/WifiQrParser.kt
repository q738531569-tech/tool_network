package com.installer.toolbox.core.network.wifi

/**
 * WiFi 二维码解析（扫码连 WiFi 用）。
 *
 * 手机/路由器「分享 WiFi」生成的二维码内容为标准格式：
 * `WIFI:T:WPA;S:小米路由;P:12345678;H:true;;`
 * - T：加密类型（WPA/WPA2/WPA3/WEP/nopass/空=开放）
 * - S：SSID（必填）
 * - P：密码（T=nopass 时无）
 * - H：true=隐藏网络（可选）
 * - 字段值里出现 `;` `:` `\` 时用 `\;` `\:` `\\` 转义
 *
 * 解析对键大小写不敏感（部分厂商生成大写 WIFI:/小写 s: 混用）。
 */
data class WifiQr(
    val ssid: String,
    val password: String? = null,   // 开放网络（nopass）为 null
    val type: String? = null,       // WPA/WPA2/WPA3/WEP/nopass；null=未标
    val hidden: Boolean = false
) {
    /** 是否开放网络（无密码） */
    val isOpen: Boolean get() = type.equals("nopass", true) || password.isNullOrEmpty()
}

object WifiQrParser {

    /**
     * 解析 WiFi 二维码文本；非 `WIFI:` 前缀或 SSID 缺失返回 null。
     * @param raw 二维码解码出的原始文本
     */
    fun parse(raw: String?): WifiQr? {
        if (raw == null) return null
        val t = raw.trim()
        if (!t.startsWith("WIFI:", ignoreCase = true)) return null

        val body = t.substring(5) // 去掉 "WIFI:"（可能大小写混合，按 5 字符切）
        var ssid: String? = null
        var password: String? = null
        var type: String? = null
        var hidden = false

        var i = 0
        val n = body.length
        while (i < n) {
            // 跳过前导分隔符
            if (body[i] == ';') { i++; continue }
            // 找键（单字符 + ':'）
            if (i + 1 >= n || body[i + 1] != ':') { i++; continue }
            val key = body[i]
            i += 2
            // 收集值直到未转义的 ';'，处理 \; \: \\ 转义
            val value = StringBuilder()
            while (i < n && body[i] != ';') {
                val c = body[i]
                if (c == '\\' && i + 1 < n) {
                    value.append(body[i + 1]) // \x → x（; : \ 都还原）
                    i += 2
                } else {
                    value.append(c)
                    i++
                }
            }
            when (key) {
                'S', 's' -> if (ssid == null) ssid = value.toString()
                'P', 'p' -> if (password == null) password = value.toString()
                'T', 't' -> if (type == null) type = value.toString()
                'H', 'h' -> hidden = value.toString().equals("true", ignoreCase = true)
            }
        }

        val name = ssid?.trim().orEmpty()
        if (name.isEmpty()) return null // SSID 缺失不构成可连 WiFi
        return WifiQr(
            ssid = name,
            password = password?.takeIf { it.isNotEmpty() },
            type = type,
            hidden = hidden
        )
    }
}
