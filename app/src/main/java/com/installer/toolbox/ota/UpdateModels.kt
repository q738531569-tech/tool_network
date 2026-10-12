package com.installer.toolbox.ota

import org.json.JSONObject

/** 更新源 URL（服务器 OTA 目录，域名 your-server.example:8090，写死） */
const val DEFAULT_UPDATE_URL = "http://your-server.example:8090/toolbox/ota/"

/**
 * OTA 更新信息（对应更新源 version.json 的字段约定）。
 *
 * version.json 约定格式（字段可缺省，缺失按 null/0 处理）：
 * ```json
 * {"latestVersion":"0.1.1","apkUrl":"app-debug.apk","changelog":"本次更新内容","size":61426356,"md5":""}
 * ```
 * - latestVersion：最新版本号（必须非空，否则视为无效）
 * - apkUrl：APK 地址，相对更新源 baseUrl（也支持 http(s) 绝对地址）
 * - changelog：本次更新日志（可空）
 * - size：APK 字节大小（0 = 未知，跳过大小校验）
 * - md5：APK MD5（可空，当前版本不校验，留作后续扩展）
 */
data class UpdateInfo(
    val latestVersion: String,
    val apkUrl: String,
    val changelog: String?,
    val size: Long,
    val md5: String?
)

/**
 * 解析 version.json 文本。
 *
 * 容忍字段缺失：latestVersion / apkUrl 缺失或为空视为无效返回 null；
 * JSON 格式错误、解析异常同样返回 null（不抛异常）。
 */
fun parseUpdateInfo(json: String): UpdateInfo? = try {
    val obj = JSONObject(json)
    val latest = obj.optStringOrNull("latestVersion")
    val apk = obj.optStringOrNull("apkUrl")
    if (latest == null || apk == null) {
        null // 关键字段缺失，视为无效更新信息
    } else {
        UpdateInfo(
            latestVersion = latest,
            apkUrl = apk,
            changelog = obj.optStringOrNull("changelog"),
            // optLong 默认 0；JSONObject.NULL 或缺失时均按 0（未知大小）处理
            size = obj.optLong("size", 0L),
            md5 = obj.optStringOrNull("md5")
        )
    }
} catch (e: Exception) {
    null // JSON 格式错误等一律返回 null
}

/**
 * 容错读取字符串字段：字段缺失 / 值为 JSONObject.NULL / 空白串均返回 null
 * （注意 optString 对 JSONObject.NULL 会返回 "null" 字符串，需显式判空）。
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().ifEmpty { null }
