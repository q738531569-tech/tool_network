package com.installer.toolbox.core.agent.support

/**
 * 敏感信息打码器：把对话消息文本中的密码/口令值替换为 ***。
 *
 * 双保险策略（用户拍板）：
 * 1. **保险库锚点精确替换**：AI 通过 save_credential 识别并存库的密码值，在
 *    当前会话全部消息（用户消息 + AI 回复）中精确替换——只打真被识别为密码的值，零误伤；
 * 2. **正则兜底**：AI 漏存时，按「密码/pwd/password/口令/密钥 等关键词 + 后跟的值」
 *    本地扫描打码（值字符集限字母数字与常见符号、不含中文，长度 4-32，降低误伤）。
 *
 * 打码不可逆：只在消息落盘前与启动迁移时执行一次。
 */
object SensitiveMasker {

    /**
     * 精确打码（AI mask_credential 工具专用）：只替换 AI 指定/保险库已存的密码值，零误伤。
     * 长值优先，避免短值是长值前缀时残留。
     */
    fun maskExact(text: String, values: List<String>): String {
        if (text.isBlank()) return text
        var result = text
        values.filter { it.length >= 3 }
            .sortedByDescending { it.length }
            .forEach { v ->
                if (result.contains(v)) result = result.replace(v, "***")
            }
        return result
    }

    /**
     * 兜底打码（启动历史迁移用）：保险库锚点精确替换 + 关键词正则扫描。
     * 正则仅在启动迁移执行一次，避免 AI 未识别时明文长期残留。
     */
    fun mask(text: String, vaultValues: List<String>): String {
        if (text.isBlank()) return text
        var result = maskExact(text, vaultValues)
        // 关键词正则兜底：密码/pwd 等关键词后紧跟的疑似凭据值
        result = KEYWORD_VALUE_REGEX.replace(result) { m ->
            val g = m.groups[1] ?: return@replace m.value
            "${m.value.substring(0, g.range.first - m.range.first)}***" +
                m.value.substring(g.range.last + 1 - m.range.first)
        }
        return result
    }

    /** 批量打码（消息列表用） */
    fun maskAll(texts: List<String>, vaultValues: List<String>): List<String> =
        texts.map { mask(it, vaultValues) }

    /**
     * 关键词 + 值 模式：
     * 第 1 组 = 值（字母数字与常见密码符号 4-32 位，不含中文/空白/逗号，防止吞掉后续正常文字）。
     * 关键词前后允许常见连接符（: ： = 空格 是 为 是/为）。
     */
    private val KEYWORD_VALUE_REGEX = Regex(
        "(?i)(密码|口令|密钥|pwd|password|passwd|secret|pass)[\\s:：=]*[是为]?[\\s:：=]*([A-Za-z0-9@#$%^&*._!~+-]{4,32})"
    )
}
