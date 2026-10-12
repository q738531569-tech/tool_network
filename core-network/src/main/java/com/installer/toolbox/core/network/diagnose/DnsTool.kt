package com.installer.toolbox.core.network.diagnose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.Lookup
import org.xbill.DNS.Record
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.net.InetAddress
import kotlin.system.measureTimeMillis

/**
 * DNS 查询工具（基于 dnsjava）。
 * 查询 A / AAAA / MX / NS / TXT 五类记录并统计耗时。
 */
object DnsTool {

    /** 单条 DNS 记录 */
    data class DnsRecordInfo(
        val name: String,
        /** 记录类型，如 A / AAAA / MX */
        val type: String,
        /** 记录数据（rdata） */
        val rdata: String
    )

    /** DNS 查询结果 */
    data class DnsResult(
        val host: String,
        val records: List<DnsRecordInfo>,
        /** 总查询耗时（毫秒） */
        val elapsedMs: Long,
        /** 状态说明：成功 / 无记录 / 解析失败 */
        val status: String
    )

    /** 查询的记录类型 */
    private val queryTypes = intArrayOf(Type.A, Type.AAAA, Type.MX, Type.NS, Type.TXT)

    /** 备用 DNS 服务器（阿里公共 DNS） */
    private const val FALLBACK_DNS = "223.5.5.5"

    /**
     * 查询指定主机的各类型记录。
     * @param host 域名
     * @param bindIp 本机源地址（如局域网接口 IP，非空时绑定该地址），
     *   让 DNS 查询流量从指定接口发出；null 保持默认行为
     */
    suspend fun query(host: String, bindIp: String? = null): DnsResult = withContext(Dispatchers.IO) {
        val records = mutableListOf<DnsRecordInfo>()
        var elapsed = 0L
        var status = "成功"
        try {
            for (type in queryTypes) {
                val typeName = Type.string(type)
                val lookup = Lookup(host, type)
                // 显式指定公共 DNS，避免 Android 默认解析器不可用
                val resolver = SimpleResolver(FALLBACK_DNS)
                // 绑定本机源地址：让 DNS 查询从指定接口发出（局域网无外网时不误走移动数据）
                if (bindIp != null) {
                    resolver.setLocalAddress(InetAddress.getByName(bindIp))
                }
                lookup.setResolver(resolver)
                val time = measureTimeMillis {
                    val result = lookup.run()
                    if (result != null) {
                        result.forEach { record ->
                            records.add(
                                DnsRecordInfo(
                                    name = record.name.toString(),
                                    type = typeName,
                                    rdata = record.rdataToString()
                                )
                            )
                        }
                    }
                }
                elapsed += time
            }
            if (records.isEmpty()) status = "无记录"
        } catch (e: Exception) {
            status = "解析失败：${e.message ?: e.javaClass.simpleName}"
        }
        DnsResult(
            host = host,
            records = records,
            elapsedMs = elapsed,
            status = status
        )
    }
}
