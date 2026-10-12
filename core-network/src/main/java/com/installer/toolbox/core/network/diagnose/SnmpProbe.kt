package com.installer.toolbox.core.network.diagnose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snmp4j.CommunityTarget
import org.snmp4j.PDU
import org.snmp4j.Snmp
import org.snmp4j.mp.SnmpConstants
import org.snmp4j.smi.Null
import org.snmp4j.smi.OID
import org.snmp4j.smi.OctetString
import org.snmp4j.smi.TimeTicks
import org.snmp4j.smi.UdpAddress
import org.snmp4j.smi.Variable
import org.snmp4j.smi.VariableBinding
import org.snmp4j.transport.DefaultUdpTransportMapping

/**
 * SNMP 探测器：用 SNMP v2c 查询设备系统信息（名称/描述/运行时间/位置/联系人）。
 *
 * 用于快速了解路由器、交换机、打印机等支持 SNMP 的设备；无需 root，
 * 但目标设备需开启 SNMP 服务（默认 community 为 public）。
 *
 * 实现说明：
 * - 本机随机 UDP 端口监听 → Snmp 实例 → CommunityTarget（udp:目标/161，v2c）；
 * - 一次 GET 全部 5 个 OID；整包失败（如目标不支持 SNMP/community 不对超时）时
 *   逐个 OID 单独重试一次，兼容只响应部分 OID 的设备；单个 OID 失败仅该字段为 null；
 * - sysUpTime 为 TimeTicks（百分之一秒），转成"X天X小时X分"可读文本；
 * - 全部失败 → error 填可读中文错误，其余字段 null；用完 close()（try/finally）。
 */
object SnmpProbe {

    /** SNMP 面板查询结果（全基本类型，不泄漏 snmp4j 类型） */
    data class SnmpResult(
        val ip: String,
        val community: String,
        val sysName: String?,
        val sysDescr: String?,
        val sysUpTime: String?,
        val sysLocation: String?,
        val sysContact: String?,
        val error: String?
    )

    // MIB-2 system 组 OID（.0 = 标量实例）
    private const val OID_SYS_DESCR = "1.3.6.1.2.1.1.1.0"
    private const val OID_SYS_CONTACT = "1.3.6.1.2.1.1.4.0"
    private const val OID_SYS_NAME = "1.3.6.1.2.1.1.5.0"
    private const val OID_SYS_LOCATION = "1.3.6.1.2.1.1.6.0"
    private const val OID_SYS_UP_TIME = "1.3.6.1.2.1.1.3.0"

    /** 查询的 OID 列表（一次 GET 全部） */
    private val OID_LIST = listOf(OID_SYS_NAME, OID_SYS_DESCR, OID_SYS_UP_TIME, OID_SYS_LOCATION, OID_SYS_CONTACT)

    /** 标准 SNMP 端口 */
    private const val SNMP_PORT = 161

