package com.installer.toolbox.core.agent

import java.io.File

/**
 * Root 命令执行器（Magisk/KernelSU su 通道）。
 *
 * 用于 AI Agent 控制系统级功能（Wi-Fi/蓝牙/移动数据/飞行模式等）。
 * Android 10+ 普通 APP 无法直接改这些系统设置，但 root 通道可以执行
 * `svc` / `settings` 系统命令。仅对已 root 设备生效，非 root 时返回
 * 明确错误信息。
 */
object RootShell {

    /** 主线程 Handler：logListener 回调统一切主线程（run 在 IO 线程执行，
     *  跨线程写 Compose state 会触发 Compose 1.7 "Unsupported concurrent change" 崩溃） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 当前正在执行的进程（停止按钮用：destroy 立即终止长命令，如 tcpdump 抓包） */
    @Volatile
    private var currentProcess: Process? = null

    /** 终止当前正在执行的 root 命令（配合协程取消实现"停止立即生效"） */
    fun cancelCurrent() {
        runCatching { currentProcess?.destroy() }
    }

    /** 日志回调安全转发：listener 为空跳过；切主线程保证 UI state 写入合法 */
    private fun postLog(msg: String) {
        val l = logListener ?: return
        mainHandler.post { runCatching { l.invoke(msg) } }
    }

    /**
     * root 命令执行日志回调（工具过程可视化）：每条命令执行前/后回调，
     * 由 AgentTools.execute 在工具执行期间设置（AI 页实时滚动显示），
     * 其余时间保持 null。回调内不得再调 run（防递归）。
     */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** 检测 root 是否可用（su 可执行且当前为 root） */
    fun isAvailable(): Boolean {
        val out = run("id")
        return out.contains("uid=0") && !out.startsWith("root 执行失败")
    }

    /** su 常见路径（Magisk 通常挂载到 /product/bin 或 /debug_ramdisk） */
    private val SU_CANDIDATES = listOf(
        "/product/bin/su", "/system/bin/su", "/system/xbin/su",
        "/sbin/su", "/debug_ramdisk/su", "/su/bin/su"
    )

    /**
     * 以 root 执行单条命令，返回输出（stdout + stderr 合并，trim 后）。
     * 任何异常/失败返回以"root 执行失败"或"命令退出码"开头的错误文本，
     * 不抛异常。
     */
    fun run(command: String): String {
        val suPath = SU_CANDIDATES.firstOrNull { File(it).exists() }
            ?: run {
                // 过程可视化：无 root 也打印出来（人用页/AI 页都看得到失败原因）
                postLog("└ ✖ 未找到 su（设备未 root 或 Magisk 未安装）")
                return "未找到 su（设备未 root 或 Magisk 未安装）"
            }
        // 工具过程可视化：执行前记录命令（超长截断）
        postLog("┌ 执行：${command.take(120)}${if (command.length > 120) "…" else ""}")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", command))
            currentProcess = process // 记录当前进程：停止按钮 cancelCurrent() 可立即终止长命令
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            currentProcess = null
            val text = (out + "\n" + err).trim()
            val result = if (exit != 0 && text.isBlank()) {
                // 失败文案带上命令本身：过程可见，用户一眼定位是哪条命令退出异常
                val cmdBrief = command.take(60) + if (command.length > 60) "…" else ""
                when (exit) {
                    // 143 = 128+15(SIGTERM)：pkill/kill 命令匹配到自身命令行把 shell 杀了
                    // （应改用 "[x]xxx" 正则写法避免）；137 = 128+9(SIGKILL)
                    143 -> "命令退出码 143（进程被终止：pkill 匹配到自身命令行的典型表现）｜命令：$cmdBrief"
                    137 -> "命令退出码 137（进程被强制杀死）｜命令：$cmdBrief"
                    124 -> "命令退出码 124（执行超时被 timeout 终止）｜命令：$cmdBrief"
                    else -> "命令退出码 $exit（可能未在 Magisk 中授权本应用）｜命令：$cmdBrief"
                }
            } else {
                text.ifBlank { "（命令执行成功，无输出）" }
            }
            postLog(
                if (result.startsWith("root 执行失败") || result.startsWith("命令退出码")) {
                    "└ ✖ $result"
                } else {
                    "└ ✔ 输出 ${result.length} 字" + if (result.isNotBlank()) "：${result.take(120)}" else ""
                }
            )
            result
        } catch (e: Exception) {
            currentProcess = null
            val msg = "root 执行失败：${e.message ?: e.javaClass.simpleName}"
            postLog("└ ✖ $msg")
            msg
        }
    }

    /** 查询系统设置项（settings get global），返回 1/0 或 null */
    private fun getGlobal(name: String): String? {
        val v = run("settings get global $name")
        if (v.startsWith("root 执行失败") || v.startsWith("命令退出码") || v.contains("null")) return null
        return v
    }

    /** 查询当前 Wi-Fi 开关状态（1=开 0=关 null=未知） */
    fun wifiOn(): String? = getGlobal("wifi_on")

    /** 查询当前蓝牙开关状态 */
    fun bluetoothOn(): String? = getGlobal("bluetooth_on")

    /** 查询当前移动数据开关状态 */
    fun mobileDataOn(): String? = getGlobal("mobile_data")

    /** 查询飞行模式状态（1=开 0=关） */
    fun airplaneOn(): String? = getGlobal("airplane_mode_on")

    /** 开启/关闭 Wi-Fi（on=true 开） */
    fun setWifi(on: Boolean): String = run(if (on) "svc wifi enable" else "svc wifi disable")

    /** 开启/关闭蓝牙 */
    fun setBluetooth(on: Boolean): String = run(if (on) "svc bluetooth enable" else "svc bluetooth disable")

    /** 开启/关闭移动数据 */
    fun setMobileData(on: Boolean): String = run(if (on) "svc data enable" else "svc data disable")

    /**
     * 开启/关闭飞行模式。
     * 先写全局设置再广播，触发系统切换。
     */
    fun setAirplaneMode(on: Boolean): String {
        val put = run("settings put global airplane_mode_on ${if (on) 1 else 0}")
        run("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on").let { }
        return put
    }

    /** 汇总系统开关状态（供 AI 工具返回；开头明确 root 权限状态，AI 可直接判断） */
    fun systemStatusSummary(): String {
        if (!isAvailable()) return "未检测到 root 权限（Magisk/KernelSU），无法控制系统开关"
        val wifi = wifiOn()?.let { if (it == "1") "开" else if (it == "0") "关" else "未知" } ?: "未知"
        val bt = bluetoothOn()?.let { if (it == "1") "开" else if (it == "0") "关" else "未知" } ?: "未知"
        val data = mobileDataOn()?.let { if (it == "1") "开" else if (it == "0") "关" else "未知" } ?: "未知"
        val airplane = airplaneOn()?.let { if (it == "1") "开" else if (it == "0") "关" else "未知" } ?: "未知"
        return "已获取 root 权限（Magisk/KernelSU，完整功能可用）；系统开关：Wi-Fi：$wifi；蓝牙：$bt；移动数据：$data；飞行模式：$airplane"
    }
}
