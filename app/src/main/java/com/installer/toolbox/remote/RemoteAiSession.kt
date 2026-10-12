package com.installer.toolbox.remote

import android.content.Context
import android.os.PowerManager
import com.installer.toolbox.core.agent.AgentEngine
import com.installer.toolbox.core.agent.AgentPrompts
import com.installer.toolbox.core.agent.DeepSeekClient
import com.installer.toolbox.core.agent.support.AiMemory
import com.installer.toolbox.core.agent.support.AiNotes
import com.installer.toolbox.core.agent.support.CredentialVault
import com.installer.toolbox.util.SecurePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程 AI 会话（M2 被控端 · 会话感知版）：与控制端一对一，**直接读写 App 的 chat_sessions 存储**，
 * 与 PDA 屏上 AI 页完全同一份会话数据（思考/工具记录/记忆/档案/凭据按动态 sessionId 隔离）。
 *
 * 设计（REMOTE_CONTROL_DESIGN.md 3.3/3.4 + 会话同步需求）：
 * - 会话不再固定 "remote"：读写 SharedPreferences "agent"/chat_sessions（JSON 结构与 AiViewModel 完全一致），
 *   控制端切会话/新建/清空/删除/重命名 → 改的是同一份数据，App AI 页重进/重启即见一致状态；
 * - 复用链路：本机记忆/档案清单/凭据清单注入（按当前 sessionId）→ AgentEngine.runAgentLoop → 全部回调
 *   转 ai_event / ai_done 帧即时外发（逐帧不缓存）；
 * - 一轮完成后把 user 消息与 assistant 消息（带 reasoning/toolCalls/roundSec）写入当前会话并落盘，
 *   PDA 屏上打开该会话能看到思考过程与工具折叠记录；
 * - 远程模式下本地一切弹窗静默：toolGate 只对 root_shell 做远程审批（发 approval_req → 挂
 *   CompletableDeferred 等 handleApprovalResp，120 秒超时默认拒绝），其余工具放行；
 * - 所有会话 JSON 读写统一在 Dispatchers.IO 协程执行，sessionLock 保证同一份 chat_sessions 的读改写原子性。
 *
 * 本类运行在服务上下文，**不持有任何 Compose state**；并发状态只写内部变量
 * （AtomicBoolean / @Volatile），协程统一跑在 Dispatchers.IO。
 */
