package com.installer.toolbox.common.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 设备清单表：保存用户从"设备发现"页收藏/保存的设备。
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    /** 主键：优先用 MAC 地址，无 MAC 时退化为 IP */
    @PrimaryKey val id: String,
    val ip: String,
    /** MAC 地址，可能为空字符串（未从 ARP 表匹配到） */
    val mac: String,
    /** 厂商（由 MAC OUI 前缀识别），可能为空 */
    val vendor: String,
    /** 主机名（尽力解析），可能为空 */
    val hostname: String,
    /** 开放端口列表，逗号分隔，如 "80,443" */
    val openPorts: String,
    /** 最后在线时间戳（毫秒） */
    val lastSeen: Long
)
