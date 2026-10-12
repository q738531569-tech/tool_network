package com.installer.toolbox.ui.screens.ai

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.installer.toolbox.core.agent.AgentEngine
import com.installer.toolbox.core.agent.AgentPrompts
import com.installer.toolbox.core.agent.DeepSeekClient
import com.installer.toolbox.core.agent.DeepSeekClient.ChatMessage
import com.installer.toolbox.core.agent.support.AiMemory
import com.installer.toolbox.core.agent.support.AiNotes
import com.installer.toolbox.core.agent.support.CredentialVault
import com.installer.toolbox.core.agent.support.SensitiveMasker
import com.installer.toolbox.util.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.widget.Toast
import com.installer.toolbox.remote.AiSyncBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 对话助手 ViewModel（自由对话）。
 *
 * - API Key 由设置页写入加密存储（SecurePrefs，Android Keystore AES-GCM），
 *   本类 init / refreshKey() 只读一次到内存，不负责写入。
 * - [sendText]：自由对话入口。历史 = [system 提示词] + 全部已发消息（不含占位），
 *   经 AgentEngine.runAgentLoop 走工具调用循环（模型可主动调用诊断工具采集数据），
 *   最终回答一次写入 assistant 占位；工具调用期间 [status] 显示进度。
 * - 所有网络调用必须在 Dispatchers.IO 上执行。
 */
class AiViewModel(application: Application) : AndroidViewModel(application) {

    /** 单条工具调用记录（AI 调用工具的过程，界面折叠展示） */
    data class ToolCallRecord(
        val name: String,
        val input: String,
        val output: String
    )

    /** 对话消息（role: user / assistant；timestamp 为毫秒时间戳，用于显示消息时间） */
    data class UiMsg(
        val role: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        /** assistant 消息的思考链（思考过程可见：流式累积，完成后 UI 折叠可展开）；持久化落盘 */
        val reasoning: String? = null,
        /** assistant 消息轮次内的工具调用记录（可折叠查看输入/输出） */
        val toolCalls: List<ToolCallRecord> = emptyList(),
        /** 本轮耗时（秒，assistant 完成时写入；气泡下方时间戳旁显示"本轮 N 秒"） */
        val roundSec: Long = 0
    )

    /** 会话（含消息历史，本地持久化，支持多会话新建/切换/删除/重命名） */
    data class ChatSession(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<UiMsg>,
        /** 标题是否用户手动命名（true 时 persistCurrentMessages 不覆盖标题） */
        val titleCustom: Boolean = false
    ) {
        /** 最后一条消息预览（会话列表用，1 行省略；跳过末尾空占位） */
        val lastMessagePreview: String
            get() = messages.lastOrNull { it.text.isNotBlank() }?.text?.take(28) ?: ""
    }

    // API Key 已加密存储（SecurePrefs：Android Keystore AES-GCM，明文自动迁移）；
    // 本 prefs 仍用于会话记录（chat_sessions，非敏感）
    private val prefs = application.getSharedPreferences("agent", Context.MODE_PRIVATE)

    /** 内存中的 API Key（设置页写 prefs，本类 init/refreshKey 读入） */
    private var key: String = ""

    /** 模型名（设置页可配置，默认 deepseek-v4-flash；存 prefs "agent"/model_name） */
    private val _modelName = MutableStateFlow(DeepSeekClient.DEFAULT_MODEL)
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** API Key 是否已配置（决定能否对话） */
    private val _apiKeyReady = MutableStateFlow(false)
    val apiKeyReady: StateFlow<Boolean> = _apiKeyReady.asStateFlow()

    /** 服务器连接检测状态（null=未检测；OK/UNAUTHORIZED/FAIL 见 DeepSeekClient.ServerStatus） */
    private val _serverStatus = MutableStateFlow<DeepSeekClient.ServerStatus?>(null)
    val serverStatus: StateFlow<DeepSeekClient.ServerStatus?> = _serverStatus.asStateFlow()

    // ==================== root shell 审批 ====================

    /** root shell 审批档位 */
    enum class ShellLevel(val label: String, val desc: String) {
        /** 关闭：AI 不可执行 root shell（工具返回未开启说明） */
        OFF("关闭", "AI 不能执行 root 命令"),

        /** 每步审批（默认）：每次 root 命令都弹窗确认 */
        ASK("每步审批", "每条 root 命令都弹窗确认（默认）"),

        /** 本次会话允许：当前会话内 root 命令不再弹窗（危险，重启 App 后恢复默认） */
        SESSION("会话允许", "本会话内 root 命令免审批（危险）")
    }

    /** 当前审批档位（默认每步审批；内存态，重启恢复默认） */
    private val _shellLevel = MutableStateFlow(ShellLevel.ASK)
    val shellLevel: StateFlow<ShellLevel> = _shellLevel.asStateFlow()

    /** 待审批的 root 命令（UI 弹窗用；approved 完成即回传执行与否） */
    data class PendingShell(
        val command: String,
        val approved: kotlinx.coroutines.CompletableDeferred<Boolean>
    )

    private val _pendingShell = MutableStateFlow<PendingShell?>(null)
    val pendingShell: StateFlow<PendingShell?> = _pendingShell.asStateFlow()

    /** 切换审批档位（循环：关闭→每步→会话→关闭） */
    fun cycleShellLevel() {
        _shellLevel.value = when (_shellLevel.value) {
            ShellLevel.OFF -> ShellLevel.ASK
            ShellLevel.ASK -> ShellLevel.SESSION
            ShellLevel.SESSION -> ShellLevel.OFF
        }
    }

    /** 直接设置审批档位（档位选择弹窗用） */
    fun setShellLevel(level: ShellLevel) {
        _shellLevel.value = level
    }

    /** 用户对审批弹窗的答复 */
    fun approvePendingShell(ok: Boolean) {
        val pending = _pendingShell.value ?: return
        _pendingShell.value = null
        pending.approved.complete(ok)
    }

    /** 摘要缓存：已压缩的旧消息条数与摘要文本（增量压缩：仅在超出部分变化时重新摘要） */
    private var summaryText: String? = null
    private var summarizedCount = 0

