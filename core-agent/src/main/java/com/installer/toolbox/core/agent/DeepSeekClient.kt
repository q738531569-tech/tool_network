package com.installer.toolbox.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 对话客户端（OpenAI 兼容 API，SSE 流式）。
 *
 * - 端点：POST https://api.deepseek.com/chat/completions
 * - 模型：deepseek-v4-flash（1M 上下文，适合塞入整份诊断报告）
 * - 流式：stream=true，逐行解析 `data: {...}` 增量返回 delta.content
 *
 * 注意：DeepSeek 是外网 API，不设置 NO_PROXY（走系统代理反而可能必需），
 * 与 core-isapi 的 OnvifClient（局域网直连必须绕代理）场景相反。
 */
object DeepSeekClient {

    private const val BASE_URL = "https://api.deepseek.com"

    /** 默认模型名（设置页可自定义覆盖，方便换模型/升级不用发版） */
    const val DEFAULT_MODEL = "deepseek-v4-flash"

    /** 单次回复最大 token 数：设置页可配置（存 prefs 后写到这里，模型更新/长回复需求无需发版）。
     *  默认 256K 对齐 DeepSeek Harness 适配器（官方 v4 上限 384K）；按实际生成计费，设大上限不额外花钱。
     *  GET /models 不返回该字段（仅 id/object/owned_by，已查官方文档），故由设置页驱动、不写死常量。 */
    @Volatile
    var maxOutputTokens: Int = 256000

    /** 思考强度（官方 reasoning_effort：low/high/max，默认 high）：思考与正文共享输出预算，
     *  多工具轮次思考过长会挤占正文导致截断——设置页可调 low 节省预算 / max 增强推理 */
    @Volatile
    var reasoningEffort: String = "high"

    /** 服务器连接检测结果（AI 页"连接服务器正常"文案用） */
    enum class ServerStatus {
        /** API Key 有效，服务器可达（可正常对话） */
        OK,
        /** 服务器可达但 API Key 无效（401/403） */
        UNAUTHORIZED,
        /** 服务器可达但账户余额不足（402 Insufficient Balance） */
        INSUFFICIENT_BALANCE,
        /** 请求过于频繁被限流（429） */
        RATE_LIMITED,
        /** 网络/服务器不可达 */
        FAIL
    }

    /**
     * 账户余额（实时显示用；DeepSeek 官方 GET /user/balance）。
     */
    data class BalanceInfo(
        val currency: String,
        val totalBalance: String
    )