class RemoteAiSession(
    context: Context,
    private val onSend: (JSONObject) -> Unit
) {

    /** 会话内一条消息（字段与 AiViewModel.UiMsg 对齐，落盘 key 完全一致） */
    private data class Msg(
        val role: String,
        val text: String,
        val ts: Long = System.currentTimeMillis(),
        val reasoning: String? = null,
        val toolCalls: List<ToolCallRecord> = emptyList(),
        val roundSec: Long = 0
    )

    /** 单条工具调用记录（与 AiViewModel.ToolCallRecord 对齐） */
    private data class ToolCallRecord(
        val name: String,
        val input: String,
        val output: String
    )

    /** 会话（与 AiViewModel.ChatSession 对齐：id/title/updatedAt/titleCustom/messages） */
    private data class Session(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<Msg>,
        val titleCustom: Boolean = false
    )

    /** 应用上下文（避免持有 Service 引用造成潜在泄漏） */
    private val appContext = context.applicationContext

    /** "agent" SharedPreferences：模型名 / 活跃会话 id / chat_sessions 与 App AI 页共用同一文件 */
    private val prefs = appContext.getSharedPreferences("agent", Context.MODE_PRIVATE)

    /** 独立协程作用域（SupervisorJob + IO）：远程 AI 轮次不随任何页面生命周期取消 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 会话读写锁（保证同一份 chat_sessions 的读改写原子性；SharedPreferences 本身线程安全） */
    private val sessionLock = Any()

    /** 是否正在执行一轮 AI（原子标志：跨 OkHttp 回调线程与 IO 协程安全读写） */
    private val busyFlag = AtomicBoolean(false)

    /** 是否忙（Service 状态上报 busy 用；控制端借此判断能否再发消息） */
    val busy: Boolean get() = busyFlag.get()

    /** 当前远程会话 id（动态：默认取 active_session_id 或最新会话；无会话自动新建） */
    @Volatile
    private var currentSessionId: String = ""

    /** 当前轮协程（handleStop 取消它，中断 HTTP 与工具执行） */
    @Volatile
    private var currentJob: Job? = null

    /** 挂起中的远程审批：reqId → 等待结果的 CompletableDeferred（并发安全，Map 跨线程） */
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** 未决审批的命令缓存：reqId → command（控制端重连后 pending_approval_query 重发 approval_req 用） */
    private val pendingApprovalCommands = ConcurrentHashMap<String, String>()

    /** 上一轮实测 prompt tokens（上下文规模真实值，驱动 50%/80% 自动压缩档位；首次为 0=满档） */
    @Volatile
    private var lastPromptTokens = 0L

    init {
        // 启动时加载会话并确定当前会话：默认取 active_session_id（App AI 页最近活跃），
        // 否则取最新会话（updatedAt 最大）；一个会话都没有则自动新建「新会话」。
        val loaded = synchronized(sessionLock) { loadSessionsUnlocked() }
        val active = prefs.getString("active_session_id", null)
        val target = loaded.firstOrNull { it.id == active } ?: loaded.maxByOrNull { it.updatedAt }
        currentSessionId = target?.id ?: synchronized(sessionLock) {
            val nid = UUID.randomUUID().toString()
            val l = loadSessionsUnlocked().toMutableList()
            l.add(0, Session(nid, "新会话", System.currentTimeMillis(), emptyList()))
            saveSessionsUnlocked(l)
            nid
        }
    }

    /**
     * 处理控制端发来的远程 AI 消息（{t:"ai_chat", roundId, text, authorized}）。
     *
     * @param roundId    本轮会话 ID（服务器下发，回传 ai_event/ai_done 时对齐）
     * @param text       控制端消息文本
     * @param authorized 兼容旧协议字段（设备所有者授权功能已取消，忽略）
     */
    fun handleChat(roundId: String, text: String, authorized: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // 三端互斥：PDA 屏上正在对话时拒绝远程新轮（避免两轮并发抢工具输出）
        if (AiSyncBus.localBusy) {
            onSend(
                JSONObject()
                    .put("t", "ai_done")
                    .put("roundId", roundId)
                    .put("text", "")
                    .put("error", "PDA 屏上正在对话，请稍候")
            )
            return
        }
        // 三端实时同步：user 消息实时广播给 PDA 屏 AI 页（同进程事件桥）
        AiSyncBus.publish(AiSyncBus.Event(kind = "user_msg", source = "remote", sessionId = currentSessionId, text = trimmed))

        // busy：拒绝新一轮，回 ai_done 提示控制端（不重入）
        if (!busyFlag.compareAndSet(false, true)) {
            onSend(
                JSONObject()
                    .put("t", "ai_done")
                    .put("roundId", roundId)
                    .put("text", "")
                    .put("error", "正在执行上一任务，请稍候")
            )
            return
        }
        AiSyncBus.setRemoteBusy(true)   // 三端互斥：本地 AI 页此时拒绝发消息

        // API Key 读取（与 AiViewModel 同法：SecurePrefs 加密存储，明文自动迁移）
        val apiKey = SecurePrefs.getApiKey(appContext)?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            busyFlag.set(false)
            AiSyncBus.setRemoteBusy(false)   // 三端互斥解除
            onSend(
                JSONObject()
                    .put("t", "ai_done")
                    .put("roundId", roundId)
                    .put("text", "")
                    .put("error", "未配置 API Key，请在 App 设置页填写")
            )
            return
        }

        // 模型名（prefs "agent"/model_name，缺省 deepseek-v4-flash）
        val model = prefs.getString("model_name", null)?.trim()?.ifBlank { null }
            ?: DeepSeekClient.DEFAULT_MODEL

        currentJob = scope.launch {
            // 锁屏/Doze 下 CPU 降频会导致远程轮次卡半天：轮次期间持有 PARTIAL_WAKE_LOCK
            // 保持 CPU 全速（现场 PDA 常插电锁屏放一旁；30 分钟超时自动释放兜底防泄漏，平时无轮次不持锁）
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toolbox:remote_round")
            runCatching { wakeLock?.acquire(30 * 60 * 1000L) }
            val roundStart = System.currentTimeMillis()
            // 本轮 user 消息的落盘 ts（生成一次，落盘与 ai_done.userTs 回传一致，
            // 控制端以此定位「删除这一轮」）
            val roundUserTs = System.currentTimeMillis()
            // 流式过程中已产生（onDelta 快照；用于停止时回传部分内容）
            var partialText = ""
            // 本轮思考链 / 工具调用记录（落盘到会话库，与 App AI 页 UiMsg 字段一致）
            val reasoning = StringBuilder()
            val toolCalls = mutableListOf<ToolCallRecord>()
            // 最终 assistant 正文（成功/停止/失败三种收尾统一落盘）
            var assistantText = ""
            try {
                // 活跃会话 id 临时切到当前会话，保证本轮 remember_info/write_md/read_md/save_credential
                // 等工具落库到当前会话（与注入侧 sessionId 对齐）；结束恢复 prev，避免污染 UI 会话。
                val prevActive = prefs.getString("active_session_id", null)
                prefs.edit().putString("active_session_id", currentSessionId).apply()

                // 上下文预算驱动压缩（用户拍板）：按上一轮真实 prompt_tokens 选档——
                // ≥80 万（80%）强制只带最近 5 轮；≥50 万（50%）开始自动压缩（15 轮）；其余 30 轮。
                // 远程轮次更早历史直接丢弃（关键信息在记忆/档案）；超限另有降级重试一次兜底
                var historyRounds = when {
                    lastPromptTokens > 800_000L -> 5
                    lastPromptTokens > 500_000L -> 15
                    else -> MAX_HISTORY_ROUNDS
                }
                var attempt = 0
                var reply = ""
                try {
                    while (true) {
                        try {
                            val history = buildHistory(trimmed, historyRounds)
                            reply = AgentEngine.runAgentLoop(
                        context = appContext,
                        apiKey = apiKey,
                        history = history,
                        onStatus = { s ->
                            sendEvent("status", s, roundId)
                            AiSyncBus.publish(AiSyncBus.Event(kind = "status", source = "remote", sessionId = currentSessionId, text = s))
                        },
                        onDelta = { d ->
                            partialText = d
                            // 正文快照流式外发（工具轮过渡语/最终回答逐行打印，最终回答覆盖过渡语）
                            sendEvent("delta", d, roundId)
                            AiSyncBus.publish(AiSyncBus.Event(kind = "delta", source = "remote", sessionId = currentSessionId, text = d))
                        },
                        toolGate = ::toolGate,
                        onReasoning = { piece ->
                            sendEvent("reasoning", piece, roundId)
                            reasoning.append(piece)
                            AiSyncBus.publish(AiSyncBus.Event(kind = "reasoning", source = "remote", sessionId = currentSessionId, text = piece))
                        },
                        onToolLog = { line ->
                            sendEvent("tool_log", line, roundId)
                            AiSyncBus.publish(AiSyncBus.Event(kind = "tool_log", source = "remote", sessionId = currentSessionId, text = line))
                            // 与 AiViewModel 一致的 toolCalls 累积：开始工具建占位、后续行追加输出
                            if (line.startsWith("▶ 开始工具 ")) {
                                val name = line.removePrefix("▶ 开始工具 ").substringBefore("（")
                                toolCalls.add(ToolCallRecord(name, "", line))
                            } else if (toolCalls.isNotEmpty()) {
                                val idx = toolCalls.size - 1
                                toolCalls[idx] = toolCalls[idx].copy(output = toolCalls[idx].output + "\n" + line)
                            }
                        },
                        onToolResult = { name, input, output ->
                            onSend(
                                JSONObject()
                                    .put("t", "ai_event")
                                    .put("kind", "tool_result")
                                    .put("roundId", roundId)
                                    .put(
                                        "data",
                                        JSONObject()
                                            .put("name", name)
                                            .put("input", input)
                                            .put("output", output)
                                    )
                            )
                            AiSyncBus.publish(
                                AiSyncBus.Event(kind = "tool_result", source = "remote", sessionId = currentSessionId, name = name, input = input, output = output)
                            )
                            // 与 AiViewModel 一致：按 name 匹配最后一张空 input 卡填 input+结果，否则新建
                            val idx = toolCalls.indexOfLast { it.name == name && it.input.isBlank() }
                            if (idx >= 0) {
                                toolCalls[idx] = ToolCallRecord(
                                    name, input,
                                    toolCalls[idx].output + "\n=== 结果 ===\n" + output.take(4000)
                                )
                            } else {
                                toolCalls.add(ToolCallRecord(name, input, output.take(4000)))
                            }
                        },
                        onUsage = { u ->
                            lastPromptTokens = u.promptTokens   // 上下文规模实测值（压缩档位依据）
                            onSend(
                                JSONObject()
                                    .put("t", "ai_event")
                                    .put("kind", "usage")
                                    .put("roundId", roundId)
                                    .put(
                                        "data",
                                        JSONObject()
                                            .put("cacheHitTokens", u.cacheHitTokens)
                                            .put("cacheMissTokens", u.cacheMissTokens)
                                    )
                            )
                            AiSyncBus.publish(
                                AiSyncBus.Event(kind = "usage", source = "remote", sessionId = currentSessionId, cacheHitTokens = u.cacheHitTokens, cacheMissTokens = u.cacheMissTokens)
                            )
                        },
                        model = model
                    )
                            break
                        } catch (e: CancellationException) {
                            throw e   // 控制端点停止：直接上抛，不降级重试
                        } catch (e: Exception) {
                            // 上下文超限降级重试一次（与 AiViewModel 对称）：只带最近 5 轮再试；
                            // 再失败抛到外层 catch 报错给控制端
                            if (attempt == 0 && (e.message ?: "").contains("上下文上限")) {
                                attempt = 1
                                historyRounds = 5
                                continue
                            }
                            throw e
                        }
                    }
                } finally {
                    // 恢复 UI 会话的活跃 id（本轮远程会话结束，避免污染 UI 会话后续记忆写入）
                    if (prevActive != null) {
                        prefs.edit().putString("active_session_id", prevActive).apply()
                    } else {
                        prefs.edit().remove("active_session_id").apply()
                    }
                }

                partialText = reply
                assistantText = reply.ifBlank { "（模型未返回内容）" }
                val roundSec = ((System.currentTimeMillis() - roundStart) / 1000).coerceAtLeast(0)
                onSend(
                    JSONObject()
                        .put("t", "ai_done")
                        .put("roundId", roundId)
                        .put("text", reply)
                        .put("roundSec", roundSec)
                        .put("userTs", roundUserTs)
                )
                AiSyncBus.publish(AiSyncBus.Event(kind = "done", source = "remote", sessionId = currentSessionId, text = reply, roundSec = roundSec, userTs = roundUserTs))
            } catch (e: CancellationException) {
                // 控制端点停止：保留已产生的部分内容（思考/工具日志已随 ai_event 流式外发），
                // 正文回传已产生的部分 text 并标记 error=stopped
                val stopSec = ((System.currentTimeMillis() - roundStart) / 1000).coerceAtLeast(0)
                onSend(
                    JSONObject()
                        .put("t", "ai_done")
                        .put("roundId", roundId)
                        .put("text", partialText)
                        .put("roundSec", stopSec)
                        .put("error", "stopped")
                        .put("userTs", roundUserTs)
                )
                assistantText = if (partialText.isBlank()) "⏹ 已停止生成。" else "$partialText\n\n⏹ 已手动停止。"
                AiSyncBus.publish(AiSyncBus.Event(kind = "done", source = "remote", sessionId = currentSessionId, text = partialText, roundSec = stopSec, userTs = roundUserTs, error = "stopped"))
            } catch (e: Exception) {
                // 异常：ai_done 带 error（网络/HTTP 错误已在 DeepSeekClient 映射为中文）
                val failSec = ((System.currentTimeMillis() - roundStart) / 1000).coerceAtLeast(0)
                onSend(
                    JSONObject()
                        .put("t", "ai_done")
                        .put("roundId", roundId)
                        .put("text", "")
                        .put("roundSec", failSec)
                        .put("error", e.message ?: "未知错误")
                        .put("userTs", roundUserTs)
                )
                val msg = when {
                    e is java.net.SocketTimeoutException -> "网络超时，请检查网络后重试"
                    e is java.io.IOException -> "网络连接失败，请检查网络后重试"
                    else -> e.message ?: "未知错误"
                }
                assistantText = "分析失败：$msg"
                AiSyncBus.publish(AiSyncBus.Event(kind = "done", source = "remote", sessionId = currentSessionId, text = "", roundSec = failSec, userTs = roundUserTs, error = e.message ?: "未知错误"))
            } finally {
                busyFlag.set(false)
                currentJob = null
                AiSyncBus.setRemoteBusy(false)   // 三端互斥解除
                runCatching { wakeLock?.release() }   // 防休眠锁释放（30 分钟超时自动释放兜底）
                // 本轮 user + assistant（带 reasoning/toolCalls/roundSec）写入当前会话并落盘
                val roundSec = ((System.currentTimeMillis() - roundStart) / 1000).coerceAtLeast(0)
                persistRound(trimmed, assistantText, reasoning.toString(), toolCalls, roundSec, roundUserTs)
                // 每轮完成后查询余额并推送给控制端（消耗实时可见）
                runCatching {
                    val balance = DeepSeekClient.getBalance(apiKey)
                    if (balance != null) {
                        onSend(
                            JSONObject()
                                .put("t", "ai_event")
                                .put("kind", "balance")
                                .put("roundId", roundId)
                                .put("data", balance.currency + " " + balance.totalBalance)
                        )
                    }
                }
            }
        }
    }

    /** 控制端停止当前轮（{t:"ai_stop", roundId}）：取消协程 + 终止正在执行的 root 命令（立即生效） */
    fun handleStop(roundId: String) {
        // 单并发会话：只跟踪一个进行中的 job，roundId 仅用于与帧对齐（v1 不校验）
        com.installer.toolbox.core.agent.RootShell.cancelCurrent() // 长命令（tcpdump 抓包等）立即终止
        currentJob?.cancel()
    }

    /**
     * 控制端审批响应（{t:"approval_resp", reqId, allow}）：唤醒挂起的 root_shell 审批。
     * 未找到对应 reqId（已超时/已答复）时静默忽略。
     */
    fun handleApprovalResp(reqId: String, allow: Boolean) {
        pendingApprovals.remove(reqId)?.complete(allow)
        pendingApprovalCommands.remove(reqId)
    }

    /**
     * 控制端重连后请求重发未决审批（{t:"pending_approval_query"}）：
     * 网络波动导致控制端断线时，此前发出去的 approval_req 可能丢失（server 不缓存），
     * 设备端会一直等到 120s 超时。控制端重连后发此帧 → 把所有仍挂起的审批重新下发，
     * 控制端重新弹窗，审批不丢、设备不白等。
     */
    fun handlePendingApprovalQuery() {
        if (pendingApprovalCommands.isEmpty()) return
        pendingApprovalCommands.forEach { (reqId, command) ->
            if (pendingApprovals.containsKey(reqId)) {
                onSend(
                    JSONObject()
                        .put("t", "approval_req")
                        .put("reqId", reqId)
                        .put("toolName", "root_shell")
                        .put("command", command)
                )
            }
        }
    }

    // ==================== 会话管理协议（控制端 → 设备） ====================

    /** 控制端请求会话列表（{t:"session_list"}；busy 时也允许——只读，不破坏进行中的轮次） */
    fun handleSessionList() {
        scope.launch { sendSessionListResp() }
    }

    /** 控制端切换当前会话（{t:"session_switch", id, beforeTs?}）：
     *  - 不带 beforeTs：切当前会话并回最近 5 条历史；
     *  - 带 beforeTs：分页取该时间戳之前（更早）的 5 条，控制页"加载更早"用（控制端会前插渲染） */
    fun handleSessionSwitch(id: String, beforeTs: Long) {
        if (id.isBlank()) return
        // 【修复 2026-08-17】busy 时仅拦截"切到别的会话"；切当前会话（网络波动重连后控制端拉历史恢复）放行——
        // 否则设备端 AI 正忙时控制端重连 session_switch 被拒，消息区空白/不刷新，体验中断
        if (busyFlag.get() && id != currentSessionId) { onSend(sessionErr("正在执行任务")); return }
        scope.launch {
            val result = synchronized(sessionLock) {
                val loaded = loadSessionsUnlocked()
                val s = loaded.firstOrNull { it.id == id }
                if (s == null) null else {
                    // 分页模式（beforeTs>0）不切换当前会话（只是加载更多历史）；首屏才切换
                    if (beforeTs <= 0) currentSessionId = id
                    s.messages
                }
            }
            if (result == null) {
                onSend(sessionErr("会话不存在或已被删除"))
            } else {
                // 分页：beforeTs>0 时取该时间戳之前（更早）的消息；首屏取最近。
                // 批次开头对齐到轮边界（user）：截取后若第一条是 assistant，多包含其前一条 user，
                // 保证控制端每条 assistant 都能配对所属轮的 ts（删除按钮不缺失），两页之间不拆轮。
                val total = if (beforeTs > 0) result.filter { it.ts < beforeTs } else result
                var from = (total.size - MAX_HISTORY_SWITCH).coerceAtLeast(0)
                if (from > 0 && total[from].role != "user") from--   // 第一条是 assistant → 多带前一条 user
                val page = total.subList(from, total.size)
                val hasMore = from > 0                               // 还有更早（且不与上一页重复）
                onSend(
                    JSONObject()
                        .put("t", "session_switched")
                        .put("id", id)
                        .put("history", messagesToJsonArray(page))
                        .put("hasMore", hasMore)
                )
            }
        }
    }

    /** 控制端新建会话（{t:"session_new"}）：新建「新会话」并回新列表 + currentId 指向新会话 */
    fun handleSessionNew() {
        if (busyFlag.get()) { onSend(sessionErr("正在执行任务")); return }
        scope.launch {
            synchronized(sessionLock) {
                val nid = UUID.randomUUID().toString()
                val list = loadSessionsUnlocked().toMutableList()
                list.add(0, Session(nid, "新会话", System.currentTimeMillis(), emptyList()))
                saveSessionsUnlocked(list)
                currentSessionId = nid
            }
            sendSessionListResp()
        }
    }

    /** 控制端清空指定会话消息（{t:"session_clear", id}）：标题保留；记忆/档案/凭据保留（与 App 清空语义一致） */
    fun handleSessionClear(id: String) {
        if (id.isBlank()) return
        if (busyFlag.get()) { onSend(sessionErr("正在执行任务")); return }
        scope.launch {
            synchronized(sessionLock) {
                val list = loadSessionsUnlocked().toMutableList()
                val idx = list.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(messages = emptyList(), updatedAt = System.currentTimeMillis())
                    saveSessionsUnlocked(list)
                }
            }
            sendSessionListResp()
        }
    }

    /** 控制端删除会话（{t:"session_delete", id}）：连带删该会话记忆/档案/凭据（与 App 删会话语义一致） */
    fun handleSessionDelete(id: String) {
        if (id.isBlank()) return
        if (busyFlag.get()) { onSend(sessionErr("正在执行任务")); return }
        scope.launch {
            synchronized(sessionLock) {
                val list = loadSessionsUnlocked().toMutableList()
                val idx = list.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    // 连带删：记忆 / 档案目录 / 凭据（core-agent support 已提供按会话删除 API）
                    AiMemory.removeBySession(appContext, id)
                    AiNotes.removeBySession(appContext, id)
                    CredentialVault.removeBySession(appContext, id)
                    list.removeAt(idx)
                    if (currentSessionId == id) {
                        // 删除的是当前会话：回退到最新会话；一个都不剩则新建
                        val first = list.maxByOrNull { it.updatedAt }
                        currentSessionId = if (first != null) first.id else {
                            val nid = UUID.randomUUID().toString()
                            list.add(0, Session(nid, "新会话", System.currentTimeMillis(), emptyList()))
                            nid
                        }
                    }
                    saveSessionsUnlocked(list)
                }
            }
            sendSessionListResp()
        }
    }

    /** 控制端重命名会话（{t:"session_rename", id, title}）：titleCustom=true，之后新消息不覆盖标题 */
    fun handleSessionRename(id: String, title: String) {
        val t = title.trim()
        if (id.isBlank() || t.isBlank()) return
        if (busyFlag.get()) { onSend(sessionErr("正在执行任务")); return }
        scope.launch {
            synchronized(sessionLock) {
                val list = loadSessionsUnlocked().toMutableList()
                val idx = list.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(title = t.take(TITLE_RENAME_MAX), titleCustom = true)
                    saveSessionsUnlocked(list)
                }
            }
            sendSessionListResp()
        }
    }

    // ==================== 记忆同步协议（控制端 ↔ 设备） ====================
    // 记忆与 App AI 页 🧠 弹窗同一份存储（AiMemory 按 sessionId 隔离），控制端可查看/单删/清空本会话

    /** 控制端请求当前会话记忆列表（{t:"memory_list"}；只读，busy 时也允许） */
    fun handleMemoryList() {
        scope.launch { sendMemoryListResp() }
    }

    /** 控制端删除单条记忆（{t:"memory_delete", text}）：按文本精确匹配删除，删完回新列表 */
    fun handleMemoryDelete(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        scope.launch {
            AiMemory.remove(appContext, t)
            sendMemoryListResp()
        }
    }

    /** 控制端清空当前会话记忆（{t:"memory_clear"}）：与 App 记忆弹窗「清空本会话」语义一致 */
    fun handleMemoryClear() {
        scope.launch {
            AiMemory.removeBySession(appContext, currentSessionId)
            sendMemoryListResp()
        }
    }

    /**
     * 控制端删除一轮对话（{t:"round_delete", id, ts}）：删除 ts 对应的 user 消息及其后随的
     * assistant 消息（直到下一条 user 前），该轮不再进入 AI 上下文（避免污染后续推理）。
     * 标题：titleCustom 保留；否则按剩余首条 user 重新生成（无 user 则回「新会话」）。
     * 成功回 round_deleted + 新会话列表；失败回 session_err。
     */
    fun handleRoundDelete(id: String, ts: Long) {
        if (id.isBlank() || ts <= 0) return
        if (busyFlag.get()) { onSend(sessionErr("正在执行任务")); return }
        scope.launch {
            val ok = synchronized(sessionLock) {
                val list = loadSessionsUnlocked().toMutableList()
                val idx = list.indexOfFirst { it.id == id }
                if (idx < 0) false
                else {
                    val s = list[idx]
                    val msgs = s.messages
                    val si = msgs.indexOfFirst { it.role == "user" && it.ts == ts }
                    if (si < 0) false
                    else {
                        // 删除范围：[si, 下一条 user 之前)（本轮 user + 后随 assistant）
                        val ei = msgs.withIndex().indexOfFirst { (i, m) -> i > si && m.role == "user" }
                        val end = if (ei >= 0) ei else msgs.size
                        val newMsgs = msgs.filterIndexed { i, _ -> i < si || i >= end }
                        val newTitle = if (s.titleCustom) s.title
                        else newMsgs.firstOrNull { it.role == "user" }?.text?.take(TITLE_MAX) ?: "新会话"
                        list[idx] = s.copy(messages = newMsgs, title = newTitle, updatedAt = System.currentTimeMillis())
                        saveSessionsUnlocked(list)
                        true
                    }
                }
            }
            if (ok) {
                onSend(JSONObject().put("t", "round_deleted").put("id", id).put("ts", ts))
                sendSessionListResp()
            } else {
                onSend(sessionErr("该轮消息不存在或已被删除"))
            }
        }
    }

    /** 下发当前会话记忆（{t:"memory_list_resp", id, items:[{text,ts}]}） */
    private fun sendMemoryListResp() {
        val items = AiMemory.ofSession(appContext, currentSessionId)
        val arr = JSONArray()
        items.forEach { m ->
            arr.put(JSONObject().put("text", m.text).put("ts", m.ts))
        }
        onSend(
            JSONObject()
                .put("t", "memory_list_resp")
                .put("id", currentSessionId)
                .put("items", arr)
        )
    }

    // ==================== 会话持久化（与 AiViewModel 同一份 chat_sessions） ====================

    /** 读全部会话（sessionLock 锁内调用；JSON 结构与 AiViewModel.loadSessions 完全一致） */
    private fun loadSessionsUnlocked(): List<Session> {
        return try {
            val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
            val arr = JSONObject(raw).optJSONArray("sessions") ?: return emptyList()
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val msgArr = o.optJSONArray("messages") ?: JSONArray()
                val total = msgArr.length()
                val msgs = (0 until total).mapNotNull { j ->
                    val m = msgArr.getJSONObject(j)
                    val role = m.optString("role")
                    val text = m.optString("text")
                    // 空 text 的 assistant 占位也恢复（中断检测依据），与 App 一致
                    if (role == "user" || role == "assistant") {
                        val reasoning = m.optString("reasoning").ifBlank { null }
                        val toolCalls = m.optJSONArray("toolCalls")?.let { tcArr ->
                            (0 until tcArr.length()).mapNotNull { k ->
                                val tc = tcArr.optJSONObject(k) ?: return@mapNotNull null
                                ToolCallRecord(tc.optString("name"), tc.optString("input"), tc.optString("output"))
                            }
                        } ?: emptyList()
                        // ts=0 的旧消息（远程功能上线前落盘）按数组下标合成 1,2,3… 递增 ts：
                        // 必须比所有真实 ts 更小（真实 ts 是毫秒级大数），否则分页 filter(ts<beforeTs)
                        // 永远取不到这批最旧消息。合成值仅在会话内唯一有序，分页/删除都在会话内定位。
                        val rawTs = m.optLong("ts", 0L)
                        val ts = if (rawTs > 0) rawTs else (j + 1L)
                        Msg(
                            role, text,
                            ts,
                            reasoning,
                            toolCalls,
                            m.optLong("roundSec", 0L)
                        )
                    } else null
                }
                Session(
                    o.optString("id"),
                    o.optString("title", "新会话"),
                    o.optLong("updatedAt"),
                    msgs,
                    o.optBoolean("titleCustom", false)
                )
            }
            if (list.isNotEmpty()) list.sortedByDescending { it.updatedAt } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 写全部会话（sessionLock 锁内调用；JSON 结构与 AiViewModel.saveSessions 完全一致） */
    private fun saveSessionsUnlocked(list: List<Session>) {
        try {
            val arr = JSONArray()
            list.forEach { s ->
                val msgArr = JSONArray()
                // 全量落盘（含末尾空 assistant 占位——中断恢复检测依据，恢复时自动清理）
                s.messages.forEach { m ->
                    val msgObj = JSONObject()
                        .put("role", m.role)
                        .put("text", m.text)
                        .put("ts", m.ts)
                    // 思考链、工具调用记录、本轮耗时持久化（界面折叠展示、重启可恢复）
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
        } catch (e: Exception) {
        }
    }

    /** 一轮完成后落盘：user + assistant（带 reasoning/toolCalls/roundSec），标题自动生成（titleCustom 不覆盖）。
     *  userTs 与 ai_done.userTs 一致（控制端「删除这一轮」定位用） */
    private fun persistRound(userText: String, assistantText: String, reasoning: String, toolCalls: List<ToolCallRecord>, roundSec: Long, userTs: Long) {
        synchronized(sessionLock) {
            val list = loadSessionsUnlocked().toMutableList()
            var idx = list.indexOfFirst { it.id == currentSessionId }
            if (idx < 0) {
                // 极端情况：当前会话已被删除（如 App 端并发删）→ 新建并写入
                val nid = UUID.randomUUID().toString()
                currentSessionId = nid
                list.add(0, Session(nid, "新会话", System.currentTimeMillis(), emptyList()))
                idx = 0
            }
            val s = list[idx]
            val userMsg = Msg("user", userText, userTs)
            val asstMsg = Msg(
                "assistant", assistantText,
                reasoning = reasoning.ifBlank { null },
                toolCalls = toolCalls,
                roundSec = roundSec
            )
            val newMessages = s.messages + userMsg + asstMsg
            // 标题：titleCustom 时不覆盖；否则取整会话首条 user 消息前 24 字（与 App 一致）
            val autoTitle = newMessages.firstOrNull { it.role == "user" }?.text?.take(TITLE_MAX) ?: "新会话"
            val newTitle = if (s.titleCustom) s.title else autoTitle
            list[idx] = s.copy(messages = newMessages, updatedAt = System.currentTimeMillis(), title = newTitle)
            saveSessionsUnlocked(list)
        }
    }

    /** 消息列表 → JSONArray（供 session_switched 下发历史；含 reasoning/toolCalls/roundSec；
     *  单条字段仅防极端超长帧设大上限（text/reasoning 2 万、工具入参 4 千/输出 8 千），
     *  正常长回复/长思考完整下发——历史回放不得截断内容（2026-08-18 修复：原 4000 截断致
     *  控制台看 PDA 长消息尾部丢失） */
    private fun messagesToJsonArray(msgs: List<Msg>): JSONArray {
        val arr = JSONArray()
        msgs.forEach { m ->
            val o = JSONObject().put("role", m.role).put("text", m.text.take(20000)).put("ts", m.ts)
            m.reasoning?.let { o.put("reasoning", it.take(20000)) }
            if (m.roundSec > 0) o.put("roundSec", m.roundSec)
            if (m.toolCalls.isNotEmpty()) {
                val tcArr = JSONArray()
                m.toolCalls.forEach { tc ->
                    tcArr.put(
                        JSONObject()
                            .put("name", tc.name)
                            .put("input", tc.input.take(4000))
                            .put("output", tc.output.take(8000))
                    )
                }
                o.put("toolCalls", tcArr)
            }
            arr.put(o)
        }
        return arr
    }

    /** 下发会话列表（{t:"session_list_resp", sessions:[{id,title,updatedAt,count}], currentId}） */
    private fun sendSessionListResp() {
        val sessions = synchronized(sessionLock) {
            var loaded = loadSessionsUnlocked()
            if (loaded.none { it.id == currentSessionId }) {
                // 当前会话失效（可能被 App 端删除）：回退最新；一个都没有则新建，保证 currentId 始终有效
                val target = loaded.maxByOrNull { it.updatedAt }
                if (target != null) {
                    currentSessionId = target.id
                } else {
                    val nid = UUID.randomUUID().toString()
                    val nl = loaded.toMutableList()
                    nl.add(0, Session(nid, "新会话", System.currentTimeMillis(), emptyList()))
                    saveSessionsUnlocked(nl)
                    loaded = nl
                    currentSessionId = nid
                }
            }
            loaded
        }
        val arr = JSONArray()
        sessions.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("count", s.messages.size)
            )
        }
        onSend(
            JSONObject()
                .put("t", "session_list_resp")
                .put("sessions", arr)
                .put("currentId", currentSessionId)
        )
    }

    /** 会话操作错误帧（{t:"session_err", reason}） */
    private fun sessionErr(reason: String): JSONObject =
        JSONObject().put("t", "session_err").put("reason", reason)

    /**
     * 构建请求历史（当前会话版本）：
     * 1) system = SYSTEM_PROMPT + 远程控制模式提示 + 当前会话记忆 + 凭据清单 + 档案清单；
     * 2) 当前会话最近 [rounds] 轮对话（只 user/assistant 文本，单条截 [MAX_MSG_CHARS]；
     *    rounds 由上下文预算压缩档位驱动：50%/80% 阈值自动降档）；
     * 3) 末尾追加本轮 user 消息。
     */
    private fun buildHistory(currentUserText: String, rounds: Int = MAX_HISTORY_ROUNDS): List<DeepSeekClient.ChatMessage> {
        val sid = currentSessionId
        // 本会话记忆注入（按当前会话 id 隔离，与 UI 会话不串用）
        val memory = AiMemory.injectText(appContext, sid)
        // 凭据保险库清单注入（只给标签/类型/id，不含密码值）
        val vault = CredentialVault.injectText(appContext, sid)
        // 资料档案清单注入（清空后重聊 AI 一眼看到有哪些 md 可读）
        val notes = AiNotes.list(appContext, sid)
        val notesText = if (notes.isEmpty()) null else buildString {
            append("\n\n【本会话资料档案（AI 生成的 md，需要细节时用 read_md 读取全文）】共 ${notes.size} 份：")
            notes.take(10).forEachIndexed { i, f ->
                val head = runCatching { f.readText().take(300) }.getOrDefault("")
                val firstLine = head.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                append("\n").append(i + 1).append(". ").append(f.name)
                if (firstLine.isNotBlank()) append("（摘要：").append(firstLine.take(80)).append("）")
            }
        }
        val system = AgentPrompts.SYSTEM_PROMPT + REMOTE_MODE_APPEND +
            (memory ?: "") + (vault ?: "") + (notesText ?: "")

        // 当前会话的消息（直接读同一份 chat_sessions；会话被删时回退空历史）
        val msgs = synchronized(sessionLock) {
            loadSessionsUnlocked().firstOrNull { it.id == sid }?.messages ?: emptyList()
        }
        // 按 user 消息切轮，取最近 [rounds] 轮（与 AiViewModel.buildHistory 同法；参数 rounds 由压缩档位驱动）
        val roundList = mutableListOf<List<Msg>>()
        var current = mutableListOf<Msg>()
        for (m in msgs.asReversed()) {
            current.add(0, m)
            if (m.role == "user") {
                roundList.add(0, current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) roundList.add(0, current)
        val kept = roundList.takeLast(rounds).flatten()

        return buildList {
            add(DeepSeekClient.ChatMessage("system", system))
            // 最近 N 轮完整对话（工具调用细节不重放，模型只看 user/assistant 文本结论）
            kept.forEach { m ->
                add(DeepSeekClient.ChatMessage(m.role, m.text.take(MAX_MSG_CHARS)))
            }
            add(DeepSeekClient.ChatMessage("user", currentUserText.take(MAX_MSG_CHARS)))
        }
    }

    /**
     * 工具审批闸门（远程审批，仅 root_shell 硬门；设备所有者授权功能已取消）：
     * - 非 root_shell → 放行（null）；
     * - root_shell 但为 ARP 类命令（arpspoof/arping，流量审计/借道审计核心动作）→ 免审批放行，
     *   审计是用户显式发起的封装操作，逐条弹窗会打断审计流程；
     * - 其余 root_shell → 生成 reqId 发 approval_req（含完整命令）→ 挂起等 handleApprovalResp
     *   （控制端按审批档位自动应答或弹窗确认）；120 秒超时默认拒绝。
     */
    private suspend fun toolGate(call: DeepSeekClient.ToolCall): String? {
        if (call.name != "root_shell") return null
        val command = call.arguments.optString("command", "")
        if (command.isBlank()) return "参数错误：root_shell 缺少 command"
        // ARP 白名单：免审批直接执行（其余命令照旧每步审批弹窗）
        if (command.contains("arpspoof") || command.contains("arping")) return null

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovals[reqId] = deferred
        pendingApprovalCommands[reqId] = command   // 缓存命令：控制端重连后 pending_approval_query 重发用
        onSend(
            JSONObject()
                .put("t", "approval_req")
                .put("reqId", reqId)
                .put("toolName", "root_shell")
                .put("command", command)
        )

        // 挂起等待审批结果；withTimeoutOrNull 超时返回 null（默认拒绝），取消则向上抛 CancellationException
        val ok = try {
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingApprovals.remove(reqId)
            pendingApprovalCommands.remove(reqId)
        }
        return when (ok) {
            null -> "远程控制端未响应审批，已自动拒绝"
            true -> null
            else -> "远程控制端拒绝了该 root 命令的执行。"
        }
    }

    /** 发一条 ai_event 帧（kind + 字符串 data + roundId） */
    private fun sendEvent(kind: String, data: String, roundId: String) {
        onSend(
            JSONObject()
                .put("t", "ai_event")
                .put("kind", kind)
                .put("data", data)
                .put("roundId", roundId)
        )
    }

    companion object {
        /** 会话持久化存储键（与 AiViewModel 的 chat_sessions 完全一致） */
        private const val KEY_SESSIONS = "chat_sessions"

        /** 历史上下文窗口：最近 N 轮完整对话（与 AiViewModel 对齐） */
        private const val MAX_HISTORY_ROUNDS = 30

        /** session_switched 历史回放最多下发条数（单帧受服务器 maxPayload 限制，超长历史按最近条数截断） */
        private const val MAX_HISTORY_SWITCH = 5

        /** 单条消息最大字符数（超长截断，控制费用） */
        private const val MAX_MSG_CHARS = 4000

        /** 自动标题：首条 user 消息截断字数（与 AiViewModel.persistCurrentMessages 一致） */
        private const val TITLE_MAX = 24

        /** 手动重命名标题上限（与 AiViewModel.renameSession 一致） */
        private const val TITLE_RENAME_MAX = 32

        /** 远程审批超时（120 秒，与设计文档 3.4 / 服务器约定一致），超时默认拒绝 */
        private const val APPROVAL_TIMEOUT_MS = 120_000L

        /** 追加到 system 提示词末尾的远程控制模式提示（简洁：只说明运行环境，不引导申请授权） */
        private const val REMOTE_MODE_APPEND =
            "\n\n远程控制模式：你运行在放置于客户现场的设备上，回答实时传输给远程控制端。" +
                "直接执行排查并简洁汇报结果，无需询问授权；root 命令会自动弹审批给控制端确认，" +
                "其中 arpspoof/arping 等 ARP 类命令免审批直接执行。"
    }
}

