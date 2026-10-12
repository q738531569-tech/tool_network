package com.installer.toolbox.ui.screens.remote

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 远程控制页三阶段状态机。
 *
 * 页面从登录 → 设备列表 → 控制台逐级推进；退回用导航返回键（ViewModel 随页面销毁时 onCleared 关 socket）。
 */
enum class RemotePhase { LOGIN, DEVICE_LIST, CONSOLE }

/** 设备列表项（device_list 帧解析结果） */
data class RemoteDevice(
    val deviceId: String,
    val name: String,
    val model: String,
    val account: String,
    val online: Boolean,
    val battery: Int,
    val ssid: String,
    val busy: Boolean
)

/** 会话项（session_list_resp 帧解析结果） */
data class RemoteSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val count: Int
)

/** 单条工具调用记录（思考/工具折叠卡内容） */
data class RemoteToolCard(
    val name: String,
    val input: String = "",
    val output: String = ""
)

/** 单条消息（user 右气泡 / assistant 左气泡带思考·工具·耗时 / sys 居中系统提示） */
data class RemoteMsg(
    val id: Long,
    val role: String,
    val text: String = "",
    val ts: Long = System.currentTimeMillis(),
    /** assistant 思考链（流式累积，历史默认折叠） */
    val reasoning: String = "",
    /** assistant 轮次内的工具调用记录 */
    val toolCards: List<RemoteToolCard> = emptyList(),
    /** 本轮耗时（秒，assistant 完成时写入） */
    val roundSec: Long = 0,
    /** assistant 是否已完成（false = 流式进行中） */
    val done: Boolean = true,
    /** assistant 是否出错（error 文案红色显示） */
    val error: Boolean = false,
    /** 流式中「正在调用工具…」状态行文本 */
    val statusText: String = "",
    /** 状态行开始时间（实时计时用） */
    val statusStart: Long = 0,
    /** 该轮 user 消息的落盘 ts（历史回放/ai_done 后填充；0=未知，未完成轮不显示删除入口） */
    val roundTs: Long = 0
)

/** 待审批请求（root_shell 命令审批，120 秒倒计时） */
data class RemoteApproval(
    val reqId: String,
    val toolName: String,
    val command: String,
    val deadline: Long
)

/** 当前会话的一条记忆（memory_list_resp items） */
data class RemoteMemoryItem(
    val text: String,
    val ts: Long
)

/**
 * 远程控制页 ViewModel：持有 OkHttp WebSocket 连接与全部页面状态。
 *
 * 设计要点：
 * 1. 用普通 [ViewModel]（无参构造）而非 AndroidViewModel——WebSocket 不依赖 Context，
 *    经 `viewModel()` 挂在 NavBackStackEntry 上，页面旋转/重组不丢连接；
 * 2. WebSocket 回调跑在 OkHttp 内部线程，统一 `handler.post` 回主线程再改状态，
 *    保证所有 Compose 快照状态（mutableStateOf / mutableStateListOf）都在主线程写；
 * 3. 断线重连：5/10/20/40/60 秒封顶指数退避；重连成功后补发 login → bind + session_list；
 * 4. 历史瀑布流：session_switched 首屏最近 5 条；带 beforeTs 分页取更早 5 条前插，
 *    reverseLayout 列表下前插自动保持视口位置（无需手动 scrollState 调整）。
 */
class RemoteControlViewModel : ViewModel() {

    // ==================== 阶段与连接状态 ====================

    /** 页面阶段（LOGIN 登录 → DEVICE_LIST 设备列表 → CONSOLE 控制台） */
    var phase by mutableStateOf(RemotePhase.LOGIN)
        private set

    /** 是否正在登录/注册建连（登录页按钮转圈） */
    var connecting by mutableStateOf(false)
        private set

    /** 是否处于断线自动重连中（登录后连接断开） */
    var reconnecting by mutableStateOf(false)
        private set

    /** 登录页错误提示（登录失败 / 注册失败 / 被顶下线） */
    var loginError by mutableStateOf<String?>(null)
        private set

    /** 注册成功提示（切回登录后显示，下次操作清空） */
    var registerSuccess by mutableStateOf<String?>(null)
        private set

    /** 顶部临时提示（绑定失败 / 被顶掉 / 已断开等，Toast 后自动清空） */
    var notice by mutableStateOf<String?>(null)
        private set

    // ==================== 设备 ====================

    /** 账号名下设备列表 */
    var devices by mutableStateOf<List<RemoteDevice>>(emptyList())
        private set

    /** 当前绑定设备 ID（null = 未绑定） */
    var boundDeviceId by mutableStateOf<String?>(null)
        private set

    /** 绑定设备是否离线 */
    var deviceOffline by mutableStateOf(false)
        private set

    /** 是否已勾选设备所有者授权（含 ARP 中间人流量审计），每帧 chat 带 authorized */
    // 授权功能已取消（用户拍板）：保留 root 审批档位状态
    /** root 审批档位：ask 每步审批（默认）/ session 会话允许 / off 关闭（内存态，重启恢复默认，与 App 本地 AI 页一致） */
    var shellLevel by mutableStateOf("ask")
        private set

