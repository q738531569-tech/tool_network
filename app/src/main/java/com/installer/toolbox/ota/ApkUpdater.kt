package com.installer.toolbox.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * APK 下载与安装（OTA 更新执行器）。
 *
 * 安装策略（root 优先，非 root 兜底）：
 * 1. root 可用（rootRun("id") 输出含 uid=0）：先 cp 到 /data/local/tmp/toolbox_update.apk
 *    （规避 SELinux 对 /data/data 执行限制，见 AGENTS.md 关键坑 6），再 pm install -r -t 静默安装
 * 2. 非 root：FileProvider 分享 APK → 系统安装器（ACTION_INSTALL_PACKAGE）手动确认
 *
 * 网络层用 HttpURLConnection 直连（Proxy.NO_PROXY）+ 120s 读超时；
 * 注：app 模块无法直接引用 okhttp（core-* 模块均为 implementation 不传递），
 * 故未使用 OkHttp，行为等价（直连 + 超时 + 流式下载）。
 */
object ApkUpdater {

    /** 下载目标文件名（cacheDir 下，FileProvider 仅暴露该文件） */
    private const val APK_FILE_NAME = "update.apk"

    /** 下载超时：连接 10s，读取 120s（几十 MB 大文件慢速下载需要） */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 120_000

    /**
     * 下载 APK 到 context.cacheDir/update.apk。
     *
     * @param baseUrl 更新源 base URL（与 version.json 同源，用于拼接相对 apkUrl）
     * @param info 更新信息（apkUrl 支持相对 baseUrl，也支持 http(s) 绝对地址）
     * @param onProgress 进度回调（已下载字节 / 总字节，总字节未知时为 -1；IO 线程触发）
     * @return 下载成功（且 size 校验通过）返回 APK 文件；任何失败返回 null，不抛异常
     */
    suspend fun download(
        context: Context,
        baseUrl: String,
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            // 拼接 APK 地址：apkUrl 为绝对 http(s) 地址时直接用，否则相对 baseUrl
            val apkUrl = if (info.apkUrl.startsWith("http://") || info.apkUrl.startsWith("https://")) {
                info.apkUrl
            } else {
                if (baseUrl.endsWith("/")) "$baseUrl${info.apkUrl}" else "$baseUrl/${info.apkUrl}"
            }

            // 打开连接：NO_PROXY 直连 + 10s 连接 / 120s 读超时（大文件）
            val conn = (URL(apkUrl).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return@withContext null // HTTP 非 2xx 视为失败
                val total = conn.contentLengthLong // 服务器未返回 Content-Length 时为 -1
                val target = File(context.cacheDir, APK_FILE_NAME)

                // 流式写文件：边下载边落盘，避免整包进内存
                var downloaded = 0L
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }

                // 大小校验：info.size > 0 时严格比对，不符视为下载不完整
                if (info.size > 0 && target.length() != info.size) {
                    target.delete()
                    return@withContext null
                }
                target
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null // 网络中断 / IO 异常 / 取消等一律返回 null
        }
    }

    /**
     * 安装 APK：root 静默优先，非 root 系统安装器兜底。
     *
     * @param apk 已下载的 APK 文件（download 的返回值）
     * @param rootRun root 命令执行通道（RootShell.run，null 表示不尝试 root）
     * @return 中文结果提示，不抛异常
     */
    suspend fun install(context: Context, apk: File, rootRun: ((String) -> String)?): String =
        withContext(Dispatchers.IO) {
            // ---- 分支 1：root 静默安装（优先）----
            if (rootRun != null) {
                val rootReady = try {
                    rootRun("id").contains("uid=0") // 确认当前确为 root
                } catch (e: Exception) {
                    false
                }
                if (rootReady) {
                    // 先复制到 /data/local/tmp（SELinux 下 /data/data 内文件安装受限）
                    val cp = rootRun("cp -f ${apk.absolutePath} /data/local/tmp/toolbox_update.apk")
                    if (cp.startsWith("root 执行失败") || cp.startsWith("命令退出码")) {
                        return@withContext "root 复制 APK 失败：$cp"
                    }
                    // pm install -r 覆盖安装，-t 允许测试包
                    val pm = rootRun("pm install -r -t /data/local/tmp/toolbox_update.apk")
                    if (pm.contains("Success")) {
                        return@withContext "已通过 root 安装，重启应用生效"
                    }
                    return@withContext "root 安装失败：$pm"
                }
                // root 不可用：继续走下方非 root 分支
            }

            // ---- 分支 2：非 root，调系统安装器手动确认 ----
            // Android 8+ 调系统安装器需"安装未知应用"权限（REQUEST_INSTALL_PACKAGES），
            // 未开启时 PackageInstaller 会提示"没有权限安装"——先检测并引导去设置开启
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                try {
                    val grant = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.applicationContext.startActivity(grant)
                } catch (e: Exception) {
                    // 部分 ROM 无此设置项，忽略跳转仅返回提示
                }
                return@withContext "未开启「安装未知应用」权限：已跳转系统设置，请允许「装维通」安装应用，再回来重新下载安装"
            }
            try {
                // FileProvider 暴露 cacheDir/update.apk，授权给系统安装器读取
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk
                )
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    // 用 applicationContext 启动（避免依赖 Activity 栈）+ 新任务标志
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.applicationContext.startActivity(intent)
                "已调起系统安装器，请手动确认"
            } catch (e: Exception) {
                "调起系统安装器失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
}
