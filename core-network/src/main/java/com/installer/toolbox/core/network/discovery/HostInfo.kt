package com.installer.toolbox.core.network.discovery

/**
 * 局域网主机信息：设备发现页的核心数据模型。
 */
data class HostInfo(
    /** IP 地址 */
    val ip: String,
    /** MAC 地址；ARP 表未匹配到时为 null */
    val mac: String?,
    /** 厂商（MAC OUI 前缀识别）；未知为 null */
    val vendor: String?,
    /** 主机名（尽力反向解析）；失败为 null */
    val hostname: String?,
    /** 在线端口列表（如 80、443） */
    val openPorts: List<Int>,
    /** 最后在线时间戳（毫秒） */
    val lastSeen: Long,
    /**
     * 疑似 PCDN 设备（玩客云/京东云无线宝/迅雷赚钱宝/网心云等）。
     * 启发式判定：主机名关键词 + 特征端口，仅供参考不保证准确。
     */
    val pcdn: Boolean = false,
    /**
     * 是否本地管理地址（随机 MAC）。
     * Android 10+ 默认按 Wi-Fi 网络随机化 MAC，此类 MAC 的 OUI 不代表真实厂商，
     * 厂商识别必然失败——UI 应提示"隐私保护"而非"未知厂商"。
     */
    val randomMac: Boolean = false
)