    // ==================== 会话 ====================

    /** 设备返回的会话列表 */
    var sessions by mutableStateOf<List<RemoteSession>>(emptyList())
        private set

    /** 设备当前会话 id */
    var currentSessionId by mutableStateOf<String?>(null)
        private set

    // ==================== 消息 ====================

    /** 消息列表（时间正序：更早在前、最新在后；UI reverseLayout 渲染） */
    val messages = mutableStateListOf<RemoteMsg>()

    /** 是否有进行中的 AI 轮次（决定发送/停止按钮） */
    var busy by mutableStateOf(false)
        private set

    /** 缓存命中顶部状态行（usage 事件解析，显示在对话区顶部） */
    var cacheStat by mutableStateOf<String?>(null)
        private set

    /** 账户余额顶部状态行（每轮完成后设备端推送 balance 事件） */
    var balanceStat by mutableStateOf<String?>(null)
        private set

    // ==================== 历史瀑布流 ====================

    /** 是否还有更早历史可加载（session_switched.hasMore） */
    var historyHasMore by mutableStateOf(false)
        private set

    /** 是否正在加载更早历史（防重入） */
    var loadingMore by mutableStateOf(false)
        private set

    // ==================== 审批 ====================

    /** 待审批请求（null = 无） */
    var pendingApproval by mutableStateOf<RemoteApproval?>(null)
        private set

    // ==================== 记忆同步 ====================

    /** 当前会话记忆列表（memory_list_resp 解析；删/清后设备自动回新列表） */
    var memoryItems by mutableStateOf<List<RemoteMemoryItem>>(emptyList())
        private set

    /** 记忆弹窗是否打开（换绑定/切会话/被顶掉时关闭；ai_done 后若开着自动重拉） */
    var memoryOpen by mutableStateOf(false)
        private set

    // ==================== WebSocket 内部状态 ====================

    /** OkHttp 客户端（懒建复用；读超时 0 无限 + 协议层 ping 25s 保 TCP） */
    private var client: OkHttpClient? = null

    /** 当前 WebSocket 连接（null = 未连接；仅在主线程读写） */
    private var webSocket: WebSocket? = null

    /** 重连退避次数（连接成功归零；每失败一次 +1，决定下次延迟档位） */
    private var reconnectAttempt = 0

    /** 本次 pending 帧是注册还是登录（onOpen 据此发 register_account 或 login） */
    private var registerMode = false

    /** 登录账号 / 密码（断线重连复用，不落盘） */
    private var account = ""
    private var password = ""

    /** 是否已通过登录（收到过 device_list）；断线只对已登录态重连 */
    private var loggedIn = false

    /** ViewModel 是否已销毁（onCleared 后不再重连） */
    private var destroyed = false

    /** 挂起的重连任务句柄（onCleared 移除，避免残留延迟任务触发重连） */
    private var reconnectRunnable: Runnable? = null

    /** 发送后无响应看门狗句柄（网络差时提示设备可能离线） */
    private var watchdogRunnable: Runnable? = null

    /** 绑定/删除当前会话后自动加载新当前会话历史 */
    private var autoLoadHistory = false

    /** 本次 session_switch 是否为「加载更早」分页请求 */
    private var loadMoreMode = false

    /** 已加载历史中最早一条的 ts（分页锚点） */
    private var earliestTs = 0L

    /** 消息自增 ID（历史渲染 / 流式更新用稳定 key） */
    private var msgSeq = 0L

    /** 设备在线状态快照（检测绑定设备离线→恢复自动重绑） */
    private var devOnlineMap = mutableMapOf<String, Boolean>()

    /** 主线程 Handler：所有 WebSocket 回调回主线程改状态 */
    private val handler = Handler(Looper.getMainLooper())

    // ==================== 登录 / 注册 ====================

    /** 登录：记账号密码、关旧连接、重连、onOpen 发 login */
    fun login(name: String, pwd: String) {
        account = name.trim()
        password = pwd
        registerMode = false
        pendingLoginMsg = null
        loginError = null
        registerSuccess = null
        connecting = true
        closeQuietly()
        connect()
    }

    /** 注册：同输入发 register_account；成功后提示切登录 */
    fun register(name: String, pwd: String) {
        account = name.trim()
        password = pwd
        registerMode = true
        pendingLoginMsg = null
        loginError = null
        registerSuccess = null
        connecting = true
        closeQuietly()
        connect()
    }

    // ==================== 设备绑定 ====================

    /** 连接设备：清空历史、重置会话、进控制台、发 bind */
    fun bindDevice(deviceId: String) {
        boundDeviceId = deviceId
        deviceOffline = false
        closeMemory() // 换绑定关闭记忆弹窗
        messages.clear()
        resetSessions()
        autoLoadHistory = true // 绑定成功后拉列表并自动加载当前会话历史
        phase = RemotePhase.CONSOLE
        send(JSONObject().put("t", "bind").put("deviceId", deviceId))
    }

