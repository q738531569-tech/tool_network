package com.installer.toolbox.core.agent

import android.content.Context
import com.installer.toolbox.core.agent.DeepSeekClient.ChatMessage
import com.installer.toolbox.core.agent.DeepSeekClient.ToolCall

/**
 * AI 诊断引擎：Agent 工具调用循环。
 *
 * app 层调用方直接发消息，模型通过 [AgentTools] 主动调用诊断工具采集数据，
 * 本引擎只负责执行工具调用循环。
 */
object AgentEngine {

    /** Agent 工具循环最大轮数（每轮模型可调用一批工具，防死循环） */
    private const val MAX_TOOL_ROUNDS = 8

    /** 工具输出进上下文的单条字符上限（多工具轮次防上下文/输出预算挤压；UI 层另有完整折叠记录） */
    private const val TOOL_CTX_CHARS = 3000

    /**
     * Agent 工具调用循环（M5.1）：自由对话 + 主动调用 APP 内诊断工具。
     *
     * 流程（流式，上限 [MAX_TOOL_ROUNDS] 轮）：
     * 1. [DeepSeekClient.chatWithTools] 带全部工具定义请求模型（stream=true）；
     *    思考链增量 → onReasoning（逐行打印）；正文增量 → 累积快照 onDelta（过渡语/最终回答逐行）；
     * 2. 无 toolCalls → 本轮 text 即最终回答；
     * 3. 有 toolCalls → 依次 [AgentTools.execute] 执行（[onStatus] 提示），
     *    把 assistant 消息（**含 tool_calls 原样回传**，OpenAI 协议要求）
     *    + 每条 tool 结果消息（role=tool, tool_call_id=id）追加进 messages，
     *    继续下一轮，直到模型给出最终回答或达到轮数上限；
     * 4. 达上限仍没结论 → 追加一轮不带工具的收尾请求，让模型基于已有信息总结（不再腰斩半截话）。
     *
     * @param context 应用上下文（工具执行需要）
     * @param apiKey DeepSeek API Key
     * @param history 历史消息，需以 SYSTEM_PROMPT 开头（调用方保证，否则自动前置）
     * @param onStatus 状态提示回调（如"正在调用工具：ping_device…"）
     * @param onDelta 正文快照回调（每轮累积全文，覆盖式渲染即逐行打字效果）
     * @param toolGate 工具审批闸门：每个工具执行前回调；返回 null=放行执行，
     *   返回非 null=拒绝，该文本作为工具结果回传模型（如"用户拒绝执行"）。
     *   用于危险工具（root_shell）的人工审批；默认全部放行。
     * @return 最终回答文本
     */
    suspend fun runAgentLoop(
        context: Context,
        apiKey: String,
        history: List<ChatMessage>,
        onStatus: (String) -> Unit,
        onDelta: (String) -> Unit,
        toolGate: suspend (ToolCall) -> String? = { null },
        onUsage: ((DeepSeekClient.UsageInfo) -> Unit)? = null,
        onMaskRequest: (List<String>) -> Unit = {},
        onReasoning: (String) -> Unit = {},
        onToolResult: (name: String, input: String, output: String) -> Unit = { _, _, _ -> },
        onToolLog: (String) -> Unit = {},
        model: String = DeepSeekClient.DEFAULT_MODEL
    ): String {
        val messages = history.toMutableList()
        // 调用方未前置 system 时自动补上
        if (messages.firstOrNull()?.role != "system") {
            messages.add(0, ChatMessage("system", AgentPrompts.SYSTEM_PROMPT))
        }
        val tools = AgentTools.definitions(context)

        var finalText = ""
        var round = 0
        var finished = false   // 模型是否在轮数上限内正常给出最终回答
        while (round < MAX_TOOL_ROUNDS) {
            round++
            // 聚合本轮思考链（流式增量回调：UI 逐行打印思考过程）
            val roundReasoning = StringBuilder()
            val wrappedReasoning: (String) -> Unit = { piece ->
                roundReasoning.append(piece)
                onReasoning(piece)
            }
            // 本轮正文快照流式输出：content 增量 → 累积快照 onDelta（三端覆盖式渲染 = 逐行打字效果；
            // 快照保证轮边界自然重置——新一轮 content 从头开始，覆盖上一轮过渡语）
            val roundText = StringBuilder()
            val wrappedDelta: (String) -> Unit = { piece ->
                roundText.append(piece)
                onDelta(roundText.toString())
            }
            val result = DeepSeekClient.chatWithTools(
                apiKey, messages, tools, wrappedReasoning, model,
                onContentDelta = wrappedDelta
            )
            // 本轮 token/缓存命中统计回调（App 层展示缓存命中率）
            result.usage?.let { onUsage?.invoke(it) }

            if (result.toolCalls.isEmpty()) {
                // 模型直接给出最终回答，结束循环；被 max_tokens 截断时追加提示（对齐 harness 的
                // max-tokens finish 处理：提示可继续追问，而不是让截断内容显得完整）
                finalText = if (result.truncated && result.text.isNotBlank()) {
                    result.text + "\n\n⚠ 回复过长已被截断，可让我继续补充。"
                } else {
                    result.text
                }
                finished = true
                break
            }

            // 工具轮过渡语兜底：content 为空（流式未输出过正文）时把思考里"准备说的话"提取输出
            if (roundText.isEmpty() && roundReasoning.isNotBlank()) {
                val interim = interimFromReasoning(roundReasoning.toString())
                if (interim.isNotBlank()) onDelta(interim)
            }

            // 1) 回传 assistant 消息：content + tool_calls + reasoning_content
            //    （官方要求：工具轮必须回传思考链才能延续思考；无工具轮不回传省 token）
            messages.add(
                ChatMessage(
                    "assistant",
                    result.text,
                    toolCalls = result.toolCalls,
                    reasoning = roundReasoning.toString().ifBlank { null }
                )
            )
            // 2) 依次执行工具，结果作为 role=tool 消息追加
            for (call in result.toolCalls) {
                onStatus("正在调用工具：${call.name}…")
                // 审批闸门：返回非 null 即拒绝（拒绝文本作为工具结果回传，模型可继续对话说明）
                val denied = toolGate(call)
                val output = if (denied == null) {
                    AgentTools.execute(context, call, onMaskRequest, onToolLog)
                } else {
                    denied
                }
                // 工具调用记录回调（App 层折叠展示：工具名 + 参数 + 输出）
                val inputBrief = runCatching { call.arguments.toString(2) }.getOrDefault(call.arguments.toString())
                onToolResult(call.name, inputBrief, output)
                // 工具输出进上下文截断（多工具轮次的上下文瘦身：输出全文动辄几万字，
                // 全带会让最终回复的思考+正文预算被挤压导致截断；3000 字足够模型决策）；
                // 空输出传字面占位（对齐 harness，避免模型困惑）
                val ctxOutput = output.take(TOOL_CTX_CHARS).ifBlank { "（无输出）" }
                messages.add(ChatMessage("tool", ctxOutput, toolCallId = call.id))
            }
        }

        // 达到轮数上限且模型仍未给出最终回答（最后一轮还在调工具，正文停在半截）：
        // 追加收尾请求——不带工具定义，模型只能基于已有信息给结论（修复"话说一半被腰斩"）
        if (!finished) {
            val closeText = StringBuilder()
            val closeReasoning = StringBuilder()
            val closeDelta: (String) -> Unit = { piece ->
                closeText.append(piece)
                onDelta(closeText.toString())
            }
            val closeReasoningCb: (String) -> Unit = { piece ->
                closeReasoning.append(piece)
                onReasoning(piece)
            }
            messages.add(
                ChatMessage(
                    "system",
                    "已达工具轮数上限（$MAX_TOOL_ROUNDS 轮），请基于以上信息直接给出最终结论，不要再调用工具。"
                )
            )
            val r = runCatching {
                DeepSeekClient.chatWithTools(
                    apiKey, messages, emptyList(), closeReasoningCb, model,
                    onContentDelta = closeDelta
                )
            }.getOrNull()
            if (r != null) {
                r.usage?.let { onUsage?.invoke(it) }
                finalText = if (r.truncated && r.text.isNotBlank()) {
                    r.text + "\n\n⚠ 回复过长已被截断，可让我继续补充。"
                } else {
                    r.text
                }
            }
            if (finalText.isBlank()) {
                finalText = "已连续调用 $MAX_TOOL_ROUNDS 轮工具仍未得到最终结论，请缩小问题范围后重试。"
            }
        }
        onDelta(finalText)
        return finalText
    }

    /** 从工具轮思考链提取过渡语：取最后一个非空句子（按换行/句号切），≤120 字 */
    private fun interimFromReasoning(reasoning: String): String {
        val trimmed = reasoning.trim()
        if (trimmed.isBlank()) return ""
        val lastLine = trimmed.split("\n").lastOrNull { it.isNotBlank() } ?: trimmed
        val lastSentence = lastLine.split("。").lastOrNull { it.isNotBlank() } ?: lastLine
        return lastSentence.trim().take(120)
    }
}
