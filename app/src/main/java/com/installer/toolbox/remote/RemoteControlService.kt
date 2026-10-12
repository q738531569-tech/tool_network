package com.installer.toolbox.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.installer.toolbox.ota.ApkUpdater
import com.installer.toolbox.ota.DEFAULT_UPDATE_URL
import com.installer.toolbox.ota.UpdateChecker

/**
 * 远程 AI 控制 · 被控端前台服务（M2：通道 + 远程 AI 会话）。
 *
 * 职责：
 * 1. 前台常驻（通知 channel "remote_control" 低优先级），保持进程存活；
 * 2. OkHttp WebSocket 连服务器 ws://your-server.example:8091/ws；
 * 3. 连接成功后发注册帧 register（设备 ID/名/型号/版本）；
 * 4. 心跳：收到 {"t":"ping"} 回 {"t":"pong"}（另靠 OkHttp 协议层 pingInterval 保 TCP）；
 * 5. 状态上报：注册后发一次 status（电量 / 当前 Wi-Fi 名 / busy=远程会话是否忙）；
 * 6. 断线重连：5/10/20/40/60 秒封顶指数退避；
 * 7. M2 远程 AI 会话：解析 ai_chat / ai_stop / approval_resp 帧接入 [RemoteAiSession]，
 *    会话复用 AgentTools 全工具链，回调转 ai_event / ai_done / approval_req 帧外发；
 * 8. 服务销毁时关 socket、停通知。
 *
 * 未知帧一律忽略。
 */
class RemoteControlService : Service() {

    /** 远程 AI 会话（onCreate 实例化；onSend 直接复用本服务发帧方法） */
    private lateinit var aiSession: RemoteAiSession

