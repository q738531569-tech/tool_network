package com.installer.toolbox.common.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 诊断报告表：Ping / Traceroute / DNS / 端口扫描 的执行结果自动存档。
 */
@Entity(tableName = "diag_reports")
data class DiagReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 诊断类型：ping / traceroute / dns / portscan */
    val type: String,
    /** 诊断目标（IP 或域名） */
    val target: String,
    /** 一行摘要，用于列表展示 */
    val summary: String,
    /** 详细结果（多行文本） */
    val detail: String,
    /** 生成时间戳（毫秒） */
    val timestamp: Long
)
