package com.installer.toolbox.core.network.scan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * IDC / 数据中心目标 IP 检测。
 *
 * 背景（装维经验）：现代 PCDN 设备普遍伪装流量特征，运营商检测 PCDN 的
 * 关键手段之一是**看流量目的 IP 是否指向 IDC（数据中心）机房**——P2P CDN
 * 的节点服务器大多部署在 IDC。因此统计设备上行流量的目的 IP，判断其
 * 是否属于 hosting/IDC 网络，是识别 PCDN 的高价值信号。
 *
 * 实现：调用 ip-api.com 免费批量接口（type=hosting 表示数据中心；org 含
 * 机房/IDC/Cloud 关键词也判为 IDC）。免费版限流约 45 次/分钟、单次批量
 * ≤100 IP；局域网 IP 不查询。
 */
object IdcTargetDetector {

    /** 单个 IP 的归属判定结果 */
    data class IdcResult(
        val ip: String,
        /** 是否疑似 IDC/数据中心 */
        val isIdc: Boolean,
        /** 归属组织（如 AS15169 Google LLC） */
        val org: String?,
        /** 类型（isp/hosting/business 等） */
        val type: String?
    )

    private val client by lazy {
        OkHttpClient.Builder()
            // 外网 API 需绕过手机死代理（与 DeepSeek 相同处理）
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /**
     * IDC/数据中心关键词（org/isp 命中即判 IDC）。
     * **收紧原则**：只保留机房托管类词——之前把 cloud/alibaba/aliyun/tencent cloud 等
     * 云厂商也算 IDC，导致刷抖音/看视频（上行连字节云/CDN）被误判 +3。
     * 云服务 ≠ IDC 机房：正常 App 普遍连云，只有托管/数据中心才是 PCDN 调度特征。
     * 主判定仍是 ip-api 的 type=hosting（机房托管），关键词仅作补充。
     */
    private val IDC_KEYWORDS = listOf(
        "data center", "datacenter", "hosting", "idc", "机房", "colocation", "dedicated server"
    )

    /**
     * 批量查询 IP 归属并判定是否 IDC。
     * @param ips 待查询 IP（自动过滤局域网/环回/组播/广播）
     * @return 判定结果列表（仅含成功查询的；失败/超时的 IP 不返回）
     */
    suspend fun classify(ips: List<String>): List<IdcResult> = withContext(Dispatchers.IO) {
        val targets = ips.distinct().filter { isPublicIpv4(it) }
        if (targets.isEmpty()) return@withContext emptyList()
        // 分批（每批 100），免费接口限制
        val results = mutableListOf<IdcResult>()
        targets.chunked(100).forEach { batch ->
            try {
                val body = JSONArray().apply {
                    batch.forEach { ip -> put(JSONObject().put("query", ip)) }
                }.toString()
                val request = Request.Builder()
                    .url("http://ip-api.com/batch?fields=query,status,org,isp,type")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            if (o.optString("status") != "success") continue
                            val ip = o.optString("query")
                            val org = o.optString("org").ifBlank { o.optString("isp") }
                            val type = o.optString("type")
                            results.add(
                                IdcResult(
                                    ip = ip,
                                    isIdc = type == "hosting" || IDC_KEYWORDS.any { org.contains(it, ignoreCase = true) },
                                    org = org.ifBlank { null },
                                    type = type.ifBlank { null }
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // 单批失败跳过（网络/限流），不阻塞整体
            }
        }
        results
    }

    /** 是否为可查询的公网 IPv4（排除内网/环回/组播/保留） */
    private fun isPublicIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val n = parts.mapNotNull { it.toIntOrNull() }
        if (n.size != 4 || n.any { it !in 0..255 }) return false
        val first = n[0]
        return when {
            first == 0 -> false                       // 0.0.0.0/8
            first == 10 -> false                      // 10.0.0.0/8 内网
            first == 127 -> false                     // 环回
            first == 169 && n[1] == 254 -> false      // 链路本地
            first == 172 && n[1] in 16..31 -> false   // 172.16/12 内网
            first == 192 && n[1] == 168 -> false      // 192.168/16 内网
            first >= 224 -> false                     // 组播/保留
            else -> true
        }
    }
}
