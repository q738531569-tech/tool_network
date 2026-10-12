package com.installer.toolbox.core.network.scan

import android.content.Context
import java.io.File

/**
 * 内置 root 依赖工具统一管理（assets → filesDir → /data/local/tmp）。
 *
 * APK assets 内置 4 个 arm64 产物：
 * - assets/nmap：静态编译 nmap（约 13MB），运行需带 `--datadir` 指向内置 nmap_data
 * - assets/nmap_data/：nmap 数据（nmap-os-db / nmap-service-probes / nmap-mac-prefixes / scripts/ 等）
 * - assets/tcpdump：静态编译 tcpdump（约 5.4MB）
 * - assets/arpspoof：自研 C arpspoof（约 1.2MB）
 *
 * 执行链路（已真机验证）：
 * 1. 解压：assets → context.filesDir（APP 私有目录，首次或长度异常时重解压）；
 * 2. root 复制：/data/data 下执行会被 SELinux 拒绝（not executable），
 *    必须 root 复制到 /data/local/tmp/toolbox_<tag> 并 chmod 755 后运行。
 *
 * 所有步骤 try/catch：失败返回 null 或降级值（系统 tcpdump / Termux nmap），
 * 调用方负责降级，不抛异常、不崩溃。root 命令通道由调用方注入（如 RootShell::run），
 * 本模块不直接依赖 core-agent，避免 core-network ← core-agent 反向依赖。
 */
object BuiltinTools {

    /** 内置 nmap 二进制 assets 路径 */
    private const val ASSET_NMAP = "nmap"

    /**
     * NDK libc++ 动态库 assets 路径（内置 nmap 为动态链接 libc++_shared.so，系统不内置该库；
     * 现场反馈 CANNOT LINK EXECUTABLE "nmap_bin": library "libc++_shared.so" not found）。
     * 运行时复制到 /data/local/tmp/libc++_shared.so，nmap 执行前用 LD_LIBRARY_PATH 指过去。
     */
    private const val ASSET_LIBCPP = "libc++_shared.so"

    /** libc++ 运行路径（与 nmap 同放 /data/local/tmp，LD_LIBRARY_PATH 指此目录） */
    const val RUN_LIBCPP_PATH = "/data/local/tmp/libc++_shared.so"

    /** LD_LIBRARY_PATH 指向的目录（/data/local/tmp） */
    const val RUN_LIBCPP_DIR = "/data/local/tmp"

    /** 内置 tcpdump 二进制 assets 路径 */
    private const val ASSET_TCPDUMP = "tcpdump"

    /** 内置 arpspoof 二进制 assets 路径 */
    private const val ASSET_ARPSPOOF = "arpspoof"

    /** 内置 nmap 数据目录 assets 路径 */
    private const val ASSET_DATA = "nmap_data"

    /** root 运行目录前缀：/data/local/tmp/toolbox_<tag>（可执行分区，不受 SELinux 限制） */
    private const val RUN_DIR = "/data/local/tmp/toolbox_"

    /** 内置 nmap 数据目录在 /data/local/tmp 下的运行路径（nmap 执行带 `--datadir` 用） */
    const val RUN_DATA_DIR = "$RUN_DIR$ASSET_DATA"

