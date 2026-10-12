package com.installer.toolbox.core.agent

import android.content.Context
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import com.installer.toolbox.core.agent.DeepSeekClient.ChatMessage
import com.installer.toolbox.core.agent.DeepSeekClient.ToolCall

/**
 * AI 诊断引擎：Agent 工具调用循环。
 *
 * app 层调用方直接发消息，模型通过 [AgentTools] 主动调用诊断工具采集数据，
 * 本引擎只负责执行工具调用循环。
 */
object AgentEngine {

    /** 2026-08-18 用户拍板：**去掉工具循环轮数上限**——AI 一直调工具直到给出结论；
     *  陷入死循环由用户手动点停止（停止按钮实时生效：轮边界/工具间 ensureActive 检查取消）。
     *  工具循环每轮模型可调一批工具。 */
    // （原 MAX_TOOL_ROUNDS=8 上限已删除；收尾请求逻辑一并移除）

    /** 工具输出进上下文的单条字符上限（多工具轮次防上下文/输出预算挤压；UI 层另有完整折叠记录） */
    private const val TOOL_CTX_CHARS = 3000

    /**
     * Agent 工具调用循环（M5.1）：自由对话 + 主动调用 APP 内诊断工具。
     *
     * 流程（流式，**无轮数上限**——2026-08-18 用户拍板，复杂排查场景 8 轮不够）：
     * 1. [DeepSeekClient.chatWithTools] 带全部工具定义请求模型（stream=true）；
     *    思考链增量 → onReasoning（逐行打印）；正文增量 → 累积快照 onDelta（过渡语/最终回答逐行）；
     * 2. 无 toolCalls → 本轮 text 即最终回答；
     * 3. 有 toolCalls → 依次 [AgentTools.execute] 执行（[onStatus] 提示），
     *    把 assistant 消息（**含 tool_calls 原样回传**，OpenAI 协议要求）
     *    + 每条 tool 结果消息（role=tool, tool_call_id=id）追加进 messages，
     *    继续下一轮，直到模型给出最终回答；
     * 4. 模型陷入死循环时由用户手动点停止（每轮边界/工具间 ensureActive 检查，取消实时生效）。
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
        // 【2026-08-18 用户拍板】无轮数上限：while(true) 一直调工具直到模型给结论；
        // 死循环由用户手动停止（每轮 ensureActive 检查，停止按钮实时生效）
        while (true) {
            round++
            coroutineContext.ensureActive()   // 【修复 2026-08-17】停止按钮实时生效：轮边界检查取消
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
                // 模型直接给出最终回答，结束循环；被 max_tokens 截断或流中断时追加提示
                // （对齐 harness 的 max-tokens finish 处理：提示可继续追问，而不是让截断内容显得完整；
                //   2026-08-18 补流中断：多工具轮后网络波动致服务端流提前断开，正文半截——明确提示可继续）
                finalText = when {
                    result.truncated && result.text.isNotBlank() ->
                        result.text + "\n\n⚠ 回复过长已被截断，可让我继续补充。"
                    result.interrupted && result.text.isNotBlank() ->
                        result.text + "\n\n⚠ 回复中断（网络波动或服务异常），可让我继续补充。"
                    else -> result.text
                }
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
                coroutineContext.ensureActive()   // 【修复 2026-08-17】工具间检查取消（前一个工具跑完立即停，不继续下一个）
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
        // 2026-08-18 用户拍板去掉轮数上限后，不再有"达上限收尾请求"——模型给结论即 break，
        // 死循环用户手动停止（停止按钮实时生效）；此处直接返回最终结论
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
