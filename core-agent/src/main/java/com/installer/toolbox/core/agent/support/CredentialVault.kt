package com.installer.toolbox.core.agent.support

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 现场凭据保险库：客户现场的密码/账号（摄像头密码、路由器密码、WiFi 密码、ONVIF 账号等）
 * 的**加密存储**通道，与明文 AI 记忆（AiMemory）刻意分开。
 *
 * 设计要点（用户拍板）：
 * - **加密落盘**：整体 JSON 用 Android Keystore AES-256-GCM 加密（与 API Key 同款机制，
 *   仅本设备可解密、不可导出）；core-agent 模块自带实现（app 层 SecurePrefs 不可反向依赖）；
 * - **会话隔离**：每条凭据带 sessionId（一个客户一个库），列表/注入只取当前会话；
 * - **生命周期同步**：清空会话**保留**（与记忆/档案一致），删除会话**连带删除**；
 * - **值与清单分离**：list/injectText 只暴露标签/类型/设备（不含密码值），
 *   密码值只能通过 getValue 按 id 读取（供 AI 工具 get_credential 使用）；
 * - 解密失败（换设备/密钥丢失）安全降级为空列表，不抛异常。
 */
object CredentialVault {

    /** 凭据元信息（清单展示用，**不含密码值**） */
    data class CredentialMeta(
        val id: String,
        val label: String,
        val type: String,
        val device: String,
        val ts: Long
    )

    /** 内部完整条目（value 仅存内存，落盘时随整体 JSON 加密） */
    private data class CredentialItem(
        val id: String,
        val sessionId: String,
        val label: String,
        val type: String,
        val device: String,
        val value: String,
        val ts: Long
    )

    /** Keystore 密钥别名（独立于 API Key 的 alias，互不影响） */
    private const val KEYSTORE_ALIAS = "toolbox_cred_vault"

    /** SharedPreferences 文件名（独立文件，不与其他数据混放） */
    private const val PREF_FILE = "cred_vault"
    private const val PREF_KEY = "vault"

    /** 凭据类型白名单（AI 存库时约束；未知类型归 other） */
    val TYPES = listOf("camera", "router", "wifi", "onvif", "other")

    // ==================== 读取 ====================

    /** 全部条目（内部用；解密失败返回空列表） */
    private fun all(context: Context): List<CredentialItem> {
        return try {
            val raw = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(PREF_KEY, null) ?: return emptyList()
            val json = decrypt(raw) ?: return emptyList()
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    add(
                        CredentialItem(
                            id = id,
                            sessionId = o.optString("sessionId"),
                            label = o.optString("label", "未命名凭据"),
                            type = o.optString("type", "other"),
                            device = o.optString("device"),
                            value = o.optString("value"),
                            ts = o.optLong("ts", 0L)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 指定会话的凭据清单（**不含密码值**；记忆/凭据弹窗与 buildHistory 注入用） */
    fun list(context: Context, sessionId: String): List<CredentialMeta> =
        all(context).filter { it.sessionId == sessionId }.map {
            CredentialMeta(it.id, it.label, it.type, it.device, it.ts)
        }

    /** 指定会话凭据条数（删除会话提示用） */
    fun countBySession(context: Context, sessionId: String): Int =
        all(context).count { it.sessionId == sessionId }

    /** 按 id 读密码值（仅 AI get_credential 工具与确需展示的场景使用） */
    fun getValue(context: Context, id: String): String? =
        all(context).firstOrNull { it.id == id }?.value

    /** 指定会话的全部密码值（打码器锚点用：对消息历史做精确替换） */
    fun allValues(context: Context, sessionId: String): List<String> =
        all(context).filter { it.sessionId == sessionId }.map { it.value }.filter { it.length >= 3 }

    /** 注入文本：本会话凭据清单（标签/类型/设备，**不含值**）；空则 null */
    fun injectText(context: Context, sessionId: String): String? {
        val items = list(context, sessionId)
        if (items.isEmpty()) return null
        return buildString {
            append("\n\n【本会话凭据保险库（客户现场密码已加密存储，需密码时调用 get_credential 按 id 读取，绝不在回复中明文写出密码值）】")
            items.forEachIndexed { i, it ->
                append("\n").append(i + 1).append(". ")
                append(it.label)
                if (it.device.isNotBlank()) append("（").append(it.device).append("）")
                append(" 类型：").append(it.type)
                append(" 凭据id：").append(it.id)
            }
        }
    }

    // ==================== 写入 ====================

    /** 新增凭据（加密落盘；同会话同标签同设备视为重复，覆盖更新值） */
    fun add(
        context: Context,
        sessionId: String,
        label: String,
        type: String,
        device: String,
        value: String
    ): CredentialMeta {
        val l = label.trim().ifBlank { "未命名凭据" }.take(40)
        val t = type.trim().lowercase().let { if (it in TYPES) it else "other" }
        val d = device.trim().take(60)
        val v = value.trim()
        if (sessionId.isBlank() || v.isEmpty()) {
            throw IllegalArgumentException("sessionId 与凭据值不能为空")
        }
        val items = all(context).toMutableList()
        // 同会话同标签同设备覆盖（避免重复条目堆积）
        val exist = items.firstOrNull {
            it.sessionId == sessionId && it.label == l && it.device == d
        }
        val item = CredentialItem(
            id = exist?.id ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            label = l,
            type = t,
            device = d,
            value = v,
            ts = System.currentTimeMillis()
        )
        if (exist != null) {
            items[items.indexOf(exist)] = item
        } else {
            items.add(item)
        }
        save(context, items)
        return CredentialMeta(item.id, item.label, item.type, item.device, item.ts)
    }

    /** 删除单条凭据（按 id） */
    fun remove(context: Context, id: String) {
        save(context, all(context).filterNot { it.id == id })
    }

    /** 删除指定会话的全部凭据（删除会话时连带调用），返回删除条数 */
    fun removeBySession(context: Context, sessionId: String): Int {
        val items = all(context)
        val removed = items.count { it.sessionId == sessionId }
        save(context, items.filterNot { it.sessionId == sessionId })
        return removed
    }

    /** 序列化并加密落盘（同步 commit：凭据数据量小，可靠性优先——apply 异步写在进程被杀时可能丢失） */
    private fun save(context: Context, items: List<CredentialItem>) {
        try {
            val arr = JSONArray()
            items.forEach { itm ->
                arr.put(
                    JSONObject()
                        .put("id", itm.id)
                        .put("sessionId", itm.sessionId)
                        .put("label", itm.label)
                        .put("type", itm.type)
                        .put("device", itm.device)
                        .put("value", itm.value)
                        .put("ts", itm.ts)
                )
            }
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, encrypt(arr.toString())).commit()
        } catch (e: Exception) {
            // 加密失败（Keystore 异常等）：丢弃本次写入，不破坏已存数据
        }
    }

    // ==================== 加解密（与 SecurePrefs 同机制，独立 alias） ====================

    private fun getSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) +
            "." + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String? {
        return try {
            val parts = payload.split(".", limit = 2)
            if (parts.size != 2) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getSecretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null // 解密失败（换设备/密钥丢失）→ 视为空库
        }
    }
}