    /**
     * 从 assets 解压单文件到 filesDir/<fileTag>（首次或长度异常时重解压）。
     *
     * @param context 用于访问 assets 与 filesDir
     * @param assetName assets 中的文件名（如 "nmap"）
     * @param fileTag filesDir 下的目标文件名（如 "nmap_bin"）
     * @return 本地文件绝对路径；失败返回 null
     */
    fun extract(context: Context, assetName: String, fileTag: String): String? {
        return try {
            val dest = File(context.filesDir, fileTag)
            val expected = assetLength(context, assetName)
            // 文件缺失或长度与 assets 不一致时重新解压（拿不到 assets 长度则只判缺失）
            if (!dest.exists() || (expected != null && dest.length() != expected)) {
                context.assets.open(assetName).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setExecutable(true, false)
            }
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 递归解压 assets 目录到 filesDir/<tag>（nmap_data 用）。
     * 用 `.complete` 标记文件判断解压是否完整，防止半截目录被当作可用。
     *
     * @param context 用于访问 assets 与 filesDir
     * @param assetDir assets 中的目录名（如 "nmap_data"）
     * @param tag filesDir 下的目标目录名
     * @return 本地目录绝对路径；失败返回 null
     */
    fun extractDir(context: Context, assetDir: String, tag: String): String? {
        return try {
            val destDir = File(context.filesDir, tag)
            val marker = File(destDir, ".complete")
            if (!destDir.exists() || !marker.exists()) {
                destDir.deleteRecursively()
                copyAssetDir(context, assetDir, destDir)
                marker.writeText("ok")
            }
            destDir.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * root 下把本地文件复制到 /data/local/tmp/toolbox_<tag> 并 chmod 755。
     * 用 `cmp -s` 先比对：内容一致时跳过复制（正在执行的二进制文件被 cp 覆盖会报
     * "Text file busy"——静默/审计等进程运行时二次调用本方法不再失败）。
     *
     * @param rootRun root 命令执行通道（如 RootShell::run）
     * @param localPath filesDir 下的本地文件路径
     * @param tag 运行文件名（如 "nmap"），拼成 /data/local/tmp/toolbox_<tag>
     * @return root 运行路径（如 /data/local/tmp/toolbox_nmap）；失败返回 null
     */
    fun prepareForRun(rootRun: (String) -> String, localPath: String, tag: String): String? {
        val runPath = "$RUN_DIR$tag"
        val out = try {
            rootRun("sh -c 'cmp -s $localPath $runPath || cp $localPath $runPath 2>/dev/null; chmod 755 $runPath && echo TOOL_READY'")
        } catch (e: Exception) {
            return null
        }
        return if (out.contains("TOOL_READY")) runPath else null
    }

    /**
     * 准备内置 nmap：解压二进制 + nmap_data 数据目录，root 复制到 /data/local/tmp。
     * 执行时需带 `--datadir ${RUN_DATA_DIR}`（内置数据：os-db / service-probes / scripts 等）。
     *
     * @return nmap 运行路径（如 /data/local/tmp/toolbox_nmap）；任一环节失败返回 null
     */
    fun nmap(context: Context, rootRun: (String) -> String): String? {
        // ① 二进制：解压 + root 复制到可执行分区
        val bin = extract(context, ASSET_NMAP, "nmap_bin") ?: return null
        val runPath = prepareForRun(rootRun, bin, "nmap") ?: return null
        // ② 依赖库 libc++_shared.so：root 复制到 /data/local/tmp（动态链接器经 LD_LIBRARY_PATH 加载）
        val libBin = extract(context, ASSET_LIBCPP, "libcpp_shared") ?: return null
        val libOut = try {
            rootRun(
                "sh -c 'cmp -s $libBin $RUN_LIBCPP_PATH || cp $libBin $RUN_LIBCPP_PATH 2>/dev/null; " +
                    "chmod 644 $RUN_LIBCPP_PATH && echo LIB_READY'"
            )
        } catch (e: Exception) {
            return null
        }
        if (!libOut.contains("LIB_READY")) return null
        // ③ 数据目录：解压到 filesDir + root 复制到 /data/local/tmp/toolbox_nmap_data
        val dataDir = extractDir(context, ASSET_DATA, "nmap_data") ?: return null
        if (!prepareDataDir(rootRun, dataDir)) return null
        return runPath
    }

    /**
     * 准备内置 tcpdump（内置优先，系统 tcpdump 兜底）。
     *
     * @return 内置运行路径（如 /data/local/tmp/toolbox_tcpdump）；
     *         内置不可用时返回 "tcpdump"（表示走 root PATH 里的系统 tcpdump）；rootRun 为空返回 null
     */
    fun tcpdump(context: Context, rootRun: ((String) -> String)?): String? {
        if (rootRun == null) return null
        val bin = extract(context, ASSET_TCPDUMP, "tcpdump_bin") ?: return "tcpdump"
        return prepareForRun(rootRun, bin, "tcpdump") ?: "tcpdump"
    }

    /**
     * 准备内置 arpspoof。
     *
     * @return 运行路径（如 /data/local/tmp/toolbox_arpspoof）；失败返回 null
     */
    fun arpspoof(context: Context, rootRun: ((String) -> String)?): String? {
        if (rootRun == null) return null
        val bin = extract(context, ASSET_ARPSPOOF, "arpspoof_bin") ?: return null
        return prepareForRun(rootRun, bin, "arpspoof")
    }

    /** 内置 deauth 二进制 assets 路径 */
    private const val ASSET_DEAUTH = "deauth"

    /**
     * 准备内置 deauth（802.11 强制踢下线）。
     *
     * @return 运行路径（如 /data/local/tmp/toolbox_deauth）；失败返回 null
     */
    fun deauth(context: Context, rootRun: ((String) -> String)?): String? {
        if (rootRun == null) return null
        val bin = extract(context, ASSET_DEAUTH, "deauth_bin") ?: return null
        return prepareForRun(rootRun, bin, "deauth")
    }

    /** 内置 ndpspoof 二进制 assets 路径 */
    private const val ASSET_NDPSPOOF = "ndpspoof"

    /**
     * 准备内置 ndpspoof（IPv6 NDP 欺骗，配合 arpspoof 做 IPv6 双重断网）。
     *
     * @return 运行路径（如 /data/local/tmp/toolbox_ndpspoof）；失败返回 null
     */
    fun ndpspoof(context: Context, rootRun: ((String) -> String)?): String? {
        if (rootRun == null) return null
        val bin = extract(context, ASSET_NDPSPOOF, "ndpspoof_bin") ?: return null
        return prepareForRun(rootRun, bin, "ndpspoof")
    }

    // ---- 内部辅助 ----

    /**
     * assets 单文件字节数：优先 openFd 精确长度；
     * 压缩 asset 无法 openFd 时退化为 available() 近似值；都失败返回 null。
     */
    private fun assetLength(context: Context, assetName: String): Long? {
        return try {
            context.assets.openFd(assetName).use { it.length }
        } catch (e: Exception) {
            try {
                context.assets.open(assetName).use { it.available().toLong() }
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * 递归复制 assets 目录到本地目录。
     * assets.list 对文件返回 null、对目录返回子项数组（空目录返回空数组）。
     */
    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath) ?: return
        destDir.mkdirs()
        children.forEach { name ->
            val childAsset = "$assetPath/$name"
            val childDest = File(destDir, name)
            // 注意：assets.list(文件) 返回空数组而非 null，须用 isNotEmpty() 判目录
            val sub = context.assets.list(childAsset)
            if (sub != null && sub.isNotEmpty()) {
                copyAssetDir(context, childAsset, childDest)
            } else {
                context.assets.open(childAsset).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** root 下把本地 nmap 数据目录复制到 /data/local/tmp/toolbox_nmap_data 并放开读取权限；成功返回 true */
    private fun prepareDataDir(rootRun: (String) -> String, localDir: String): Boolean {
        val out = try {
            rootRun(
                "sh -c 'rm -rf $RUN_DATA_DIR 2>/dev/null; " +
                    "cp -r $localDir $RUN_DATA_DIR 2>/dev/null && " +
                    "chmod -R 755 $RUN_DATA_DIR && echo DATA_READY'"
            )
        } catch (e: Exception) {
            return false
        }
        return out.contains("DATA_READY")
    }
}