    /**
     * 查询设备系统信息。
     *
     * @param ip 目标设备 IP（空 → 参数错误结果，不抛异常）
     * @param community SNMP 团体名，默认 public
     * @param timeoutMs 单次请求超时（毫秒），默认 2000
     * @param bindIp 本机源地址（局域网接口 IP，非空时绑定该接口收发）——
     *   局域网无外网/多网络时查询不从移动数据默认路由误发；null 保持系统默认
     */
    suspend fun query(
        ip: String,
        community: String = "public",
        timeoutMs: Long = 2000,
        bindIp: String? = null
    ): SnmpResult =
        withContext(Dispatchers.IO) {
            val t = ip.trim()
            if (t.isEmpty()) {
                return@withContext SnmpResult(
                    ip = "", community = community, sysName = null, sysDescr = null,
                    sysUpTime = null, sysLocation = null, sysContact = null,
                    error = "参数错误：请输入设备 IP"
                )
            }
            val comm = community.trim().ifEmpty { "public" }
            val transport = try {
                if (bindIp != null) {
                    // 绑定局域网接口 IP 的随机 UDP 端口：查询从指定接口发出（多网络适配）
                    DefaultUdpTransportMapping(UdpAddress("$bindIp/0"))
                } else {
                    DefaultUdpTransportMapping()
                }
            } catch (e: Exception) {
                return@withContext SnmpResult(
                    ip = t, community = comm, sysName = null, sysDescr = null,
                    sysUpTime = null, sysLocation = null, sysContact = null,
                    error = "绑定本机接口失败（$bindIp）：${e.message ?: e.javaClass.simpleName}"
                )
            }
            var snmp: Snmp? = null
            try {
                transport.listen()
                snmp = Snmp(transport)
                val target = CommunityTarget<UdpAddress>().apply {
                    address = UdpAddress("$t/$SNMP_PORT")
                    this.community = OctetString(comm)
                    version = SnmpConstants.version2c
                    timeout = timeoutMs
                    retries = 1
                }

                // 一次 GET 全部 OID；整包失败则逐个 OID 单独重试（兼容部分响应）
                val values = mutableMapOf<String, Variable>()
                val bulk = PDU().apply {
                    type = PDU.GET
                    addAll(OID_LIST.map { VariableBinding(OID(it)) })
                }
                val bulkResp = snmp.get(bulk, target)?.response
                if (bulkResp != null && bulkResp.errorStatus == 0) {
                    OID_LIST.forEach { oid -> readValue(bulkResp, oid)?.let { values[oid] = it } }
                } else {
                    OID_LIST.forEach { oid ->
                        runCatching {
                            val single = PDU().apply {
                                type = PDU.GET
                                add(VariableBinding(OID(oid)))
                            }
                            val resp = snmp.get(single, target)?.response
                            if (resp != null && resp.errorStatus == 0) {
                                readValue(resp, oid)?.let { values[oid] = it }
                            }
                        }
                    }
                }

                if (values.isEmpty()) {
                    return@withContext SnmpResult(
                        ip = t, community = comm, sysName = null, sysDescr = null,
                        sysUpTime = null, sysLocation = null, sysContact = null,
                        error = "连接超时：设备可能不支持 SNMP 或 community 不对"
                    )
                }
                SnmpResult(
                    ip = t,
                    community = comm,
                    sysName = values[OID_SYS_NAME]?.toString(),
                    sysDescr = values[OID_SYS_DESCR]?.toString(),
                    sysUpTime = (values[OID_SYS_UP_TIME] as? TimeTicks)?.let { formatUpTime(it.toLong()) },
                    sysLocation = values[OID_SYS_LOCATION]?.toString(),
                    sysContact = values[OID_SYS_CONTACT]?.toString(),
                    error = null
                )
            } catch (e: Exception) {
                SnmpResult(
                    ip = t, community = comm, sysName = null, sysDescr = null,
                    sysUpTime = null, sysLocation = null, sysContact = null,
                    error = "查询失败：${e.message ?: e.javaClass.simpleName}"
                )
            } finally {
                // 用完必须 close，否则 UDP 监听端口泄漏
                runCatching { snmp?.close() }
                runCatching { transport.close() }
            }
        }

    /** 从响应 PDU 按 OID 取值；值为 Null（含 noSuchObject/noSuchInstance）→ null */
    private fun readValue(pdu: PDU, oidStr: String): Variable? {
        val binding = pdu.variableBindings.firstOrNull { it.oid.toString() == oidStr } ?: return null
        val v = binding.variable ?: return null
        return if (v is Null) null else v
    }

    /** TimeTicks（百分之一秒）→ "X天X小时X分" 可读文本 */
    private fun formatUpTime(ticks: Long): String {
        val totalSec = ticks / 100
        val days = totalSec / 86400
        val hours = (totalSec % 86400) / 3600
        val mins = (totalSec % 3600) / 60
        return buildString {
            if (days > 0) append("${days}天")
            if (hours > 0) append("${hours}小时")
            append("${mins}分")
        }
    }
}