    /**
     * 查询账户余额（AI 页实时显示"💰 余额 ¥xx"）。
     * GET /user/balance → balance_infos[0]（CNY total_balance）。
     * 查询失败/未配置 Key 返回 null（静默，不影响对话主链路）。
     */
    suspend fun getBalance(apiKey: String): BalanceInfo? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url("$BASE_URL/user/balance")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string().orEmpty())
                val infos = json.optJSONArray("balance_infos") ?: return@withContext null
                val info = infos.optJSONObject(0) ?: return@withContext null
                BalanceInfo(
                    currency = info.optString("currency", "CNY"),
                    totalBalance = info.optString("total_balance", "")
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 错误码 → 装维友好中文提示（用户诉求：429/余额不足等服务状态必须有显式提示，
     * 不能把英文原文直接甩给装维）。
     */
    fun friendlyError(code: Int, serverMsg: String?): String {
        val msg = serverMsg?.trim().orEmpty()
        return when {
            code == 401 -> "API Key 无效或已过期（HTTP 401），请到设置页重新填写"
            code == 402 || (code == 400 && msg.contains("balance", ignoreCase = true)) ||
                msg.contains("Insufficient Balance", ignoreCase = true) ->
                "DeepSeek 账户余额不足（HTTP $code），请到 platform.deepseek.com 充值后重试"
            code == 429 || msg.contains("rate limit", ignoreCase = true) ->
                "请求过于频繁被限流（HTTP 429），请稍等片刻后重试"
            code == 403 -> "无权限访问（HTTP 403）：API Key 可能未开通当前模型，请到设置页确认"
            code == 400 && (msg.contains("context", ignoreCase = true) ||
                msg.contains("maximum", ignoreCase = true) || msg.contains("length", ignoreCase = true)) ->
                "对话内容太长，已超出模型上下文上限"
            code in 500..599 -> "DeepSeek 服务器繁忙（HTTP $code），请稍后重试"
            else -> "DeepSeek 接口错误（HTTP $code）" + if (msg.isNotBlank()) "：$msg" else ""
        }
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            // 绕过系统代理：手机常配 127.0.0.1:8080 死代理会拦截外网请求
            .proxy(java.net.Proxy.NO_PROXY)
            // 读超时 300s：对齐 DeepSeek Harness 的流式空闲超时（模型长思考可能数十秒无输出，
            // 60s 会把思考期误判为超时切断）
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 可重试错误自动重试 1 次（对齐 harness 的 retryPolicy）：
     * 仅对瞬时故障重试——网络异常（IOException）、5xx 服务器繁忙、429 限流；
     * 401/402/403/400 等业务错误直接抛给调用方（重试无意义）。
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastError: Exception? = null
        for (i in 0..1) {
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty()
                val retryable = e is java.io.IOException ||
                    msg.contains("服务器繁忙") || msg.contains("限流")
                if (!retryable || i == 1) throw e
                kotlinx.coroutines.delay(800L + i * 700L)   // 退避 0.8s / 1.5s
            }
        }
        throw lastError ?: IllegalStateException("请求失败")
    }

    /** 一条对话消息（role: system / user / assistant / tool） */
    data class ChatMessage(
        val role: String,
        val content: String,
        /** 工具调用 ID（role = "tool" 时必填，对应 assistant tool_calls[].id） */
        val toolCallId: String? = null,
        /** assistant 消息携带的 tool_calls（OpenAI 协议要求后续请求原样回传） */
        val toolCalls: List<ToolCall>? = null,
        /** 思考链（reasoning_content）：**带工具调用的 assistant 轮必须回传**（官方要求，
         *  否则 400 且模型无法延续思考）；无工具轮不回传（省 token，官方也忽略） */
        val reasoning: String? = null
    )

    /**
     * 工具定义（OpenAI function calling 的 function 描述）。
     * @param name 工具名（ASCII 小写，如 ping_device）
     * @param description 中文功能描述，供模型判断何时调用
     * @param parameters JSON Schema（type=object，含 properties / required）
     */
    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: JSONObject
    )

    /** 模型要求调用的一次工具 */
    data class ToolCall(
        /** 工具调用 ID（回传 tool 结果时须带） */
        val id: String,
        val name: String,
        /** 解析后的参数对象（arguments JSON 字符串 → JSONObject） */
        val arguments: JSONObject
    )

    /** 非流式工具轮结果 */
    data class ToolChatResult(
        /** 最终回答文本（无工具调用时）；纯工具轮可能为空串 */
        val text: String,
        /** 模型要调用的工具列表；空表示本轮直接给出最终回答 */
        val toolCalls: List<ToolCall>,
        /** 本轮思考链全文（工具轮需回传 reasoning_content；无工具轮不回传省 token） */
        val reasoning: String = "",
        /** finish_reason=length：输出被 max_tokens 截断（最终回复时提示用户可继续追问） */
        val truncated: Boolean = false,
        /** 流式提前结束（收到内容但无 [DONE]/finish_reason=服务端断开/网络 RST）：正文可能半截，
         *  最终回复时提示"回复中断，可让我继续"（2026-08-18 修复多工具轮后"莫名截断"） */
        val interrupted: Boolean = false,
        /** 本轮 token 用量（含上下文缓存命中/未命中），服务端未返回时为 null */
        val usage: UsageInfo? = null
    )

    /**
     * token 用量与上下文缓存命中信息（DeepSeek 自动上下文缓存：
     * 请求前缀稳定时命中，命中部分输入价约为未命中的 1/10）。
     */
    data class UsageInfo(
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        /** 上下文缓存命中的输入 token 数（prompt_cache_hit_tokens） */
        val cacheHitTokens: Long = 0,
        /** 未命中的输入 token 数（prompt_cache_miss_tokens） */
        val cacheMissTokens: Long = 0
    ) {
        /** 缓存命中率（hit / (hit+miss)，无数据返回 null） */
        val cacheHitRate: Double?
            get() = if (cacheHitTokens + cacheMissTokens > 0) {
                cacheHitTokens.toDouble() / (cacheHitTokens + cacheMissTokens)
            } else null
    }

    /**
     * 发送一轮对话并流式接收回复。
     *
     * @param apiKey DeepSeek API Key
     * @param messages 消息列表（一般 system 引导 + user 诊断数据）
     * @param onDelta 每个增量片段回调（在 IO 线程触发，内部只做纯文本累加）
     * @param effort 思考强度覆盖（null=用全局 reasoningEffort；"off"=关闭思考，序列化为
     *   thinking.type:disabled——摘要等辅助调用用，正文预算全给回复）
     * @return 完整回复文本（等于各 delta 累加）
     * @throws Exception HTTP 非 200 时抛异常，message 为中文说明（含服务端 error.message）
     */
    suspend fun chat(
        apiKey: String,
        messages: List<ChatMessage>,
        onDelta: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null,
        model: String = DEFAULT_MODEL,
        effort: String? = null
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }

        withRetry {
            val body = JSONObject().apply {
                put("model", model)
                put("stream", true)
                // 显式上限：不设时服务端用默认 4K，长回复（审计报告/排查结论）会被截断
                put("max_tokens", maxOutputTokens)
                // 思考强度：off（或脏数据空串）→ thinking.type disabled（省略 reasoning_effort）；
                // 否则顶层 reasoning_effort。注意 DeepSeek v4 合法枚举为 none/minimal/low/medium/
                // high/xhigh/max——"off" 不是合法值，必须走 disabled 分支，否则 API 400
                val eff = effort ?: reasoningEffort
                if (eff == "off" || eff.isBlank()) {
                    put("thinking", JSONObject().put("type", "disabled"))
                } else {
                    put("reasoning_effort", eff)
                }
                put(
                    "messages",
                    JSONArray().apply {
                        messages.forEach { m ->
                            put(JSONObject().put("role", m.role).put("content", m.content))
                        }
                    }
                )
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            val call = client.newCall(request)
            // 停止按钮：协程取消时主动中断 HTTP（否则 execute 阻塞到响应完成才感知取消）
            coroutineContext[Job]?.invokeOnCompletion {
                if (it is java.util.concurrent.CancellationException) call.cancel()
            }
            val response = call.execute()
            val full = StringBuilder()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    // 错误响应：解析 error.message（OpenAI 兼容错误结构）
                    val errorText = try {
                        resp.body?.string().orEmpty()
                } catch (e: Exception) {
                    ""
                }
                val serverMsg = try {
                    val err = JSONObject(errorText).optJSONObject("error")
                    // 注意：optString 对 JSONObject.NULL 会返回 "null" 字符串，需显式判空
                    if (err == null || err.isNull("message")) null else err.optString("message")
                } catch (e: Exception) {
                    null
                }
                // 服务状态显式提示：429/余额不足等映射为装维友好中文
                throw IllegalStateException(friendlyError(resp.code, serverMsg ?: resp.message))
            }

            // ---- SSE 流式解析（手写，约 20 行，不引入第三方库）----
            // 格式：每行 `data: {...}`，以 `data: [DONE]` 结束；
            // 增量位于 choices[0].delta.content（可能缺省或为 null）。
            resp.body?.source()?.use { source ->
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val delta = try {
                        val deltaObj = JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                        // optString 对 JSONObject.NULL 返回 "null"，此处显式判空过滤
                        if (deltaObj == null || deltaObj.isNull("content")) null else deltaObj.optString("content")
                    } catch (e: Exception) {
                        null // 单行解析失败跳过，不影响整体
                    }
                    // 思考链增量（deepseek-reasoner / v4 系列响应携带；单独回调，不混入正文）
                    val reasoning = try {
                        val deltaObj = JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                        if (deltaObj == null || deltaObj.isNull("reasoning_content")) null
                        else deltaObj.optString("reasoning_content")
                    } catch (e: Exception) {
                        null
                    }
                    if (!reasoning.isNullOrEmpty()) {
                        onReasoning?.invoke(reasoning)
                    }
                    if (!delta.isNullOrEmpty()) {
                        full.append(delta)
                        onDelta?.invoke(delta)
                    }
                }
            }
        }
            full.toString()
        }
    }

    /**
     * 拉取 API 返回的可用模型列表（设置页模型下拉框选项动态化，选项由 API 决定）。
     * GET /models → data[].id；非 2xx 或解析失败抛异常，由调用方兜底。
     */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val request = Request.Builder()
            .url("$BASE_URL/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code !in 200..299) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val data = JSONObject(body).optJSONArray("data") ?: return@use emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id")?.trim()
                    if (!id.isNullOrBlank()) add(id)
                }
            }
        }
    }

    /**
     * 验证 API Key 与服务器连通性（AI 页"连接服务器正常"状态用）。
     * GET /models：200=Key 有效可对话；401/403=服务器可达但 Key 无效；其他/异常=不可达。
     */
    suspend fun checkServer(apiKey: String): ServerStatus = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ServerStatus.FAIL
        try {
            val request = Request.Builder()
                .url("$BASE_URL/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    in 200..299 -> ServerStatus.OK
                    401, 403 -> ServerStatus.UNAUTHORIZED
                    // 服务状态显式提示：余额不足/限流单独状态（状态行直接告诉装维怎么办）
                    402 -> ServerStatus.INSUFFICIENT_BALANCE
                    429 -> ServerStatus.RATE_LIMITED
                    else -> ServerStatus.FAIL
                }
            }
        } catch (e: Exception) {
            ServerStatus.FAIL
        }
    }

    /**
     * 工具调用轮（stream=true 流式：思考链与正文逐行回调，UI 实时打印）。
     *
     * body 相比 [chat] 增加 `tools` 数组（type=function），并支持回传带
     * tool_calls 的 assistant 消息与 role=tool 的工具结果消息。
     *
     * 流式聚合：reasoning_content 增量 → onReasoning；content 增量 → onContentDelta；
     * tool_calls 按 index 聚合（id/name/arguments 分片）；最后一个 chunk 带 usage
     * （stream_options.include_usage）。
     *
     * @param apiKey DeepSeek API Key
     * @param messages 消息列表（可含 assistant tool_calls / tool 结果，[ChatMessage.toJson] 序列化）
     * @param tools 已注册的工具定义列表
     * @param onReasoning 思考链增量回调（逐段，IO 线程）
     * @param onContentDelta 正文增量回调（逐段，IO 线程；过渡语/最终回答逐行打印用）
     * @return [ToolChatResult]：有 toolCalls 表示模型要调工具，否则 text 为最终回答
     * @throws Exception HTTP 非 200 时抛异常，message 为中文说明（含服务端 error.message）
     */
    suspend fun chatWithTools(
        apiKey: String,
        messages: List<ChatMessage>,
        tools: List<ToolDef>,
        onReasoning: ((String) -> Unit)? = null,
        model: String = DEFAULT_MODEL,
        effort: String? = null,
        onContentDelta: ((String) -> Unit)? = null
    ): ToolChatResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }

        withRetry {
            val body = JSONObject().apply {
                put("model", model)
                put("stream", true)
                // 显式上限：不设时服务端用默认 4K，长回复会被截断
                put("max_tokens", maxOutputTokens)
                // 流式下 usage 在最后一个 chunk 返回（token 统计/缓存命中；服务端不识别则忽略）
                put("stream_options", JSONObject().put("include_usage", true))
                // 思考强度：off（或脏数据空串）→ thinking.type disabled；否则顶层 reasoning_effort
                // （"off" 不是 DeepSeek v4 合法枚举，必须走 disabled 分支，否则 API 400）
                val eff = effort ?: reasoningEffort
                if (eff == "off" || eff.isBlank()) {
                    put("thinking", JSONObject().put("type", "disabled"))
                } else {
                    put("reasoning_effort", eff)
                }
                put(
                    "messages",
                    JSONArray().apply {
                        messages.forEach { m -> put(m.toJson()) }
                    }
                )
                if (tools.isNotEmpty()) {
                    put(
                        "tools",
                        JSONArray().apply {
                            tools.forEach { t ->
                                put(
                                    JSONObject().apply {
                                        put("type", "function")
                                        put(
                                            "function",
                                            JSONObject().apply {
                                                put("name", t.name)
                                                put("description", t.description)
                                                put("parameters", t.parameters)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    )
                }
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            val call = client.newCall(request)
            // 停止按钮：协程取消时主动中断 HTTP（否则 execute 阻塞到响应完成才感知取消）
            coroutineContext[Job]?.invokeOnCompletion {
                if (it is java.util.concurrent.CancellationException) call.cancel()
            }
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 错误处理与 chat() 一致：解析 OpenAI 兼容 error.message
                    val errorText = try {
                        resp.body?.string().orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    val serverMsg = try {
                        val err = JSONObject(errorText).optJSONObject("error")
                        if (err == null || err.isNull("message")) null else err.optString("message")
                    } catch (e: Exception) {
                        null
                    }
                    // 服务状态显式提示：429/余额不足等映射为装维友好中文
                    throw IllegalStateException(friendlyError(resp.code, serverMsg ?: resp.message))
                }

                // ---- SSE 流式解析（与 chat() 同款逐行解析）----
                // reasoning_content / content 增量实时回调（思考与正文逐行打印）；
                // tool_calls 按 index 聚合（id/name/arguments 分片拼接）；usage 在最后 chunk。
                val textSb = StringBuilder()
                val reasoningSb = StringBuilder()
                var finishReason = ""
                var usage: UsageInfo? = null
                // 流式结束检测：gotDone=收到 [DONE]；sawChunk=收到过任何内容/思考/工具分片——
                // 循环正常退出但 sawChunk 且未 gotDone 且无 finish_reason = 服务端流提前断开（半截正文）
                var gotDone = false
                var sawChunk = false
                // tool_calls 流式聚合：index → id/name/arguments 分片
                val toolIds = mutableMapOf<Int, String>()
                val toolNames = mutableMapOf<Int, String>()
                val toolArgs = mutableMapOf<Int, StringBuilder>()
                resp.body?.source()?.use { source ->
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") {
                            if (data == "[DONE]") gotDone = true
                            continue
                        }
                        val obj = try { JSONObject(data) } catch (e: Exception) { continue }
                        val choice = obj.optJSONArray("choices")?.optJSONObject(0)
                        val delta = choice?.optJSONObject("delta")
                        // 思考链增量（deepseek-reasoner / v4 系列携带；单独回调不混入正文）
                        if (delta != null && !delta.isNull("reasoning_content")) {
                            val piece = delta.optString("reasoning_content")
                            if (piece.isNotEmpty()) {
                                sawChunk = true
                                reasoningSb.append(piece)
                                onReasoning?.invoke(piece)
                            }
                        }
                        // 正文增量（工具轮过渡语/最终回答）
                        if (delta != null && !delta.isNull("content")) {
                            val piece = delta.optString("content")
                            if (piece.isNotEmpty()) {
                                sawChunk = true
                                textSb.append(piece)
                                onContentDelta?.invoke(piece)
                            }
                        }
                        // 工具调用分片：按 index 聚合（arguments 跨 chunk 拼接）
                        val tcs = delta?.optJSONArray("tool_calls")
                        if (tcs != null) {
                            sawChunk = true
                            for (i in 0 until tcs.length()) {
                                val tc = tcs.optJSONObject(i) ?: continue
                                val idx = tc.optInt("index", 0)
                                val id = tc.optString("id")
                                if (id.isNotBlank() && toolIds[idx] == null) toolIds[idx] = id
                                val fn = tc.optJSONObject("function")
                                val name = fn?.optString("name").orEmpty()
                                if (name.isNotBlank() && toolNames[idx] == null) toolNames[idx] = name
                                val argsPiece = fn?.optString("arguments").orEmpty()
                                if (argsPiece.isNotEmpty()) {
                                    toolArgs.getOrPut(idx) { StringBuilder() }.append(argsPiece)
                                }
                            }
                        }
                        // finish_reason：length=截断（最终回复时提示可继续追问）
                        val fr = choice?.optString("finish_reason").orEmpty()
                        if (fr.isNotBlank()) finishReason = fr
                        // usage（stream_options.include_usage 时最后 chunk 携带）
                        obj.optJSONObject("usage")?.let { u ->
                            usage = UsageInfo(
                                promptTokens = u.optLong("prompt_tokens"),
                                completionTokens = u.optLong("completion_tokens"),
                                cacheHitTokens = u.optLong("prompt_cache_hit_tokens"),
                                cacheMissTokens = u.optLong("prompt_cache_miss_tokens")
                            )
                        }
                    }
                }
                // 聚合工具调用（按 index 升序，保持模型声明顺序）
                val calls = toolArgs.keys.sorted().mapNotNull { idx ->
                    val name = toolNames[idx] ?: return@mapNotNull null
                    val argsText = toolArgs[idx]?.toString().orEmpty()
                    val args = try {
                        if (argsText.isBlank()) JSONObject() else JSONObject(argsText)
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    ToolCall(id = toolIds[idx] ?: "", name = name, arguments = args)
                }
                ToolChatResult(
                    text = textSb.toString(),
                    toolCalls = calls,
                    reasoning = reasoningSb.toString(),
                    truncated = finishReason == "length",
                    // 收到过内容但流未正常结束（无 [DONE]/finish_reason）→ 服务端提前断开，正文半截
                    interrupted = sawChunk && !gotDone && finishReason.isBlank(),
                    usage = usage
                )
            }
        }
    }

    /** 序列化单条消息：支持 tool_call_id / tool_calls（OpenAI 协议要求原样回传） */
    private fun ChatMessage.toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        val hasToolCalls = !toolCalls.isNullOrEmpty()
        // 带 tool_calls 的 assistant 消息 content 须为 null（OpenAI 协议）；其余情况原样传
        if (hasToolCalls && content.isEmpty()) {
            put("content", JSONObject.NULL)
        } else {
            put("content", content)
        }
        toolCallId?.let { put("tool_call_id", it) }
        // 思考链回传（仅带工具调用的 assistant 轮；官方要求必须回传才能延续思考）
        if (hasToolCalls && !reasoning.isNullOrBlank()) put("reasoning_content", reasoning)
        if (hasToolCalls) {
            val calls = toolCalls!!
            put(
                "tool_calls",
                JSONArray().apply {
                    calls.forEach { c ->
                        put(
                            JSONObject().apply {
                                put("id", c.id)
                                put("type", "function")
                                put(
                                    "function",
                                    JSONObject().apply {
                                        put("name", c.name)
                                        // arguments 必须序列化为 JSON 字符串（协议要求）
                                        put("arguments", c.arguments.toString())
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
    }
}
