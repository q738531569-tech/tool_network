package com.installer.toolbox.core.network.scan

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * nmap 深度扫描器。
 *
 * **内置 nmap（推荐）**：APK 内置静态编译的 arm64 nmap（assets/nmap + assets/nmap_data），
 * 由 [BuiltinTools] 统一解压并 root 复制到 /data/local/tmp 执行，命令带 `--datadir`
 * 指向内置数据目录——无需 Termux。
 * **降级**：若内置不可用，尝试 Termux 安装的 nmap（root 执行）。
 *
 * 执行用 `timeout N nmap ...` 包裹，防止扫描卡死阻塞调用方（RootShell.run 无超时参数）。
 * 输出保留原始文本（截断 [MAX_RAW]），另尽力解析 PORT/STATE/SERVICE/VERSION 列表。
 */
object NmapScanner {

    /** Termux 中 nmap 的固定路径 */
    private const val TERMUX_NMAP = "/data/data/com.termux/files/usr/bin/nmap"

    /** 原始输出保留上限（防撑爆 Agent 上下文 / UI 内存） */
    private const val MAX_RAW = 8000

    /** 端口行：`22/tcp   open  ssh  OpenSSH 8.2p1 ...`（service 允许 ssl/http 这类带斜杠的名字） */
    private val PORT_LINE = Regex("""^(\d+)/tcp\s+(\w+)\s+(\S+)(?:\s+(.*))?$""")

    /** 版本号：从剩余文本里找第一个以数字开头的 token，如 8.2p1 / 1.18.0 */
    private val VERSION_RE = Regex("""\b(\d[\d.]*(?:[a-zA-Z][\w.-]*)?)""")

    /** 单条端口结果 */
    data class NmapPort(val port: Int, val state: String, val service: String?, val version: String?)

    /** 完整扫描结果：raw 保留原始输出，ports 为解析出的开放端口列表 */
    data class NmapResult(val raw: String, val ports: List<NmapPort>, val note: String?)

    /**
     * 检查 Termux 是否已安装 nmap。
     * 必须用 root 通道检查——APP 进程无权限读取 Termux 私有目录
     * （/data/data/com.termux/），直接 File.exists() 永远返回 false。
     */
    fun isTermuxNmapAvailable(rootRun: ((String) -> String)? = null): Boolean {
        if (rootRun == null) return false
        return rootRun("sh -c 'test -x $TERMUX_NMAP && echo NMAP_OK'").contains("NMAP_OK")
    }

    /**
     * 执行 nmap 深度扫描（运行在 IO 线程）。
     * 优先内置 nmap（APK 自带，无需 Termux）；内置不可用时降级 Termux nmap。
     *
     * @param context 用于解压内置 nmap（assets → filesDir）
     * @param target 目标 IP 或域名，必填
     * @param options nmap 参数，默认 `-Pn -sV --top-ports 100 --open`（-Pn 跳过主机发现，
     * 直接扫端口——网关/设备常拒绝 ping 探测，不加会误报 host down）
     * @param timeoutSec 超时秒数，用 `timeout` 命令包裹执行
     * @param rootRun root 命令执行通道（如 RootShell::run）；null 时无法执行
     */
    suspend fun scan(
        context: Context,
        target: String,
        options: String = "-Pn -sV --top-ports 100 --open",
        timeoutSec: Long = 60,
        rootRun: ((String) -> String)? = null
    ): NmapResult = withContext(Dispatchers.IO) {
        if (target.isBlank()) {
            return@withContext NmapResult("", emptyList(), "参数错误：缺少目标 IP 或域名")
        }
        if (rootRun == null) {
            return@withContext NmapResult(
                "", emptyList(),
                "未提供 root 执行通道，无法运行 nmap 深度扫描"
            )
        }
        // ① 内置 nmap 优先：解压 + root 复制到可执行分区 + 准备 nmap_data（--datadir）
        val internalPath = BuiltinTools.nmap(context, rootRun)
        if (internalPath != null) {
            return@withContext runNmap(
                rootRun,
                "$internalPath --datadir ${BuiltinTools.RUN_DATA_DIR} $options $target",
                timeoutSec, "内置 nmap"
            )
        }
        // ② 降级：Termux nmap
        if (isTermuxNmapAvailable(rootRun)) {
            return@withContext runNmap(rootRun, "$TERMUX_NMAP $options $target", timeoutSec, "Termux nmap")
        }
        NmapResult(
            "", emptyList(),
            "内置 nmap 初始化失败且未检测到 Termux nmap（APK 应已内置，重装或检查 assets）"
        )
    }

    /** 执行 nmap 并解析结果（source 用于提示来源）
     *  LD_LIBRARY_PATH 指到 /data/local/tmp：内置 nmap 动态链接 libc++_shared.so，
     *  系统不内置该库（现场曾报 CANNOT LINK EXECUTABLE "nmap_bin"），BuiltinTools 已复制同 NDK 版本库过去 */
    private fun runNmap(
        rootRun: (String) -> String,
        cmd: String,
        timeoutSec: Long,
        source: String
    ): NmapResult {
        val out = rootRun("timeout $timeoutSec env LD_LIBRARY_PATH=${BuiltinTools.RUN_LIBCPP_DIR} $cmd")
        if (out.contains("Permission denied") || out.contains("not found") ||
            out.contains("cannot execute") || out.startsWith("root 执行失败") ||
            out.startsWith("命令退出码")
        ) {
            return NmapResult(
                out.take(MAX_RAW), emptyList(),
                "$source 执行失败：${out.take(300)}"
            )
        }
        val ports = parsePorts(out)
        val note = if (ports.isEmpty()) {
            "扫描完成（$source）但未解析到开放端口（可尝试去掉 --open 或扩大端口范围）"
        } else {
            "解析到 ${ports.size} 个开放端口（$source）"
        }
        return NmapResult(out.take(MAX_RAW), ports, note)
    }

    /**
     * root 下检查内置 nmap 是否可用（UI 提示用）。
     * 内部会解压 + root 复制（耗时），应在 IO 线程调用（本函数已切 Dispatchers.IO）。
     */
    suspend fun isBuiltinNmapAvailable(context: Context, rootRun: ((String) -> String)?): Boolean =
        withContext(Dispatchers.IO) {
            if (rootRun == null) false else BuiltinTools.nmap(context, rootRun) != null
        }

    /** 解析 nmap 输出中的端口行（尽力提取端口/状态/服务/版本） */
    private fun parsePorts(out: String): List<NmapPort> {
        val ports = mutableListOf<NmapPort>()
        out.lineSequence().forEach { line ->
            val m = PORT_LINE.find(line) ?: return@forEach
            val port = m.groupValues[1].toIntOrNull() ?: return@forEach
            val state = m.groupValues[2]
            val service = m.groupValues[3]
            val rest = m.groupValues[4].trim()
            val version = VERSION_RE.find(rest)?.groupValues?.get(1)
            ports.add(NmapPort(port, state, service, version))
        }
        return ports
    }
}