    /** 断开绑定：本地清绑定、回设备列表（v1 无解绑协议，服务器端绑定残留无害） */
    fun unbind() {
        boundDeviceId = null
        deviceOffline = false
        busy = false
        closeMemory() // 断开绑定关闭记忆弹窗
        resetSessions()
        messages.clear()
        phase = RemotePhase.DEVICE_LIST
        notice = "已断开绑定"
    }

    /** 循环切换 root 审批档位：每步审批 → 会话允许 → 关闭 → 每步审批 */
    fun cycleShellLevel() {
        shellLevel = when (shellLevel) {
            "ask" -> "session"
            "session" -> "off"
            else -> "ask"
        }
    }

    /** 清空顶部临时提示（Toast 展示后调用） */
    fun dismissNotice() {
        notice = null
    }

    // ==================== 对话 ====================

    /** 发送 AI 消息：追加 user + assistant 占位、置 busy、发 chat 帧 */
    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy || deviceOffline || boundDeviceId == null) return
        messages.add(RemoteMsg(id = ++msgSeq, role = "user", text = t))
        messages.add(RemoteMsg(id = ++msgSeq, role = "assistant", text = "", done = false))
        busy = true
        send(JSONObject().put("t", "chat").put("text", t))
        armWatchdog() // 网络差看门狗：2.5 分钟无设备响应提示
    }

    /** 发送后无响应看门狗：只在设备被服务器判离线（offline 帧）时提示下线；
     *  AI 长任务（审计/全网检测等 60-120 秒静默执行）不判下线——设备在线则静默续期继续等 */
    private fun armWatchdog() {
        clearWatchdog()
        val runnable = Runnable {
            if (!busy) return@Runnable
            if (deviceOffline) {
                appendSys("⚠ 设备已离线（现场断网/断电/App 被关），本轮任务可能中断。可点『停止』放弃，或等设备恢复。")
            } else {
                armWatchdog() // 设备仍在线：大概率是长任务静默执行，不误判下线，续期继续等
            }
        }
        watchdogRunnable = runnable
        handler.postDelayed(runnable, WATCHDOG_TIMEOUT_MS)
    }

    /** 取消看门狗（本轮结束/收到设备帧刷新时调用） */
    private fun clearWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    /** 停止当前轮：发 stop 帧，等 ai_done(error="stopped") 到达后恢复按钮 */
    fun stopRound() {
        send(JSONObject().put("t", "stop"))
        appendSys("已发送停止指令…")
    }

    // ==================== 单轮删除 ====================

    /** 删除一轮对话（三端同步）：本地移除该轮 user+assistant 两条气泡，发 round_delete 帧。
     *  设备端删除该 user 及其后随 assistant（到下一条 user 前），该轮不再进入 AI 上下文；
     *  成功回 round_deleted + 新 session_list_resp，失败回 session_err（由 handleSessionErr 重拉历史恢复）。 */
    fun delRound(roundTs: Long) {
        if (roundTs <= 0) return
        if (busy) {
            notice = "任务执行中" // busy 时设备端拒绝删除，提示
            return
        }
        // 本地移除该轮 user + assistant（roundTs 匹配；从后往前删避免索引偏移）
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.roundTs == roundTs && (m.role == "user" || m.role == "assistant")) {
                messages.removeAt(i)
            }
        }
        // 重算瀑布流锚点（删除的可能是最早一轮，否则「加载更早」会重复加载已删区间）
        earliestTs = messages.asSequence().map { it.roundTs }.filter { it > 0 }.minOrNull() ?: 0L
        val sid = currentSessionId ?: return
        send(JSONObject().put("t", "round_delete").put("id", sid).put("ts", roundTs))
    }

    // ==================== 审批 ====================

    /** 用户点允许/拒绝（120 秒倒计时超时由 UI 调 timeoutReject 静默拒绝） */
    fun respondApproval(allow: Boolean) {
        val p = pendingApproval ?: return
        respondApprovalInternal(p.reqId, allow, silent = false)
    }

    /** 审批倒计时归零：静默拒绝（不加系统提示） */
    fun timeoutReject() {
        val p = pendingApproval ?: return
        respondApprovalInternal(p.reqId, false, silent = true)
    }

    // ==================== 记忆同步 ====================

    /** 打开记忆弹窗：置打开标志并请求当前会话记忆列表 */
    fun openMemory() {
        memoryOpen = true
        requestMemoryList()
    }

    /** 关闭记忆弹窗（换绑定/切会话/被顶掉时也会被调用） */
    fun closeMemory() {
        memoryOpen = false
        memoryItems = emptyList()
    }

    /** 单条删除记忆（按文本精确匹配，设备删完自动回新列表） */
    fun deleteMemory(text: String) {
        if (busy || deviceOffline || text.isBlank()) return
        send(JSONObject().put("t", "memory_delete").put("text", text))
    }

    /** 清空当前会话记忆（设备清完自动回新列表） */
    fun clearMemory() {
        if (busy || deviceOffline) return
        send(JSONObject().put("t", "memory_clear"))
    }

    /** 请求当前会话记忆列表（{t:"memory_list"}） */
    private fun requestMemoryList() {
        if (boundDeviceId != null) send(JSONObject().put("t", "memory_list"))
    }

    // ==================== 会话管理 ====================

    /** 手动切换会话：清空消息区，等 session_switched 渲染历史 */
    fun switchSession(id: String) {
        if (id == currentSessionId) return
        currentSessionId = id
        closeMemory() // 切会话关闭记忆弹窗
        messages.clear()
        earliestTs = 0L
        historyHasMore = false
        loadingMore = false
        loadMoreMode = false
        send(JSONObject().put("t", "session_switch").put("id", id))
    }

    /** 新建会话：清空本地消息区，设备新建后回 session_list_resp */
    fun newSession() {
        if (busy || deviceOffline || boundDeviceId == null) return
        messages.clear()
        send(JSONObject().put("t", "session_new"))
    }

    /** 清空当前会话消息（标题/记忆/档案/凭据保留） */
    fun clearSession(id: String) {
        if (busy || deviceOffline || id.isBlank()) return
        messages.clear()
        send(JSONObject().put("t", "session_clear").put("id", id))
    }

    /** 删除会话（连带删记忆/档案/凭据）；删除后设备切新当前会话，自动加载其历史 */
    fun deleteSession(id: String) {
        if (busy || deviceOffline || id.isBlank()) return
        autoLoadHistory = true
        messages.clear()
        send(JSONObject().put("t", "session_delete").put("id", id))
    }

    /** 重命名会话（titleCustom=true，之后新消息不覆盖标题） */
    fun renameSession(id: String, title: String) {
        val t = title.trim()
        if (busy || deviceOffline || id.isBlank() || t.isBlank()) return
        send(JSONObject().put("t", "session_rename").put("id", id).put("title", t))
    }

    /** 加载更早历史（瀑布流触发）：发 beforeTs 分页取更早 5 条 */
    fun loadMoreHistory() {
        val sid = currentSessionId
        if (busy || deviceOffline || sid == null || loadingMore || !historyHasMore) return
        loadingMore = true
        loadMoreMode = true
        send(JSONObject().put("t", "session_switch").put("id", sid).put("beforeTs", earliestTs))
    }

    // ==================== 连接与重连 ====================

    /** 建立 WebSocket 连接（复用同一 OkHttpClient；绕过系统代理 + 读超时 0 + ping 25s） */
    private fun connect() {
        if (destroyed) return
        val okClient = client ?: OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)                          // 绕过手机常配的 127.0.0.1:8080 死代理
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)          // WebSocket 长连接不能被默认读超时掐断
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)             // 协议层 ping 保 TCP 存活
            .build().also { client = it }
        val request = Request.Builder().url(WS_URL).build()
        webSocket = okClient.newWebSocket(request, listener)
    }

    /** 发送一帧 JSON（socket 未连接时静默忽略） */
    private fun send(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    /** 关旧连接（重新登录/注册前调用，避免连接叠加）；旧连接 onClosed 因引用已换被忽略 */
    private fun closeQuietly() {
        val old = webSocket
        webSocket = null
        old?.close(1000, "re-login")
    }

    /** 断线重连：5/10/20/40/60 秒封顶指数退避 */
    private fun scheduleReconnect() {
        if (destroyed) return
        val delaysMs = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
        val delayMs = delaysMs[minOf(reconnectAttempt, delaysMs.size - 1)]
        reconnectAttempt++
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            reconnectRunnable = null
            registerMode = false // 重连只走 login（register 只在手动注册时用）
            connect()
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /** 连接断开处理：已登录 → 提示并重连；未登录 → 展示服务器具体失败原因或连接失败 */
    private fun onDisconnected() {
        if (destroyed) return
        if (loggedIn) {
            reconnecting = true
            scheduleReconnect()
        } else {
            connecting = false
            loginError = pendingLoginMsg ?: "连接失败，请检查网络"
        }
    }

    /** 登录/注册失败时暂存具体提示（onClosed 不覆盖） */
    private var pendingLoginMsg: String? = null

    /** WebSocket 回调（OkHttp 内部线程触发，统一 post 主线程再处理） */
    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            handler.post {
                reconnectAttempt = 0
                // 登录 / 注册帧（重连时 registerMode 已置 false，走 login）
                val frame = if (registerMode) {
                    JSONObject().put("t", "register_account").put("name", account).put("pwd", password)
                } else {
                    JSONObject().put("t", "login").put("name", account).put("pwd", password)
                }
                send(frame)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            handler.post { handle(json) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handler.post {
                // 仅当前连接（未被重新登录/重连替换）才处理，避免旧连接关闭误触发
                if (webSocket === this@RemoteControlViewModel.webSocket) {
                    this@RemoteControlViewModel.webSocket = null
                    onDisconnected()
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handler.post {
                if (webSocket === this@RemoteControlViewModel.webSocket) {
                    this@RemoteControlViewModel.webSocket = null
                    onDisconnected()
                }
            }
        }
    }

    // ==================== 服务器帧分发 ====================

    /** 设备 AI 帧序号（断线补发的重复帧按 (epoch,seq) 丢弃） */
    private var lastEpoch = 0L
    private var lastSeq = 0L

    /** 分发一帧服务器消息（主线程执行） */
    private fun handle(m: JSONObject) {
        // 设备 AI 帧序号去重：断线瞬间的原始帧可能已被底层发出，重连后帧缓存又补发一次 → 丢弃重复
        val t = m.optString("t")
        if (t == "ai_event" || t == "ai_done") {
            val epoch = m.optLong("epoch", 0L)
            val seq = m.optLong("seq", 0L)
            if (epoch > 0 && seq > 0) {
                if (epoch < lastEpoch || (epoch == lastEpoch && seq <= lastSeq)) return
                lastEpoch = epoch
                lastSeq = seq
            }
        }
        when (t) {
            "register_ok" -> {
                registerMode = false
                connecting = false
                registerSuccess = "注册成功，请登录"
            }
            "register_fail" -> {
                pendingLoginMsg = "注册失败：" + m.optString("reason")
                loginError = pendingLoginMsg
                connecting = false
            }
            "login_fail" -> {
                pendingLoginMsg = "登录失败：" + m.optString("reason", "账号或密码错误")
                loginError = pendingLoginMsg
                connecting = false
            }
            "device_list" -> handleDeviceList(m)
            "bound" -> handleBound(m)
            "bind_fail" -> handleBindFail(m)
            "offline" -> handleOffline(m)
            "kicked" -> handleKicked()
            "kicked_login" -> handleKickedLogin()
            "ai_event" -> handleAiEvent(m)
            "ai_done" -> handleAiDone(m)
            "approval_req" -> handleApproval(m)
            "session_list_resp" -> handleSessionListResp(m)
            "session_switched" -> handleSwitched(m)
            "session_err" -> handleSessionErr(m)
            "memory_list_resp" -> handleMemoryListResp(m)
            "round_deleted" -> { /* 本地已乐观删除，忽略即可（设备端会随新 session_list_resp 更新 count） */ }
            "ping" -> send(JSONObject().put("t", "pong")) // 服务器心跳探测（控制端一般收不到，兜底）
        }
    }

    /** 登录成功即推 device_list（无独立 login_ok 帧）：进入设备列表；重连时补发 bind */
    private fun handleDeviceList(m: JSONObject) {
        val wasReconnecting = reconnecting
        reconnecting = false
        connecting = false
        val firstLogin = !loggedIn
        if (firstLogin) loggedIn = true
        devices = parseDevices(m.optJSONArray("devices"))

        // 绑定设备离线→恢复：自动重绑（现场断网恢复后免手动点连接）
        val newMap = mutableMapOf<String, Boolean>()
        devices.forEach { d ->
            if (d.online && devOnlineMap[d.deviceId] == false && boundDeviceId == d.deviceId && deviceOffline) {
                deviceOffline = false
                send(JSONObject().put("t", "bind").put("deviceId", d.deviceId))
                appendSys("设备已恢复，重新连接中…")
            }
            newMap[d.deviceId] = d.online
        }
        devOnlineMap = newMap

        if (firstLogin) {
            phase = RemotePhase.DEVICE_LIST
        } else if (wasReconnecting && boundDeviceId != null) {
            // 断线重连成功：补发 bind + session_list（历史不清）
            send(JSONObject().put("t", "bind").put("deviceId", boundDeviceId))
            if (phase == RemotePhase.CONSOLE) {
                appendSys("连接已恢复，正在重新绑定设备…")
                requestSessionList()
            }
        }
    }

    /** 绑定成功：拉会话列表（与设备共用同一份 chat_sessions） */
    private fun handleBound(m: JSONObject) {
        boundDeviceId = m.optString("deviceId")
        deviceOffline = false
        requestSessionList()
    }

    /** 绑定被拒（设备不属于该账号/未绑定账号）：清绑定、回列表、提示原因 */
    private fun handleBindFail(m: JSONObject) {
        boundDeviceId = null
        deviceOffline = false
        busy = false
        closeMemory() // 绑定被拒关闭记忆弹窗
        resetSessions()
        messages.clear()
        phase = RemotePhase.DEVICE_LIST
        notice = "绑定失败：" + m.optString("reason")
    }

    /** 绑定设备离线：标记离线、中断本轮、系统提示 */
    private fun handleOffline(m: JSONObject) {
        if (m.optString("deviceId") == boundDeviceId) {
            deviceOffline = true
            if (busy) {
                busy = false // 设备离线中断本轮，恢复按钮避免卡死
                // 流式中的 assistant 占位补个标记，避免永久「生成中…」
                updateStreaming {
                    it.copy(text = it.text.ifBlank { "⏹ 已中断（设备离线）" }, done = true, statusText = "")
                }
            }
            appendSys("⚠ 设备已离线")
        }
    }

    /** 被其他控制端顶掉：清绑定、回设备列表 */
    private fun handleKicked() {
        boundDeviceId = null
        deviceOffline = false
        busy = false
        closeMemory() // 被顶掉关闭记忆弹窗
        resetSessions()
        messages.clear()
        phase = RemotePhase.DEVICE_LIST
        notice = "该设备已被其他控制端接管，已断开绑定"
    }

    /** 账号单点登录：本连接被同账号新登录顶下线，回到登录页 */
    private fun handleKickedLogin() {
        loggedIn = false
        boundDeviceId = null
        busy = false
        closeMemory() // 被顶下线关闭记忆弹窗
        resetSessions()
        messages.clear()
        devices = emptyList()
        pendingLoginMsg = "该账号已在其他位置登录，本页面已退出"
        loginError = pendingLoginMsg
        connecting = false
        phase = RemotePhase.LOGIN
        // 关掉当前连接（loggedIn 已置 false，onClosed 不会重连，也不会覆盖上面的提示）
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "kicked_login")
    }

    // ==================== ai_event 流式事件 ====================

    /** 流式事件：reasoning 累积 / status 状态行 / tool_log 建卡追加 / tool_result 填卡 / usage 缓存行 */
    private fun handleAiEvent(m: JSONObject) {
        // 三端实时同步：PDA 屏上发起的轮次（user_msg）→ 本地渲染 user + 流式占位，发送按钮变停止
        if (m.optString("kind") == "user_msg") {
            if (busy) return   // 控制端已有轮次进行中（并发互斥，设备端也会拒绝）
            messages.add(RemoteMsg(id = ++msgSeq, role = "user", text = m.optString("data")))
            messages.add(RemoteMsg(id = ++msgSeq, role = "assistant", text = "", done = false))
            busy = true
            armWatchdog()
            return
        }
        armWatchdog() // 收到设备帧：刷新看门狗（设备在线且在执行，长任务静默期由回调续期兜底）
        when (m.optString("kind")) {
            "reasoning" -> updateStreaming { it.copy(reasoning = it.reasoning + m.optString("data")) }
            "delta" -> updateStreaming { it.copy(text = m.optString("data")) }   // 正文增量（工具轮过渡语/最终回答）
            "status" -> updateStreaming {
                it.copy(
                    statusText = m.optString("data").ifBlank { "正在调用工具…" },
                    statusStart = System.currentTimeMillis()
                )
            }
            "tool_log" -> handleToolLog(m.optString("data"))
            "tool_result" -> handleToolResult(m.optJSONObject("data"))
            "usage" -> handleUsage(m.optJSONObject("data"))
            "balance" -> balanceStat = m.optString("data").ifBlank { null }
        }
    }

    /** tool_log：以「▶ 开始工具 X」开头建新卡（旧卡折叠、新卡展开），后续行追加到当前卡输出 */
    private fun handleToolLog(line: String) {
        updateStreaming { msg ->
            if (line.startsWith("▶ 开始工具 ")) {
                val name = line.removePrefix("▶ 开始工具 ").substringBefore("（")
                val cards = msg.toolCards + RemoteToolCard(name = name, output = line)
                msg.copy(toolCards = cards)
            } else if (msg.toolCards.isNotEmpty()) {
                val idx = msg.toolCards.size - 1
                val updated = msg.toolCards[idx].copy(output = msg.toolCards[idx].output + "\n" + line)
                msg.copy(toolCards = msg.toolCards.toMutableList().also { it[idx] = updated })
            } else {
                msg
            }
        }
    }

    /** tool_result：把 input/output 填进对应卡（按 name 匹配最后一张；无匹配则新建）；工具结束清状态行 */
    private fun handleToolResult(d: JSONObject?) {
        if (d == null) return
        val name = d.optString("name").ifBlank { d.optString("toolName") }
        val input = d.optString("input")
        val output = d.optString("output")
        if (name.isBlank() && input.isBlank() && output.isBlank()) return
        updateStreaming { msg ->
            val cards = msg.toolCards.toMutableList()
            val idx = if (name.isNotBlank()) cards.indexOfLast { it.name == name } else -1
            if (idx >= 0) {
                val c = cards[idx]
                cards[idx] = c.copy(
                    input = if (input.isNotBlank() && c.input.isBlank()) input else c.input,
                    output = if (output.isNotBlank()) {
                        (if (c.output.isBlank()) "" else c.output + "\n") + "=== 结果 ===\n" + output
                    } else c.output
                )
            } else {
                cards.add(RemoteToolCard(name = name.ifBlank { "工具" }, input = input, output = output))
            }
            msg.copy(toolCards = cards, statusText = "") // 工具结束，状态行消失
        }
    }

    /** usage：解析缓存命中 token 数，显示在对话区顶部（不进气泡，与 App 布局一致） */
    private fun handleUsage(d: JSONObject?) {
        if (d == null) return
        val hit = d.optLong("cacheHitTokens")
        val miss = d.optLong("cacheMissTokens")
        val total = hit + miss
        val rate = if (total > 0) (hit * 100 / total).toInt() else 0
        cacheStat = "⚡ 缓存命中 $rate%（命中 ${hit / 1024}K/未命中 ${miss / 1024}K）"
    }

    /** 一轮完成：落最终文本（成功/手动停止/错误三种收尾），恢复发送按钮；补该轮 userTs */
    private fun handleAiDone(m: JSONObject) {
        val text = m.optString("text")
        val roundSec = m.optLong("roundSec")
        val error = m.optString("error")
        val userTs = m.optLong("userTs") // 该轮 user 消息落盘 ts（新发轮在 ai_done 后才拿到）
        // 直接定位流式中的 assistant（done=false），同时补 roundTs；其前一条 user 同轮
        val asstIdx = messages.indexOfLast { it.role == "assistant" && !it.done }
        if (asstIdx >= 0) {
            val (finalText, isError) = when {
                error == "stopped" ->
                    (if (text.isNotBlank()) "$text\n\n⏹ 已手动停止" else "⏹ 已手动停止") to false
                error.isNotBlank() ->
                    (if (text.isNotBlank()) "$text\n\n❌ $error" else "❌ $error") to true
                else -> text to false
            }
            messages[asstIdx] = messages[asstIdx].copy(
                text = finalText, roundSec = roundSec, done = true,
                error = isError, statusText = "", roundTs = userTs
            )
            // 该轮 user 消息补 roundTs（同一轮）
            if (userTs > 0 && asstIdx > 0 && messages[asstIdx - 1].role == "user") {
                messages[asstIdx - 1] = messages[asstIdx - 1].copy(roundTs = userTs)
            }
        }
        busy = false
        clearWatchdog() // 本轮结束：取消看门狗
        // 记忆弹窗开着时，本轮 AI 可能 remember_info 新记了内容，自动重拉记忆
        if (memoryOpen) requestMemoryList()
    }

    // ==================== 审批 ====================

    /** 收到审批请求：已有挂起审批则先自动拒绝旧的（v1 单弹窗），再展示新的 */
    private fun handleApproval(m: JSONObject) {
        pendingApproval?.let { respondApprovalInternal(it.reqId, false, silent = true) }
        pendingApproval = RemoteApproval(
            reqId = m.optString("reqId"),
            toolName = m.optString("toolName"),
            command = m.optString("command"),
            deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
        )
    }

    /** 发送审批结果；silent=true 表示超时自动拒绝。结果标记追加到当前轮最后一个工具卡输出后面
     *  （用户拍板："已允许执行"跟在工具调用文字后面，不单独发系统气泡） */
    private fun respondApprovalInternal(reqId: String, allow: Boolean, silent: Boolean) {
        send(JSONObject().put("t", "approve").put("reqId", reqId).put("allow", allow))
        if (pendingApproval?.reqId == reqId) pendingApproval = null
        val mark = when {
            allow -> "✔ 已允许执行"
            silent -> "⏱ 审批超时，已自动拒绝"
            else -> "✖ 已拒绝执行"
        }
        // 找到当前流式 assistant 的最后一张工具卡，把结果标记追加到其输出后面
        val asstIdx = messages.indexOfLast { it.role == "assistant" && !it.done }
        if (asstIdx >= 0 && messages[asstIdx].toolCards.isNotEmpty()) {
            val msg = messages[asstIdx]
            val cards = msg.toolCards.toMutableList()
            val last = cards.last()
            cards[cards.size - 1] = last.copy(output = last.output + "\n" + mark)
            messages[asstIdx] = msg.copy(toolCards = cards)
        } else if (!silent) {
            appendSys(mark)
        }
    }

    // ==================== 会话帧 ====================

    /** 会话列表响应：渲染下拉；绑定成功/删除当前会话后自动加载新当前会话历史 */
    private fun handleSessionListResp(m: JSONObject) {
        sessions = parseSessions(m.optJSONArray("sessions"))
        currentSessionId = m.optString("currentId").ifBlank { null }
        if (autoLoadHistory && currentSessionId != null) {
            autoLoadHistory = false
            send(JSONObject().put("t", "session_switch").put("id", currentSessionId))
        }
    }

    /** 设备切换会话成功：分页加载更早时前插渲染，首屏切换时全量替换 */
    private fun handleSwitched(m: JSONObject) {
        currentSessionId = m.optString("id").ifBlank { null }
        val history = parseHistory(m.optJSONArray("history"))
        if (loadMoreMode) {
            // 「加载更早」分页：更早历史插到消息列表头部。
            // 渲染 items(messages.asReversed()) + reverseLayout：messages 头部 = asReversed 末尾 =
            // 最大 index = 视觉最顶部（最旧），所以更早历史必须 addAll(0, …) 插头部。
            loadMoreMode = false
            loadingMore = false
            if (history.isNotEmpty()) {
                messages.addAll(0, history)
                earliestTs = history.first().ts
            }
        } else {
            messages.clear()
            messages.addAll(history)
            if (history.isNotEmpty()) earliestTs = history.first().ts
        }
        historyHasMore = m.optBoolean("hasMore")
    }

    /** 会话操作失败（含 round_delete 失败）：提示、重新同步列表，并以设备端为准重拉当前会话历史
     *  （撤销 round_delete 的本地乐观删除等） */
    private fun handleSessionErr(m: JSONObject) {
        notice = "会话操作失败：" + m.optString("reason")
        autoLoadHistory = false // 避免与下方手动重拉历史双触发
        requestSessionList()
        val sid = currentSessionId
        if (sid != null) {
            loadMoreMode = false
            loadingMore = false
            historyHasMore = false
            send(JSONObject().put("t", "session_switch").put("id", sid))
        }
    }

    /** 记忆列表响应：只接受当前会话的记忆（id 匹配才更新，防旧会话残留串台） */
    private fun handleMemoryListResp(m: JSONObject) {
        val sid = m.optString("id")
        if (sid.isBlank() || sid == currentSessionId) {
            memoryItems = parseMemoryItems(m.optJSONArray("items"))
        }
    }

    /** 请求设备下发会话列表（{t:"session_list"}） */
    private fun requestSessionList() {
        if (boundDeviceId != null) send(JSONObject().put("t", "session_list"))
    }

    /** 重置本地会话选择状态（换设备/断开绑定/被顶掉时） */
    private fun resetSessions() {
        sessions = emptyList()
        currentSessionId = null
        autoLoadHistory = false
        historyHasMore = false
        loadingMore = false
        loadMoreMode = false
        earliestTs = 0L
    }

    // ==================== 解析辅助 ====================

    /** 解析设备列表 */
    private fun parseDevices(arr: JSONArray?): List<RemoteDevice> {
        if (arr == null) return emptyList()
        val list = mutableListOf<RemoteDevice>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = o.optJSONObject("status")
            list.add(
                RemoteDevice(
                    deviceId = o.optString("deviceId"),
                    name = o.optString("name"),
                    model = o.optString("model"),
                    account = o.optString("account"),
                    online = o.optBoolean("online"),
                    battery = st?.optInt("battery", -1) ?: -1,
                    ssid = st?.optString("ssid") ?: "",
                    busy = st?.optBoolean("busy") ?: false
                )
            )
        }
        return list
    }

    /** 解析会话列表 */
    private fun parseSessions(arr: JSONArray?): List<RemoteSession> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            RemoteSession(
                id = o.optString("id"),
                title = o.optString("title", "新会话"),
                updatedAt = o.optLong("updatedAt"),
                count = o.optInt("count")
            )
        }
    }

    /** 解析当前会话记忆列表（items:[{text,ts}]） */
    private fun parseMemoryItems(arr: JSONArray?): List<RemoteMemoryItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            RemoteMemoryItem(
                text = o.optString("text"),
                ts = o.optLong("ts")
            )
        }
    }

    /** 解析历史消息（user 右气泡 / assistant 左气泡带思考·工具·耗时 / 其他居中系统提示） */
    private fun parseHistory(arr: JSONArray?): List<RemoteMsg> {
        if (arr == null) return emptyList()
        val list = mutableListOf<RemoteMsg>()
        var curRoundTs = 0L // 当前轮 user 消息落盘 ts（assistant 随其前一条 user 同轮）
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            val role = h.optString("role")
            val text = h.optString("text")
            val ts = h.optLong("ts")
            when (role) {
                "user" -> {
                    curRoundTs = ts
                    list.add(RemoteMsg(id = ++msgSeq, role = "user", text = text, ts = ts, roundTs = ts))
                }
                "assistant" -> {
                    val toolCards = h.optJSONArray("toolCalls")?.let { tcArr ->
                        (0 until tcArr.length()).mapNotNull { k ->
                            val tc = tcArr.optJSONObject(k) ?: return@mapNotNull null
                            RemoteToolCard(
                                name = tc.optString("name", "工具"),
                                input = tc.optString("input"),
                                output = tc.optString("output")
                            )
                        }
                    } ?: emptyList()
                    list.add(
                        RemoteMsg(
                            id = ++msgSeq, role = "assistant", text = text, ts = ts,
                            reasoning = h.optString("reasoning"),
                            toolCards = toolCards,
                            roundSec = h.optLong("roundSec"),
                            done = true,
                            roundTs = curRoundTs
                        )
                    )
                }
                else -> list.add(RemoteMsg(id = ++msgSeq, role = "sys", text = text, ts = ts))
            }
        }
        return list
    }

    // ==================== 消息辅助 ====================

    /** 更新流式中的最后一条 assistant 消息（按 done=false 定位） */
    private fun updateStreaming(transform: (RemoteMsg) -> RemoteMsg) {
        val idx = messages.indexOfLast { it.role == "assistant" && !it.done }
        if (idx >= 0) messages[idx] = transform(messages[idx])
    }

    /** 追加系统提示（居中小字） */
    private fun appendSys(text: String) {
        messages.add(RemoteMsg(id = ++msgSeq, role = "sys", text = text))
    }

    // ==================== 生命周期 ====================

    override fun onCleared() {
        destroyed = true
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        clearWatchdog() // 清看门狗残留定时任务
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "page closed")
        client = null
        super.onCleared()
    }

    companion object {
        /** 服务器 WebSocket 中转地址（88 服务器 8091，与被控端同一服务） */
        private const val WS_URL = "ws://your-server.example:8091/ws"

        /** 审批挂起超时（120 秒，与服务器/被控端约定一致），超时默认拒绝 */
        private const val APPROVAL_TIMEOUT_MS = 120_000L

        /** 发送后无响应看门狗间隔（150 秒：远大于单个工具最长执行，只兜设备真离线场景） */
        private const val WATCHDOG_TIMEOUT_MS = 150_000L
    }
}
