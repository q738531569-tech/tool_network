package com.installer.toolbox.core.network.discovery

/**
 * 端口 → 服务名映射（内置常用表）。
 *
 * 覆盖装维场景常见服务：Web / SSH / 远程桌面 / 摄像头私有端口 / P2P 等。
 * 未收录端口返回 null，UI 显示原始端口号即可。
 */
object PortServiceTable {

    private val SERVICES: Map<Int, String> = mapOf(
        // 文件传输 / 远程管理
        20 to "FTP-数据",
        21 to "FTP",
        22 to "SSH",
        23 to "Telnet",
        25 to "SMTP",
        69 to "TFTP",
        445 to "SMB",
        548 to "AFP",
        2049 to "NFS",
        3389 to "远程桌面",
        5900 to "VNC",
        6000 to "X11",
        // Web / 应用
        80 to "HTTP",
        81 to "HTTP",
        443 to "HTTPS",
        8000 to "HTTP-管理",
        8008 to "HTTP",
        8080 to "HTTP-代理",
        8443 to "HTTPS-管理",
        8888 to "HTTP-管理",
        9090 to "HTTP-管理",
        10000 to "HTTP-管理",
        // 网络服务
        53 to "DNS",
        67 to "DHCP",
        68 to "DHCP",
        123 to "NTP",
        161 to "SNMP",
        162 to "SNMP-陷阱",
        179 to "BGP",
        500 to "IPsec",
        514 to "Syslog",
        1900 to "UPnP",
        5353 to "mDNS",
        // 邮件 / 目录
        110 to "POP3",
        143 to "IMAP",
        993 to "IMAPS",
        995 to "POP3S",
        389 to "LDAP",
        636 to "LDAPS",
        3268 to "AD-GC",
        // 共享 / 发现
        137 to "NetBIOS",
        138 to "NetBIOS",
        139 to "NetBIOS-SMB",
        // 数据库
        3306 to "MySQL",
        5432 to "PostgreSQL",
        6379 to "Redis",
        27017 to "MongoDB",
        // 消息 / 流媒体
        1883 to "MQTT",
        8883 to "MQTT-TLS",
        554 to "RTSP",
        8554 to "RTSP",
        1935 to "RTMP",
        // 摄像头私有端口（装维高频）
        34567 to "海康流媒体",
        37777 to "大华SDK",
        8001 to "海康流媒体",
        9001 to "海康流媒体",
        // P2P / 其他
        1080 to "Socks",
        6667 to "IRC",
        3000 to "HTTP-开发",
        7001 to "HTTP-管理",
        8009 to "AJP"
    )

    /** 端口对应服务名；未知返回 null */
    fun nameOf(port: Int): String? = SERVICES[port]
}