    /**
     * 构建请求历史（suspend）：
     * 1) system = 提示词 + **长期记忆注入**（跨会话记忆）；
     * 2) 超过 [maxHistoryRounds] 轮的更早历史 → **智能压缩**：交给模型生成要点摘要
     *    （保留拓扑/结论/用户要求），以一条 system 消息携带，避免粗暴丢弃；
     * 3) 最近 30 轮完整对话 + 单条消息截断。
     */
    private suspend fun buildHistory(messages: List<UiMsg>, rounds: Int = maxHistoryRounds): List<ChatMessage> {
        // 从尾部往前按 user 消息切轮
        val roundsList = mutableListOf<List<UiMsg>>()
        var current = mutableListOf<UiMsg>()
        for (m in messages.asReversed()) {
            current.add(0, m)
            if (m.role == "user") {
                roundsList.add(0, current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) roundsList.add(0, current)
        val kept = roundsList.takeLast(rounds).flatten()
        val old = messages.take(messages.size - kept.size)

        // 本会话记忆注入（按会话隔离：一个会话一个客户/场景，不跨会话混用）
        val memory = AiMemory.injectText(getApplication(), _currentSessionId.value)
        // 凭据保险库清单注入（只给标签/类型/id，不含密码值；需要时 AI 用 get_credential 读值）
        val vault = CredentialVault.injectText(getApplication(), _currentSessionId.value)
        // 资料档案清单注入（软件兜底：清空会话后重新聊天，AI 一眼看到有哪些 md 可读）
        val notes = AiNotes.list(getApplication(), _currentSessionId.value)
        val notesText = if (notes.isEmpty()) null else buildString {
            append("\n\n【本会话资料档案（AI 生成的 md，需要细节时用 read_md 读取全文）】共 ${notes.size} 份：")
            notes.take(10).forEachIndexed { i, f ->
                val head = runCatching { f.readText().take(300) }.getOrDefault("")
                val firstLine = head.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                append("\n").append(i + 1).append(". ").append(f.name)
                if (firstLine.isNotBlank()) append("（摘要：").append(firstLine.take(80)).append("）")
            }
        }
        val system = AgentPrompts.SYSTEM_PROMPT + (memory ?: "") + (vault ?: "") + (notesText ?: "")

        return buildList {
            add(ChatMessage("system", system))
            // 更早历史的智能压缩摘要（增量缓存：内容不变不重复调用摘要）。
            // 摘要触发条数随压缩档位联动：30 轮档 ≥20 条才摘要；15 轮档 ≥10 条；5 轮档 ≥0（全摘要）
            val summarizeThreshold = if (rounds <= 5) 0 else if (rounds <= 15) 10 else 20
            if (old.size >= summarizeThreshold) {
                if (summaryText == null || summarizedCount != old.size) {
                    summaryText = summarizeOld(context = getApplication(), old = old)
                    summarizedCount = old.size
                }
                summaryText?.let { s ->
                    add(ChatMessage("system", "【更早对话的智能摘要（已压缩，节省上下文）】$s"))
                }
            }
            kept.forEach { m ->
                add(ChatMessage(m.role, m.text.take(maxMsgChars)))
            }
        }
    }

    /** 调用模型把更早历史压缩为要点摘要（失败返回 null，不影响主流程） */
    private suspend fun summarizeOld(context: Context, old: List<UiMsg>): String? {
        if (key.isBlank() || old.isEmpty()) return null
        return try {
            val text = old.joinToString("\n") { m ->
                "${if (m.role == "user") "用户" else "AI"}：${m.text.take(2000)}"
            }
            DeepSeekClient.chat(
                apiKey = key,
                messages = listOf(
                    ChatMessage(
                        "system",
                        "你是摘要助手：把以下装维排查对话压缩成 300 字以内的要点摘要。必须保留可复用的事实：网络拓扑、设备 IP 与用途、关键结论、用户明确要求；省略寒暄与过程细节。"
                    ),
                    ChatMessage("user", text)
                ),
                model = _modelName.value,
                effort = "off"   // 摘要辅助调用关思考（对齐 harness：快且省，正文预算全给摘要）
            ).take(600)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 工具审批闸门（传入 runAgentLoop）：仅拦截 root_shell。
     * 档位：OFF→直接拒绝并说明；ASK→挂起等答复（远程开启时审批转移控制端，本地弹窗静默）；SESSION→放行。
     * ARP 类命令（arpspoof/arping）一律免审批（审计核心动作，逐条弹窗打断流程）。
     */
    private suspend fun toolGate(call: DeepSeekClient.ToolCall): String? {
        if (call.name != "root_shell") return null
        return when (_shellLevel.value) {
            ShellLevel.OFF ->
                "root 命令未执行：当前 Shell 审批档位为『关闭』，请在 AI 页标题旁的审批档位按钮切到『每步审批』或『会话允许』后重试。"
            ShellLevel.SESSION -> null
            ShellLevel.ASK -> {
                val command = call.arguments.optString("command", "")
                if (command.isBlank()) {
                    "参数错误：root_shell 缺少 command"
                } else if (command.contains("arpspoof") || command.contains("arping")) {
                    null // ARP 白名单：免审批
                } else {
                    // 本机发起的 root 命令一律本机弹窗审批（PDA 反馈 id=84 修复：
                    // 之前远程开关开着时本地轮次审批被桥接到控制端，本机操作要等远端确认/超时拒绝，
                    // 影响本地排查效率；远程轮次的审批走 RemoteAiSession approval_req 帧，天然在控制端）
                    val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    _pendingShell.value = PendingShell(command, deferred)
                    val ok = try {
                        deferred.await()
                    } finally {
                        _pendingShell.value = null
                    }
                    if (ok) null else "用户拒绝了该 root 命令的执行。"
                }
            }
        }
    }

    /** 正在检测服务器连接中 */
    private val _checkingServer = MutableStateFlow(false)
    val checkingServer: StateFlow<Boolean> = _checkingServer.asStateFlow()

    /** DeepSeek 账户余额显示文本（如"¥12.34"）；null=未查询到（接口失败/未配 Key 静默） */
    private val _balance = MutableStateFlow<String?>(null)
    val balance: StateFlow<String?> = _balance.asStateFlow()

    /** 对话消息列表（最后一条 assistant 为流式输出占位） */
    private val _messages = MutableStateFlow<List<UiMsg>>(emptyList())
    val messages: StateFlow<List<UiMsg>> = _messages.asStateFlow()

    /** 全部会话列表（本地持久化，按更新时间倒序） */
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    /** 当前会话 id */
    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    /** AI 回复进行中（发送 / 流式输出期间为 true） */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 当前发送协程（停止按钮取消它；中断 HTTP 与工具执行） */
    private var currentJob: kotlinx.coroutines.Job? = null

    /** 用户是否点了停止（区分"手动停止"与"异常失败"的收尾文案） */
    @Volatile
    private var _stoppedByUser = false

    /** 工具调用状态提示（如"正在调用工具：ping_device…"），null 表示无 */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** 上下文缓存命中统计（跨会话累计、持久化），null=尚未有数据；UI 显示"缓存命中 xx%" */
    private val _cacheStats = MutableStateFlow<DeepSeekClient.UsageInfo?>(null)
    val cacheStats: StateFlow<DeepSeekClient.UsageInfo?> = _cacheStats.asStateFlow()

    /** 上次对话是否未完成（恢复时末尾空占位判定；UI 提示"上轮回复未完成"） */
    private val _interrupted = MutableStateFlow(false)
    val interrupted: StateFlow<Boolean> = _interrupted.asStateFlow()

    /** 待打码值集合（mask_credential 工具传入但未存库的值；persist 落盘时一并精确打码）
     *  注意：必须声明在 init 块之前（Kotlin 属性初始化按声明顺序执行，
     *  init 里中断恢复路径会触发 persistCurrentMessages → pendingMask.toList()） */
    private val pendingMask = mutableSetOf<String>()

    /** 主线程 Handler：AI 循环回调（onReasoning/onToolLog 等）在 IO 线程触发，
     *  统一 post 到主线程再写 Compose state（防 Compose 1.7 "Unsupported concurrent change" 崩溃） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 主线程执行块（AI 回调写 state 前统一切换线程） */
    private inline fun onMain(crossinline block: () -> Unit) {
        mainHandler.post { block() }
    }

    /** 历史上下文窗口：最近 N 轮完整对话（省钱：长对话费用恒定，不随历史线性增长） */
    private val maxHistoryRounds = 30

    /** 单条消息最大字符数（超长工具输出/报告截断，控制单轮费用） */
    private val maxMsgChars = 4000

    /** 累计缓存命中/未命中 token（持久化：冷启动不丢统计——服务端缓存本就保留数小时） */
    private var totalCacheHit = 0L
    private var totalCacheMiss = 0L

    /** 上一轮请求的实际输入 token 数（usage.prompt_tokens 实测；上下文预算压缩档位依据：
     *  ≥50 万=50% 开始自动压缩、≥80 万=80% 强制压缩；与 1M 上下文容量对应） */
    private var lastPromptTokens = 0L


    init {
        // 单次回复最大 token 数 + 思考强度（设置页可配）：同步到 DeepSeekClient 全局，进程内生效
        DeepSeekClient.maxOutputTokens = prefs.getInt("max_output_tokens", 256000)
        DeepSeekClient.reasoningEffort = prefs.getString("reasoning_effort", "high") ?: "high"
        // 缓存统计持久化恢复（冷启动不清零；切会话/清空会话时由 resetCompressCache 重置）
        totalCacheHit = prefs.getLong("cache_hit_tokens", 0L)
        totalCacheMiss = prefs.getLong("cache_miss_tokens", 0L)
        if (totalCacheHit > 0 || totalCacheMiss > 0) {
            _cacheStats.value = DeepSeekClient.UsageInfo(
                promptTokens = 0L,
                completionTokens = 0L,
                cacheHitTokens = totalCacheHit,
                cacheMissTokens = totalCacheMiss
            )
        }
        refreshKey()
        loadSessions()
        // 一次性历史迁移：把旧版本落盘的会话消息中的密码明文打码（保险库锚点 + 正则兜底，不可逆）
        maskStoredSessions()
        // 恢复最近会话；无会话时新建默认会话
        val last = _sessions.value.firstOrNull()
        if (last != null) {
            _currentSessionId.value = last.id
            _messages.value = last.messages
            markActiveSession(last.id)
            // 检测上次未完成的对话（末尾 assistant 正文为空 = 进程被杀/切后台时中断的半轮，无论是否已产生思考/工具日志）
            val tail = last.messages.lastOrNull()
            if (tail != null && tail.role == "assistant" && tail.text.isBlank()) {
                _messages.value = _messages.value.dropLast(1)
                _interrupted.value = true
                persistCurrentMessages()
            }
        } else {
            newSession()
        }
        // 三端实时同步：监听远程轮次事件（控制端发起），实时刷新本地 UI（主线程应用）
        AiSyncBus.localListener = { e -> onMain { onRemoteEvent(e) } }
    }

    /** 更新最后一条 assistant 消息（远程事件/流式回调共用；无 assistant 占位时忽略） */
    private fun updateLastAsst(transform: (UiMsg) -> UiMsg) {
        val list = _messages.value.toMutableList()
        val last = list.lastOrNull()
        if (last != null && last.role == "assistant") {
            list[list.size - 1] = transform(last)
            _messages.value = list
        }
    }

    /**
     * 远程轮次事件（控制端发起）→ PDA 屏 AI 页实时同步。
     * sessionId 与本地当前会话不一致时忽略（落盘已保证最终一致，切会话重载即可见）。
     */
    private fun onRemoteEvent(e: AiSyncBus.Event) {
        if (e.sessionId.isNotBlank() && e.sessionId != _currentSessionId.value) return
        when (e.kind) {
            "user_msg" -> {
                if (!_busy.value) {
                    // 控制端发起一轮：本地追加 user + assistant 占位（只读同步，不置本地 busy）
                    _messages.value = _messages.value + UiMsg("user", e.text) + UiMsg("assistant", "")
                }
            }
            "reasoning" -> updateLastAsst { it.copy(reasoning = (it.reasoning ?: "") + e.text) }
            "delta" -> updateLastAsst { it.copy(text = e.text) }   // 正文增量（工具轮过渡语/最终回答）
            "tool_log" -> updateLastAsst { msg ->
                val calls = msg.toolCalls.toMutableList()
                if (e.text.startsWith("▶ 开始工具 ")) {
                    val name = e.text.removePrefix("▶ 开始工具 ").substringBefore("（")
                    calls.add(ToolCallRecord(name, "", e.text))
                } else if (calls.isNotEmpty()) {
                    val idx = calls.size - 1
                    calls[idx] = calls[idx].copy(output = calls[idx].output + "\n" + e.text)
                }
                msg.copy(toolCalls = calls)
            }
            "tool_result" -> updateLastAsst { msg ->
                val calls = msg.toolCalls.toMutableList()
                val idx = calls.indexOfLast { it.name == e.name && it.input.isBlank() }
                if (idx >= 0) {
                    calls[idx] = ToolCallRecord(e.name, e.input, calls[idx].output + "\n=== 结果 ===\n" + e.output.take(4000))
                } else {
                    calls.add(ToolCallRecord(e.name, e.input, e.output.take(4000)))
                }
                msg.copy(toolCalls = calls)
            }
            "usage" -> {
                totalCacheHit += e.cacheHitTokens
                totalCacheMiss += e.cacheMissTokens
                _cacheStats.value = DeepSeekClient.UsageInfo(
                    promptTokens = 0L, completionTokens = 0L,
                    cacheHitTokens = totalCacheHit, cacheMissTokens = totalCacheMiss
                )
            }
            "done" -> updateLastAsst { msg ->
                val text = when {
                    e.error == "stopped" ->
                        if (e.text.isBlank()) "⏹ 已停止生成。" else "${e.text}\n\n⏹ 已手动停止。"
                    e.error.isNotBlank() ->
                        if (e.text.isBlank()) "❌ ${e.error}" else "${e.text}\n\n❌ ${e.error}"
                    else -> e.text.ifBlank { "（模型未返回内容）" }
                }
                msg.copy(text = text, roundSec = e.roundSec)
            }
            "stop" -> stopCurrentRound()   // 控制端点停止：终止本地轮次
        }
    }

    /** 持久化缓存累计统计（冷启动恢复用） */
    private fun saveCacheStats() {
        try {
            prefs.edit()
                .putLong("cache_hit_tokens", totalCacheHit)
                .putLong("cache_miss_tokens", totalCacheMiss)
                .apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 启动迁移：对全部已存会话消息做敏感打码（旧版本对话历史里的密码明文）。
     * 打码不可逆且只在启动时执行一次；AI 已存库的密码值精确替换 + 关键词正则兜底。
     */
    private fun maskStoredSessions() {
        try {
            var changed = false
            val masked = _sessions.value.map { s ->
                val values = CredentialVault.allValues(getApplication(), s.id)
                val newMsgs = s.messages.map { m -> m.copy(text = SensitiveMasker.mask(m.text, values)) }
                if (newMsgs != s.messages) changed = true
                s.copy(messages = newMsgs)
            }
            if (changed) {
                _sessions.value = masked
                saveSessions()
            }
        } catch (_: Exception) {
        }
    }

    /** 从加密存储重新读取 API Key（页面每次显示时调用，设置页保存后生效），并实际验证连接 */
    fun refreshKey() {
        key = SecurePrefs.getApiKey(getApplication())?.trim().orEmpty()
        _apiKeyReady.value = key.isNotEmpty()
        // 模型名解析：手动选择优先 → API 返回的第一个（设置页拉取后缓存 default_model）→ 硬编码兜底
        val model = try {
            val chosen = prefs.getString("model_name", null)?.trim()?.ifBlank { null }
            val apiFirst = prefs.getString("default_model", null)?.trim()?.ifBlank { null }
            chosen ?: apiFirst
        } catch (e: Exception) {
            null
        }
        _modelName.value = model ?: DeepSeekClient.DEFAULT_MODEL
        checkServer()
    }

    /**
     * 网络变化监听（用户反馈：无网切到有网后连接状态不刷新）：
     * 断网 → 立即显示"无法连接"；网络恢复 → 延迟 2 秒（连接建立中）自动重检服务器状态。
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            _serverStatus.value = DeepSeekClient.ServerStatus.FAIL
            _checkingServer.value = false
        }

        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                delay(2000)
                checkServer()
            }
        }
    }

    init {
        try {
            val cm = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // 注册失败不影响功能（仅失去自动刷新）
        }
    }

    override fun onCleared() {
        AiSyncBus.localListener = null   // 注销三端同步监听
        try {
            val cm = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
        }
        super.onCleared()
    }

    /** 实际连接 DeepSeek 服务器验证 API Key（GET /models），结果驱动"连接服务器正常"文案 */
    private fun checkServer() {
        if (key.isEmpty()) {
            _serverStatus.value = null
            _checkingServer.value = false
            return
        }
        if (_checkingServer.value) return
        _checkingServer.value = true
        viewModelScope.launch {
            _serverStatus.value = DeepSeekClient.checkServer(key)
            _checkingServer.value = false
            refreshBalance() // 连接检测成功时顺带拉余额
        }
    }

    /**
     * 查询 DeepSeek 账户余额（AI 页状态行实时显示"💰 余额 ¥xx"）。
     * 查询失败静默保持旧值（余额接口异常不影响对话主链路）。
     */
    private fun refreshBalance() {
        if (key.isEmpty()) {
            _balance.value = null
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val b = DeepSeekClient.getBalance(key)
            if (b != null && b.totalBalance.isNotBlank()) {
                // StateFlow 任意线程写安全；金额格式化为两位小数显示
                _balance.value = "¥" + (b.totalBalance.toDoubleOrNull()?.let { String.format(java.util.Locale.CHINA, "%.2f", it) } ?: b.totalBalance)
            }
        }
    }

    /**
     * 清空当前会话消息（会话保留、标题复位；**本会话记忆与 md 档案保留**——重新聊天时
     * AI 仍从记忆/档案拿到关键信息）。同时重置压缩摘要缓存（丢弃旧上下文，防旧摘要污染新对话）。
     */
    fun clearChat() {
        clearSession(_currentSessionId.value)
    }

    /** 清空指定会话的消息（会话列表弹窗『清空』按钮；不清当前会话时不动界面消息列表） */
    fun clearSession(id: String) {
        if (id.isBlank()) return
        _sessions.value = _sessions.value.map { s ->
            if (s.id == id) s.copy(messages = emptyList(), updatedAt = System.currentTimeMillis()) else s
        }
        if (_currentSessionId.value == id) {
            _messages.value = emptyList()
            resetCompressCache()
        }
        saveSessions()
    }

    /**
     * 删除一轮对话（三端同步之 PDA 屏端）：按被点气泡的时间戳定位——user 或 assistant 气泡
     * 都能触发（一轮 = user 消息 + 后随 assistant，到下一条 user 前），该轮不再进入 AI 上下文
     * （避免污染后续推理）。标题：手动命名（titleCustom）保留；否则按剩余首条 user 重新生成
     * （无则回「新会话」）。只删消息，记忆/档案/凭据不受影响；进行中的轮次由 UI 层 busy 挡住。
     */
    fun deleteRound(roundTs: Long) {
        if (roundTs <= 0) return
        val sid = _currentSessionId.value
        if (sid.isBlank()) return
        val s = _sessions.value.find { it.id == sid } ?: return
        val msgs = s.messages
        // 先按时间戳找到被点的气泡，再向前找到该轮 user 起点（assistant 气泡的时间戳
        // 不等于该轮 user 的时间戳，不能直接匹配 user）
        val ti = msgs.indexOfFirst { it.timestamp == roundTs }
        if (ti < 0) return
        var si = ti
        while (si > 0 && msgs[si].role != "user") si--
        // 删除范围：[si, 下一条 user 之前)（本轮 user + 后随 assistant）
        val ei = msgs.withIndex().indexOfFirst { (i, m) -> i > si && m.role == "user" }
        val end = if (ei >= 0) ei else msgs.size
        val newMsgs = msgs.filterIndexed { i, _ -> i < si || i >= end }
        val newTitle = if (s.titleCustom) s.title
        else newMsgs.firstOrNull { it.role == "user" }?.text?.take(24) ?: "新会话"
        _sessions.value = _sessions.value.map {
            if (it.id == sid) {
                it.copy(messages = newMsgs, title = newTitle, updatedAt = System.currentTimeMillis())
            } else it
        }
        _messages.value = newMsgs
        saveSessions()
    }

    /** 新建会话 */
    fun newSession() {
        val s = ChatSession(UUID.randomUUID().toString(), "新会话", System.currentTimeMillis(), emptyList())
        _sessions.value = listOf(s) + _sessions.value
        _currentSessionId.value = s.id
        _messages.value = emptyList()
        markActiveSession(s.id)
        resetCompressCache()
        saveSessions()
    }

    /** 重命名会话（会话列表『编辑』按钮；用户命名后不再被首条消息自动覆盖，上限 32 字） */
    fun renameSession(id: String, newTitle: String) {
        val t = newTitle.trim()
        if (id.isBlank() || t.isBlank()) return
        _sessions.value = _sessions.value.map { s ->
            if (s.id == id) s.copy(title = t.take(32), titleCustom = true) else s
        }
        saveSessions()
    }

    /** 切换会话（加载该会话消息作为上下文继续对话；清理末尾中断残留空占位） */
    fun switchSession(id: String) {
        val s = _sessions.value.find { it.id == id } ?: return
        _currentSessionId.value = id
        var msgs = s.messages
        // 该会话末尾是空 assistant 占位 = 上次在此会话被中断（进程被杀/切后台）：清理并提示
        val tail = msgs.lastOrNull()
        if (tail != null && tail.role == "assistant" && tail.text.isBlank()) {
            msgs = msgs.dropLast(1)
            _sessions.value = _sessions.value.map { if (it.id == id) it.copy(messages = msgs) else it }
            _interrupted.value = true
            saveSessions()
        }
        _messages.value = msgs
        markActiveSession(id)
        resetCompressCache()
    }

    /** 切换会话/新建会话时重置压缩摘要缓存（每个客户场景独立，不跨会话复用摘要） */
    private fun resetCompressCache() {
        summaryText = null
        summarizedCount = 0
        totalCacheHit = 0L
        totalCacheMiss = 0L
        _cacheStats.value = null
        saveCacheStats() // 同步清零持久化累计（否则冷启动会恢复旧值，与清空后的显示不一致）
    }

    /** 删除会话：**该会话对应的 AI 记忆、资料档案（md）、凭据保险库一并删除**；删除当前会话后自动切到最近一个 */
    fun deleteSession(id: String) {
        // 会话与记忆/资料/凭据对应：删除会话时同步删除该会话的记忆、md 档案与保险库凭据
        AiMemory.removeBySession(getApplication(), id)
        AiNotes.removeBySession(getApplication(), id)
        CredentialVault.removeBySession(getApplication(), id)
        val rest = _sessions.value.filterNot { it.id == id }
        _sessions.value = rest
        if (rest.isEmpty()) {
            newSession()
        } else {
            if (_currentSessionId.value == id) {
                val first = rest.first()
                _currentSessionId.value = first.id
                _messages.value = first.messages
                markActiveSession(first.id)
            }
            saveSessions()
        }
    }

    /**
     * 标记当前活跃会话 id 到 prefs（agent/active_session_id）：
     * AgentTools.remember_info 存记忆时读取它，保证**记忆与会话 id 严格对应**。
     * 所有会话切换点（新建/切换/删除/恢复）都必须调用。
     */
    private fun markActiveSession(id: String) {
        try {
            prefs.edit().putString("active_session_id", id).apply()
        } catch (_: Exception) {
        }
    }

    /** 会话持久化存储键 */
    private companion object {
        const val KEY_SESSIONS = "chat_sessions"
    }

    /** 持久化全部会话到本地（含消息历史与时间戳，重启后恢复上下文） */
    private fun saveSessions() {
        try {
            val arr = JSONArray()
            _sessions.value.forEach { s ->
                val msgArr = JSONArray()
                // 全量落盘（含末尾空 assistant 占位——中断恢复检测依据，恢复时自动清理）
                s.messages.forEach { m ->
                    val msgObj = JSONObject()
                        .put("role", m.role)
                        .put("text", m.text)
                        .put("ts", m.timestamp)
                    // 思考链、工具调用记录、本轮耗时持久化（界面折叠展示、重启可恢复；
                    // 2026-08-18 修复：工具入参/输出落盘放宽至 4000/8000，此前 2000/4000 截断长输出）
                    m.reasoning?.let { msgObj.put("reasoning", it) }
                    if (m.roundSec > 0) msgObj.put("roundSec", m.roundSec)
                    if (m.toolCalls.isNotEmpty()) {
                        msgObj.put(
                            "toolCalls",
                            JSONArray().apply {
                                m.toolCalls.forEach { tc ->
                                    put(
                                        JSONObject()
                                            .put("name", tc.name)
                                            .put("input", tc.input.take(4000))
                                            .put("output", tc.output.take(8000))
                                    )
                                }
                            }
                        )
                    }
                    msgArr.put(msgObj)
                }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("updatedAt", s.updatedAt)
                        .put("titleCustom", s.titleCustom)
                        .put("messages", msgArr)
                )
            }
            prefs.edit().putString(KEY_SESSIONS, JSONObject().put("sessions", arr).toString()).apply()
        } catch (_: Exception) {
        }
    }

    /** 恢复全部会话（按更新时间倒序；旧版本无 ts 字段的消息给默认时间戳） */
    private fun loadSessions() {
        try {
            val raw = prefs.getString(KEY_SESSIONS, null) ?: return
            val arr = JSONObject(raw).optJSONArray("sessions") ?: return
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val msgArr = o.optJSONArray("messages") ?: JSONArray()
                val msgs = (0 until msgArr.length()).mapNotNull { j ->
                    val m = msgArr.getJSONObject(j)
                    val role = m.optString("role")
                    val text = m.optString("text")
                    // 空 text 的 assistant 占位也恢复（中断检测依据）；恢复时 init/switchSession 统一清理
                    if (role == "user" || role == "assistant") {
                        val reasoning = m.optString("reasoning").ifBlank { null }
                        val toolCalls = m.optJSONArray("toolCalls")?.let { arr ->
                            (0 until arr.length()).mapNotNull { k ->
                                val tc = arr.optJSONObject(k) ?: return@mapNotNull null
                                ToolCallRecord(
                                    name = tc.optString("name"),
                                    input = tc.optString("input"),
                                    output = tc.optString("output")
                                )
                            }
                        } ?: emptyList()
                        UiMsg(
                            role, text,
                            m.optLong("ts", 0L).takeIf { it > 0 } ?: o.optLong("updatedAt", 0L),
                            reasoning,
                            toolCalls,
                            m.optLong("roundSec", 0L)
                        )
                    } else null
                }
                ChatSession(
                    o.optString("id"),
                    o.optString("title", "新会话"),
                    o.optLong("updatedAt"),
                    msgs,
                    o.optBoolean("titleCustom", false)
                )
            }
            if (list.isNotEmpty()) {
                _sessions.value = list.sortedByDescending { it.updatedAt }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * AI 调 mask_credential 工具触发的打码：把**已落盘的会话消息副本**（sessions 内历史）
     * 中的密码明文精确替换为 ***（仅 AI 指定/保险库已存的值，零误伤）。
     * 不打 UI（界面保留原样，用户可看完整值）；当前轮消息由 persistCurrentMessages 收尾打码兜底。
     */
    private fun maskMessagesNow(values: List<String>) {
        if (values.isEmpty()) return
        // 主线程写 state（AI 工具在 IO 线程回调；跨线程写 Compose state 会触发并发修改崩溃）
        onMain {
            try {
                pendingMask.addAll(values)
                val id = _currentSessionId.value
                if (id.isBlank()) return@onMain
                var changed = false
                _sessions.value = _sessions.value.map { s ->
                    if (s.id == id) {
                        val ms = s.messages.map { m -> m.copy(text = SensitiveMasker.maskExact(m.text, values)) }
                        if (ms != s.messages) {
                            changed = true
                            s.copy(messages = ms)
                        } else s
                    } else s
                }
                if (changed) saveSessions()
            } catch (_: Exception) {
            }
        }
    }

    /** 将当前消息写回当前会话并持久化（标题取首条 user 消息前 24 字；用户手动命名的不覆盖） */
    private fun persistCurrentMessages() {
        val id = _currentSessionId.value
        if (id.isBlank()) return
        val uiMsgs = _messages.value
        // 落盘副本打码：用户消息与 AI 回复里的密码明文都替换为 ***（翻记录文件看不到）；
        // UI 保持原样——用户有权看完整密码值（探测结果等），落盘安全与界面可读两不误
        val values = CredentialVault.allValues(getApplication(), id) + pendingMask.toList()
        val msgs = uiMsgs.map { m -> m.copy(text = SensitiveMasker.maskExact(m.text, values)) }
        val autoTitle = uiMsgs.firstOrNull { it.role == "user" }?.text?.take(24) ?: "新会话"
        _sessions.value = _sessions.value.map { s ->
            if (s.id == id) {
                val title = if (s.titleCustom) s.title else autoTitle
                s.copy(messages = msgs, updatedAt = System.currentTimeMillis(), title = title)
            } else s
        }
        saveSessions()
    }

    /**
     * 发送自由文本对话。
     *
     * @param text 用户输入（会 trim；空文本直接忽略）
     * @return true 表示已入队发送；false 表示被忽略（空文本 / busy / 未配置 Key）
     */
    fun sendText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value || !_apiKeyReady.value) return false
        // 三端互斥：控制端正在对话时本地不让发（避免两轮并发抢工具输出）
        if (AiSyncBus.remoteBusy) {
            Toast.makeText(getApplication(), "控制端正在对话，请稍候", Toast.LENGTH_SHORT).show()
            return false
        }

        _stoppedByUser = false
        _interrupted.value = false // 新一轮开始，清除"上次未完成"提示
        // 本轮 user 消息（显式构造拿 timestamp：ai_done 的 userTs 回传用，控制端单轮删除定位）
        val userMsg = UiMsg("user", trimmed)
        AiSyncBus.setLocalBusy(true)
        currentJob = viewModelScope.launch {
            _busy.value = true
            // 防休眠锁：AI 轮次期间持有（思考+工具长任务，锁屏/Doze 会暂停 CPU 与网络导致中断——
            // 现场装维常放口袋等结果；30 分钟超时自动释放兜底防泄漏，平时无轮次不持锁）
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toolbox:ai_round")
            runCatching { wakeLock?.acquire(30 * 60 * 1000L) }
            val roundStart = System.currentTimeMillis()
            // 追加 user 消息 + 空 assistant 占位（流式内容追加到占位），
            // **立即落盘 user 消息**：返回桌面/进程被杀时消息不丢（恢复时提示未完成）
            _messages.value = _messages.value + userMsg
            _messages.value = _messages.value + UiMsg("assistant", "")
            runCatching { persistCurrentMessages() }
            // 三端实时同步：user 消息实时广播给控制端
            AiSyncBus.publish(
                AiSyncBus.Event(kind = "user_msg", sessionId = _currentSessionId.value, text = trimmed)
            )
            try {
                // 历史 = [system] + 最近 30 轮完整对话（超长消息截断，节省费用；
                // 工具调用细节不重放——模型只看到 user/assistant 文本结论）
                // 上下文超限自动降级重试：先按完整历史跑，若报「上下文上限」则
                // 只带最近 5 轮 + 全量摘要重试一次；再失败走外层 catch 提示用户
                var reply = ""
                // 上下文预算驱动压缩（用户拍板）：按上一轮真实 prompt_tokens 选档——
                // ≥80 万（80%）强制只带最近 5 轮 + 全量摘要；≥50 万（50%）开始自动压缩（15 轮）；
                // 其余正常 30 轮。上轮 token 由 onUsage 记录，无记录（首轮）按默认档。
                var historyRounds = when {
                    lastPromptTokens > 800_000L -> 5
                    lastPromptTokens > 500_000L -> 15
                    else -> maxHistoryRounds
                }
                var attempt = 0
                while (true) {
                    try {
                        val history = buildHistory(_messages.value.dropLast(1), historyRounds)
                        // Agent 循环：模型可主动调用诊断工具（scan_lan 等），
                        // onStatus 更新工具调用提示行；onDelta 非流式收尾一次回调
                        reply = withContext(Dispatchers.IO) {
                    AgentEngine.runAgentLoop(
                        context = getApplication(),
                        apiKey = key,
                        history = history,
                        onStatus = { s ->
                            onMain { _status.value = s }
                            AiSyncBus.publish(AiSyncBus.Event(kind = "status", sessionId = _currentSessionId.value, text = s))
                        },
                        onDelta = { delta ->
                            // 工具循环结束，最终回答写入 assistant 占位（触发重组）
                            onMain {
                                val list = _messages.value.toMutableList()
                                val last = list.lastOrNull()
                                if (last != null && last.role == "assistant") {
                                    list[list.size - 1] = last.copy(text = delta)
                                    _messages.value = list
                                }
                            }
                            // 三端实时同步：正文增量（工具轮过渡语/最终回答）实时广播给控制端
                            AiSyncBus.publish(AiSyncBus.Event(kind = "delta", sessionId = _currentSessionId.value, text = delta))
                        },
                        toolGate = ::toolGate,
                        onMaskRequest = ::maskMessagesNow,
                        onReasoning = { piece ->
                            // 思考链增量：累积到当前 assistant 占位的 reasoning 字段（UI 实时显示）
                            onMain {
                                val list = _messages.value.toMutableList()
                                val last = list.lastOrNull()
                                if (last != null && last.role == "assistant") {
                                    list[list.size - 1] = last.copy(reasoning = (last.reasoning ?: "") + piece)
                                    _messages.value = list
                                }
                            }
                            AiSyncBus.publish(AiSyncBus.Event(kind = "reasoning", sessionId = _currentSessionId.value, text = piece))
                        },
                        onToolResult = { name, input, output ->
                            // 工具调用记录：更新最后一条同工具记录（input + 最终输出拼到日志后）
                            onMain {
                                val list = _messages.value.toMutableList()
                                val last = list.lastOrNull()
                                if (last != null && last.role == "assistant") {
                                    val calls = last.toolCalls.toMutableList()
                                    val idx = calls.indexOfLast { it.name == name && it.input.isBlank() }
                                    if (idx >= 0) {
                                        calls[idx] = ToolCallRecord(
                                            name = name,
                                            input = input,
                                            output = calls[idx].output + "\n=== 结果 ===\n" + output.take(4000)
                                        )
                                    } else {
                                        calls.add(ToolCallRecord(name, input, output.take(4000)))
                                    }
                                    list[list.size - 1] = last.copy(toolCalls = calls)
                                    _messages.value = list
                                }
                            }
                            AiSyncBus.publish(
                                AiSyncBus.Event(kind = "tool_result", sessionId = _currentSessionId.value, name = name, input = input, output = output)
                            )
                        },
                        onToolLog = { line ->
                            // 工具过程实时日志：新工具开始建占位记录，后续日志流式追加到 output
                            onMain {
                                val list = _messages.value.toMutableList()
                                val last = list.lastOrNull()
                                if (last != null && last.role == "assistant") {
                                    val calls = last.toolCalls.toMutableList()
                                    if (line.startsWith("▶ 开始工具 ")) {
                                        val name = line.removePrefix("▶ 开始工具 ").substringBefore("（")
                                        calls.add(ToolCallRecord(name, "", line))
                                    } else if (calls.isNotEmpty()) {
                                        val idx = calls.size - 1
                                        calls[idx] = calls[idx].copy(output = calls[idx].output + "\n" + line)
                                    }
                                    list[list.size - 1] = last.copy(toolCalls = calls)
                                    _messages.value = list
                                }
                            }
                            AiSyncBus.publish(AiSyncBus.Event(kind = "tool_log", sessionId = _currentSessionId.value, text = line))
                        },
                        onUsage = { usage ->
                            // 累计缓存命中/未命中（跨请求累加），UI 显示命中率
                            onMain {
                                totalCacheHit += usage.cacheHitTokens
                                totalCacheMiss += usage.cacheMissTokens
                                lastPromptTokens = usage.promptTokens   // 上下文规模实测值（压缩档位依据）
                                _cacheStats.value = DeepSeekClient.UsageInfo(
                                    promptTokens = usage.promptTokens,
                                    completionTokens = usage.completionTokens,
                                    cacheHitTokens = totalCacheHit,
                                    cacheMissTokens = totalCacheMiss
                                )
                            }
                            AiSyncBus.publish(
                                AiSyncBus.Event(kind = "usage", sessionId = _currentSessionId.value, cacheHitTokens = usage.cacheHitTokens, cacheMissTokens = usage.cacheMissTokens)
                            )
                        },
                        model = _modelName.value
                    )
                        }
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e   // 停止按钮取消：直接上抛，不降级重试
                    } catch (e: Exception) {
                        // 上下文超限（friendlyError 已映射为「对话内容太长，已超出模型上下文上限」）
                        if (attempt == 0 && (e.message ?: "").contains("上下文上限")) {
                            attempt = 1
                            historyRounds = 5
                            summaryText = null        // 摘要缓存失效：降级后强制重新压缩更早历史
                            summarizedCount = 0
                            continue
                        }
                        throw e
                    }
                }
                // 完成后用完整回复兜底（与 onDelta 写入一致；空回复给占位文案）
                val list = _messages.value.toMutableList()
                if (list.isNotEmpty()) {
                    val last = list.lastOrNull()
                    list[list.size - 1] = (last ?: UiMsg("assistant", "")).copy(
                        text = reply.ifBlank { "（模型未返回内容）" }
                    )
                    _messages.value = list
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点停止：保留已产生的思考/工具日志，正文标记"已停止"（部分内容可见）
                val list = _messages.value.toMutableList()
                if (list.isNotEmpty()) {
                    val last = list.lastOrNull()
                    val partial = (last ?: UiMsg("assistant", "")).text
                    list[list.size - 1] = (last ?: UiMsg("assistant", "")).copy(
                        text = if (partial.isBlank()) "⏹ 已停止生成。" else "$partial\n\n⏹ 已手动停止。"
                    )
                    _messages.value = list
                }
                throw e // 协程取消语义保持（finally 仍执行收尾）
            } catch (e: Exception) {
                // 出错：替换占位正文为错误消息，但保留已显示的思考过程与工具日志（过程可见，便于定位失败环节）
                // 网络异常特判为中文提示（HTTP 错误已在 DeepSeekClient.friendlyError 映射成中文）
                val msg = when {
                    e is java.net.SocketTimeoutException -> "网络超时，请检查网络后重试"
                    e is java.io.IOException -> "网络连接失败，请检查网络后重试"
                    (e.message ?: "").contains("上下文上限") ->
                        "对话内容太长，已超出模型上下文上限——关键信息 AI 已记住/存档，建议清空会话或新建会话继续"
                    else -> e.message ?: "未知错误"
                }
                val list = _messages.value.toMutableList()
                if (list.isNotEmpty()) {
                    val last = list.lastOrNull()
                    list[list.size - 1] = (last ?: UiMsg("assistant", "")).copy(
                        text = "分析失败：$msg"
                    )
                    _messages.value = list
                }
            } finally {
                _status.value = null
                _busy.value = false
                currentJob = null
                // 释放防休眠锁（AI 轮次结束）
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                // 本轮耗时（秒）写入最后一条 assistant 消息：气泡下方时间戳旁显示"本轮 X 秒"
                val elapsedSec = ((System.currentTimeMillis() - roundStart) / 1000).coerceAtLeast(0)
                val list = _messages.value.toMutableList()
                if (list.isNotEmpty() && list.lastOrNull()?.role == "assistant") {
                    list[list.size - 1] = list.last().copy(roundSec = elapsedSec)
                    _messages.value = list
                }
                persistCurrentMessages() // 完成后持久化当前会话（含历史）
                saveCacheStats() // 缓存统计持久化（冷启动不丢累计）
                refreshBalance() // 每轮完成后刷新余额（消耗实时可见）
                // 三端实时同步：本轮完成事件广播给控制端（含 userTs 供单轮删除定位）
                val finalText = _messages.value.lastOrNull()?.takeIf { it.role == "assistant" }?.text ?: ""
                AiSyncBus.publish(
                    AiSyncBus.Event(
                        kind = "done", sessionId = _currentSessionId.value,
                        text = finalText, roundSec = elapsedSec, userTs = userMsg.timestamp,
                        error = if (_stoppedByUser) "stopped" else ""
                    )
                )
                AiSyncBus.setLocalBusy(false)
            }
        }
        return true
    }

    /** 停止当前轮：取消发送协程 + 终止正在执行的 root 命令（长命令如 tcpdump 抓包立即生效），保留已产生的部分内容 */
    fun stopCurrentRound() {
        if (!_busy.value) return
        _stoppedByUser = true
        com.installer.toolbox.core.agent.RootShell.cancelCurrent()
        currentJob?.cancel()
    }

}