    /** 升级/后台任务协程作用域（升级走 IO） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 安装完成后用 AlarmManager 延迟拉起新版本（进程被杀也可靠；2026-08-18 修复 postDelayed 失效） */
    private fun scheduleRestart(delayMs: Long) {
        // 自动重启主机制 = UpdateRestartReceiver（MY_PACKAGE_REPLACED，App 覆盖安装后系统拉起）；
        // 此处 AlarmManager 兜底（receiver 被个别 ROM 拦截时尝试；MIUI 可能限制后台 Activity 启动）
        val intent = Intent(this@RemoteControlService, com.installer.toolbox.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(
            this@RemoteControlService, 0x7755, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
        } catch (e: Exception) {
            try {
                (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                    .set(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
            } catch (e2: Exception) {
                // 仍失败则忽略（用户可手动开 App）
            }
        }
    }

    /** 主线程 Handler：用于重连延迟调度（postDelayed） */
    private val handler = Handler(Looper.getMainLooper())

    /** OkHttp 客户端（懒建，复用；读超时 0 无限 + 协议层 ping 25s 保 TCP） */
    private var client: OkHttpClient? = null

    /** 当前 WebSocket 连接（null = 未连接） */
    private var webSocket: WebSocket? = null

    /** 重连退避次数（连接成功后归零；每失败一次 +1，决定下次延迟档位） */
    @Volatile
    private var reconnectAttempt = 0

    /** 服务是否已销毁（销毁后不再重连） */
    @Volatile
    private var destroyed = false

    /** 是否因注册失败（账号或口令错误）停止（此时保留失败通知，不清除） */
    @Volatile
    private var registerFailed = false

    /** 挂起的重连任务句柄（onDestroy 时移除，避免残留延迟任务触发重连） */
    private var reconnectRunnable: Runnable? = null

    /** 断线期间的 AI 帧缓存（时间戳+JSON，FIFO）：网络抖动/短断线时 ai_event/ai_done 不丢，
     *  重连成功后按序补发（register 之后）；上限 300 帧、超 5 分钟丢弃防陈旧 */
    private val pendingFrames = ArrayDeque<Pair<Long, String>>()

    /** 帧序号：连接代（进程启动时间戳，重启变化）+ 帧内自增——控制端据此丢弃断线补发的重复帧 */
    private val connectEpoch = System.currentTimeMillis()
    private var frameSeq = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 单次回复最大 token 数 + 思考强度（设置页可配）：服务进程独立启动时也要同步到 DeepSeekClient 全局
        val agentPrefs = getSharedPreferences("agent", Context.MODE_PRIVATE)
        com.installer.toolbox.core.agent.DeepSeekClient.maxOutputTokens =
            agentPrefs.getInt("max_output_tokens", 256000)
        com.installer.toolbox.core.agent.DeepSeekClient.reasoningEffort =
            agentPrefs.getString("reasoning_effort", "high") ?: "high"
        // 远程 AI 会话实例化：onSend = { json -> send(json.toString()) }（send 为现有发帧方法）
        aiSession = RemoteAiSession(this) { json -> send(json.toString()) }
        // 三端实时同步：监听 PDA 屏 AI 页的本地轮次事件，转 ai_event/ai_done/approval_req 帧给控制端
        AiSyncBus.remoteListener = { e -> onLocalRoundEvent(e) }
    }

    /** 本地轮次事件（PDA 屏 AI 页发起）→ 转帧外发给控制端（三端实时同步） */
    private fun onLocalRoundEvent(e: AiSyncBus.Event) {
        when (e.kind) {
            "user_msg" -> {
                // 本地发起一轮：控制端收到 user_msg 后置 busy 显示流式占位
                send(
                    JSONObject()
                        .put("t", "ai_event")
                        .put("kind", "user_msg")
                        .put("roundId", "local")
                        .put("data", e.text)
                        .toString()
                )
            }
            "reasoning", "tool_log", "status", "delta" -> {
                send(
                    JSONObject()
                        .put("t", "ai_event")
                        .put("kind", e.kind)
                        .put("roundId", "local")
                        .put("data", e.text)
                        .toString()
                )
            }
            "tool_result" -> {
                send(
                    JSONObject()
                        .put("t", "ai_event")
                        .put("kind", "tool_result")
                        .put("roundId", "local")
                        .put(
                            "data",
                            JSONObject().put("name", e.name).put("input", e.input).put("output", e.output)
                        )
                        .toString()
                )
            }
            "usage" -> {
                send(
                    JSONObject()
                        .put("t", "ai_event")
                        .put("kind", "usage")
                        .put("roundId", "local")
                        .put(
                            "data",
                            JSONObject().put("cacheHitTokens", e.cacheHitTokens).put("cacheMissTokens", e.cacheMissTokens)
                        )
                        .toString()
                )
            }
            "done" -> {
                val frame = JSONObject()
                    .put("t", "ai_done")
                    .put("roundId", "local")
                    .put("text", e.text)
                    .put("roundSec", e.roundSec)
                    .put("userTs", e.userTs)
                if (e.error.isNotBlank()) frame.put("error", e.error)
                send(frame.toString())
            }
            "approval" -> {
                send(
                    JSONObject()
                        .put("t", "approval_req")
                        .put("reqId", e.reqId)
                        .put("toolName", "root_shell")
                        .put("command", e.command)
                        .toString()
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 【开关自检，双保险】远程开关已关闭（prefs enabled=false）则立即自停——
        // 防止 stopService 未生效/系统 START_STICKY 重启/残留启动等任何路径下服务"关不掉"，
        // 出现"App 已关远程但网页仍显示在线可对话"的严重问题
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 前台服务启动后须尽快 startForeground（否则系统 ANR）；通知低优先级常驻
        startForegroundNotification()
        // 已有连接则不重复连；被系统杀死后 START_STICKY 重启时也会走到这里
        if (webSocket == null) {
            connect()
        }
        // START_STICKY：系统回收后自动重启（重启时会先经过上面的 enabled 自检）
        return START_STICKY
    }

    /** 建立前台通知（channel "remote_control" 低优先级 + 常驻通知） */
    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "远程控制", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("🛰 远程控制运行中")
            .setContentText("装维可远程连接本机")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        // targetSdk 34 前台服务必须声明并传入类型（dataSync），见 manifest
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /** 注册失败：更新前台通知文案为失败原因（可划掉，非持续），随后由调用处 stopSelf */
    private fun updateNotificationFail(reason: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "远程控制", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("🛰 远程控制")
            .setContentText("注册失败（$reason）")
            .setOngoing(false)      // 失败通知可划掉
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /** 建立 WebSocket 连接（复用同一 OkHttpClient，避免反复建连接池） */
    private fun connect() {
        if (destroyed) return
        val okClient = client ?: OkHttpClient.Builder()
            // 绕过系统代理：手机常配 127.0.0.1:8080 死代理会拦截外网请求
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS)
            // 读超时 0 = 无限：WebSocket 长连接不能被 10s 默认读超时掐断
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // OkHttp 协议层 ping：每 25s 发一帧 ping 保 TCP 存活（不依赖应用层心跳）
            .pingInterval(25, TimeUnit.SECONDS)
            .build().also { client = it }
        val request = Request.Builder().url(WS_URL).build()
        webSocket = okClient.newWebSocket(request, listener)
    }

    /** 发送一帧 JSON 文本：socket 未连接/已断开时发送失败（OkHttp send 返回 false 静默丢弃）。
     *  ai_event/ai_done 属"过程/结果"帧：打 (epoch, seq) 序号后发送，失败入缓存队列重连补发——
     *  断线瞬间 send 返回 false 但底层缓冲可能仍发出原始帧，补发会产生重复，控制端按序号去重。
     *  其余帧（register/status/pong）断线重连本就会重发，无需缓存。 */
    private fun send(json: String) {
        val stamped = stampSeq(json)
        val ok = try { webSocket?.send(stamped) ?: false } catch (e: Exception) { false }
        if (!ok && (stamped.contains("\"t\":\"ai_event\"") || stamped.contains("\"t\":\"ai_done\""))) {
            synchronized(pendingFrames) {
                if (pendingFrames.size >= PENDING_FRAMES_MAX) pendingFrames.removeFirst()
                pendingFrames.addLast(System.currentTimeMillis() to stamped)
            }
        }
    }

    /** ai_event/ai_done 帧打 (epoch, seq) 序号（其余帧原样返回） */
    private fun stampSeq(json: String): String {
        if (!json.contains("\"t\":\"ai_event\"") && !json.contains("\"t\":\"ai_done\"")) return json
        return try {
            val obj = JSONObject(json)
            obj.put("epoch", connectEpoch)
            obj.put("seq", ++frameSeq)
            obj.toString()
        } catch (e: Exception) {
            json
        }
    }

    /** 连接恢复后补发断线期间缓存的 AI 帧（过滤过期；须在 sendRegister 之后调用，服务器才认设备帧） */
    private fun flushPendingFrames() {
        synchronized(pendingFrames) {
            val now = System.currentTimeMillis()
            while (pendingFrames.isNotEmpty()) {
                val (ts, json) = pendingFrames.first()
                if (now - ts > PENDING_FRAMES_TTL_MS) { pendingFrames.removeFirst(); continue }
                try { webSocket?.send(json) } catch (e: Exception) { /* 再断则下轮补发 */ }
                pendingFrames.removeFirst()
            }
        }
    }

    /** 注册帧：设备上线身份（设备 ID 首次随机生成并持久化，之后复用）+ 归属账号口令 */
    private fun sendRegister() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrBlank()) {
            // 首次生成随机 UUID，存 SharedPreferences "remote"/device_id，重启不变
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        // 归属账号 / 口令：从设置页「装维账号」「口令」读，随 register 帧上报，服务器校验后挂到账号名下
        val account = prefs.getString("account", "") ?: ""
        val pwd = prefs.getString("pwd", "") ?: ""
        val json = JSONObject()
            .put("t", "register")
            .put("deviceId", deviceId)
            // 设备名 = 型号 + "装维通"（控制端设备列表展示用）
            .put("name", Build.MODEL + "装维通")
            // 型号
            .put("model", Build.MODEL)
            // 版本号（app 模块未开 buildConfig，用 PackageManager 读取）
            .put("version", versionName())
            // 归属账号（账号 / 口令为空时服务器按旧设备处理：列表可见但绑定被拒）
            .put("account", account)
            .put("pwd", pwd)
        send(json.toString())
    }

    /** 状态上报帧：电量百分比 + 当前 Wi-Fi 名 + busy（远程或本地 AI 轮次是否正在执行） */
    private fun sendStatus() {
        val json = JSONObject()
            .put("t", "status")
            .put("battery", batteryPercent())
            .put("ssid", currentSsid())
            .put("busy", aiSession.busy || AiSyncBus.localBusy)
        send(json.toString())
    }

    /** 当前电量百分比（BatteryManager 直读，无需权限） */
    private fun batteryPercent(): Int {
        return try {
            val bm = getSystemService(BatteryManager::class.java) ?: return -1
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }

    /** 当前 Wi-Fi 名称（去掉系统自带的双引号；无 Wi-Fi 返回空串）
     *  2026-08-19：远程服务后台运行时定位权限仅"使用时允许"（foreground），
     *  系统 API 读 SSID 返回 <unknown ssid>——有 root 时用 cmd wifi status 兜底直读。 */
    @Suppress("DEPRECATION") // connectionInfo 自 API 31 起废弃，M1 仍用它读 SSID（服务端仅展示）
    private fun currentSsid(): String {
        // 一：标准 API（前台运行时定位权限可用，能直接读到）
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return ""
            val raw = wm.connectionInfo?.ssid ?: ""
            // 系统返回的 SSID 带双引号（如 "TP-LINK_5G"），去掉引号
            val clean = raw.trim('"')
            // 后台权限受限时返回 "<unknown ssid>"，判空降级走 root 兜底
            if (clean.isNotBlank() && !clean.contains("unknown", ignoreCase = true)) return clean
        } catch (e: Exception) {
            // 异常则走 root 兜底
        }
        // 二：root 兜底（后台时定位权限受限，系统 API 读不到；root 下 cmd wifi status 直读系统状态）
        return try {
            val out = com.installer.toolbox.core.agent.RootShell.run("cmd wifi status")
            val m = Regex("WifiInfo: SSID: \"([^\"]+)\"").find(out)
            m?.groupValues?.get(1)?.trim('"') ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** App 版本号（PackageManager 读取；app 模块未开 buildConfig，无 BuildConfig.VERSION_NAME） */
    private fun versionName(): String = try {
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .versionName ?: "未知"
    } catch (e: Exception) {
        "未知"
    }

    /** 断线重连：5/10/20/40/60 秒封顶指数退避 */
    private fun scheduleReconnect() {
        if (destroyed || registerFailed) return // 注册失败后不再重连（等用户改账号重新开启）
        // 退避档位：第 0 次失败 5s → 1 次 10s → 2 次 20s → 3 次 40s → 4 次及以后 60s 封顶
        val delaysMs = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
        val delayMs = delaysMs[minOf(reconnectAttempt, delaysMs.size - 1)]
        reconnectAttempt++
        // 移除旧的挂起重连任务，避免叠加
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { connect() }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /** WebSocket 回调（在 OkHttp 内部线程触发，非主线程；UI 无关，纯逻辑） */
    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 连接成功：重置退避，先注册设备身份，再补发断线期间缓存的 AI 帧 + 一次状态上报
            reconnectAttempt = 0
            sendRegister()
            flushPendingFrames()
            sendStatus()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 解析帧类型并分发：心跳 ping 回 pong；M2 起接入远程 AI 会话
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("t")) {
                "ping" -> send("""{"t":"pong"}""")
                "register_fail" -> {
                    // 注册失败（账号或密码错误）：更新通知文案后停止服务（设备端提示）
                    registerFailed = true
                    // 同步重置开关状态，避免设置页开关残留「已开启」假象
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean("enabled", false).apply()
                    updateNotificationFail(json.optString("reason"))
                    // 先摘前台状态再停止（确保服务彻底退出，不留"在线"假象）
                    ServiceCompat.stopForeground(this@RemoteControlService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
                "ai_chat" -> aiSession.handleChat(
                    json.optString("roundId"),
                    json.optString("text"),
                    json.optBoolean("authorized")
                )
                "ai_stop" -> {
                    // 三端同步路由：本地轮次（PDA 屏发起）进行中 → bus 通知 AiViewModel 停止；
                    // 否则按远程轮次原逻辑停止
                    if (AiSyncBus.localBusy) {
                        AiSyncBus.publish(AiSyncBus.Event(kind = "stop"))
                    } else {
                        aiSession.handleStop(json.optString("roundId"))
                    }
                }
                "approval_resp" -> {
                    // 双发：远程轮次审批（RemoteAiSession 找不到 reqId 时静默忽略）；
                    // 本地轮次审批（AiViewModel 监听 bus，无对应 reqId 也静默）
                    aiSession.handleApprovalResp(json.optString("reqId"), json.optBoolean("allow"))
                    AiSyncBus.publish(
                        AiSyncBus.Event(kind = "approval_resp", reqId = json.optString("reqId"), allow = json.optBoolean("allow"))
                    )
                }
                // 会话管理（控制端 → 设备，改的是同一份 chat_sessions）
                "session_list" -> aiSession.handleSessionList()
                "session_switch" -> aiSession.handleSessionSwitch(json.optString("id"), json.optLong("beforeTs", 0L))
                "session_new" -> aiSession.handleSessionNew()
                "session_clear" -> aiSession.handleSessionClear(json.optString("id"))
                "session_delete" -> aiSession.handleSessionDelete(json.optString("id"))
                "session_rename" -> aiSession.handleSessionRename(
                    json.optString("id"),
                    json.optString("title")
                )
                // 记忆同步（控制端查看/单删/清空当前会话记忆，与 App 🧠 弹窗同一份存储）
                "memory_list" -> aiSession.handleMemoryList()
                "memory_delete" -> aiSession.handleMemoryDelete(json.optString("text"))
                "memory_clear" -> aiSession.handleMemoryClear()
                // 控制端重连后请求重发未决审批（断线丢 approval_req 的恢复）
                "pending_approval_query" -> aiSession.handlePendingApprovalQuery()
                // 删除一轮对话（控制端 → 设备：该轮不再进入 AI 上下文）
                "round_delete" -> aiSession.handleRoundDelete(json.optString("id"), json.optLong("ts", 0L))
                // 远程强制升级（控制端 → 设备：检查更新+下载+root 静默安装+重启应用）
                "app_update" -> handleAppUpdate(json.optString("action", "check_and_install"))
                // 其余未知帧忽略
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 连接失败/异常断开：进入指数退避重连
            scheduleReconnect()
        }

    /**
     * 远程强制升级（2026-08-18 用户需求）：控制端发 app_update 帧 →
     * 检查更新源 → 有新版本则下载 APK → root 静默安装 → 重启应用。
     * 安全保护：设备正在执行任务（AI 轮次 busy）时不安装，回执提示由控制端决定。
     */
    private fun handleAppUpdate(action: String) {
        // 【用户拍板：PDA 在执行任务先别安装】本地或远程 AI 轮次进行中 → 拒绝并回执
        if (aiSession.busy || AiSyncBus.localBusy) {
            send("""{"t":"app_update_resp","ok":false,"reason":"设备正在执行任务，暂不升级，请稍后再试"}""")
            return
        }
        scope.launch {
            try {
                val base = DEFAULT_UPDATE_URL
                val check = UpdateChecker.checkUpdate(base)
                val info = check.getOrNull()
                if (info == null) {
                    val err = check.exceptionOrNull()?.message ?: "未知错误"
                    send("""{"t":"app_update_resp","ok":false,"reason":"检查更新失败：$err"}""")
                    return@launch
                }
                val cur = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                }.getOrDefault("0")
                val needUpgrade = action == "force" || UpdateChecker.isNewer(cur, info.latestVersion)
                if (!needUpgrade) {
                    send("""{"t":"app_update_resp","ok":false,"reason":"已是最新版本 $cur"}""")
                    return@launch
                }
                send("""{"t":"app_update_resp","ok":true,"phase":"downloading","version":"${info.latestVersion}"}""")
                val apk = ApkUpdater.download(this@RemoteControlService, base, info) { _, _ -> }
                if (apk == null) {
                    send("""{"t":"app_update_resp","ok":false,"reason":"APK 下载失败"}""")
                    return@launch
                }
                send("""{"t":"app_update_resp","ok":true,"phase":"installing","version":"${info.latestVersion}"}""")
                // 【重启可靠性 2026-08-18】root 静默安装会在 pm install 成功瞬间杀进程（含本服务），
                // **安装前**设好重启（nohup 后台：10 秒后 am start 拉起新版本，不阻塞 install 立即执行）；
                // 非 root（走系统安装器）不设（用户手动确认，无需重启）
                val rootOk = com.installer.toolbox.core.agent.RootShell.isAvailable()
                if (rootOk) scheduleRestart(10000)
                val result = ApkUpdater.install(
                    this@RemoteControlService, apk,
                    com.installer.toolbox.core.agent.RootShell::run
                )
                if (result.startsWith("已通过 root 安装")) {
                    send("""{"t":"app_update_resp","ok":true,"phase":"done","version":"${info.latestVersion}","reason":"已安装，正在重启应用"}""")
                } else {
                    send("""{"t":"app_update_resp","ok":false,"reason":"安装失败：$result"}""")
                }
            } catch (e: Exception) {
                send("""{"t":"app_update_resp","ok":false,"reason":"升级异常：${e.message ?: "未知错误"}"}""")
            }
        }
    }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // 正常关闭或对端关闭：进入重连（服务销毁时 destroyed=true 会拦截）
            scheduleReconnect()
        }
    }

    override fun onDestroy() {
        destroyed = true
        AiSyncBus.remoteListener = null   // 注销三端同步监听
        // 移除挂起的重连任务，避免销毁后仍触发连接
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        // 关闭 socket（正常关闭 code 1000；onClosed 回调会因 destroyed=true 被拦截）
        webSocket?.close(1000, "service stopped")
        webSocket = null
        client = null
        // 停通知：注册失败时保留失败通知（DETACH 仅摘前台状态不清通知），其余情况移除
        if (registerFailed) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    companion object {
        /** 通知渠道 ID（低优先级，静默常驻） */
        private const val CHANNEL_ID = "remote_control"

        /** 前台通知 ID（区别于抓包服务 1001） */
        private const val NOTIFICATION_ID = 1002

        /** SharedPreferences 文件名（设备 ID / 开关状态共用） */
        private const val PREFS_NAME = "remote"

        /** 服务器 WebSocket 地址（88 服务器 8091 中转服务） */
        private const val WS_URL = "ws://your-server.example:8091/ws"

        /** 断线 AI 帧缓存上限（一轮 AI 过程帧约几十帧，300 足够覆盖数轮） */
        private const val PENDING_FRAMES_MAX = 300

        /** 缓存帧有效期（5 分钟超时丢弃，避免补发陈旧过程帧） */
        private const val PENDING_FRAMES_TTL_MS = 5 * 60 * 1000L
    }
}
