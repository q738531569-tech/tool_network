package com.installer.toolbox.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地敏感数据加密存储（Android Keystore AES-GCM，自实现，不依赖已弃用的 security-crypto）。
 *
 * 用于 DeepSeek API Key 等敏感字段的落盘加密：
 * - 密钥由 Android Keystore 生成并保管（KeyGenParameterSpec，AES-256-GCM，**仅本设备可用、不可导出**）；
 * - 密文以「iv 的 Base64.密文 Base64」格式存储，读回时用 GCM 校验完整性（防篡改）；
 * - **兼容明文迁移**：读到旧明文数据自动加密回写（前缀 "enc:" 区分），无需用户手动操作；
 * - 解密失败（如应用数据迁移到其他设备 / Keystore 密钥丢失）安全降级为 null（视为未配置），不抛异常。
 */
object SecurePrefs {

    /** Keystore 中的密钥别名 */
    private const val KEYSTORE_ALIAS = "toolbox_api_key"

    /** SharedPreferences 文件名（与既有 "agent" 文件保持一致，仅加密 api_key 字段） */
    private const val PREF_FILE = "agent"

    /** 字段名 */
    private const val PREF_KEY = "api_key"

    /** 密文前缀：用于区分明文旧数据（迁移检测） */
    private const val PREFIX = "enc:"

    /** 读取 API Key；未配置返回 null；明文旧数据自动加密回写后返回明文 */
    fun getApiKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, null) ?: return null
        if (!raw.startsWith(PREFIX)) {
            // 明文旧数据：自动加密回写（迁移，幂等）
            runCatching {
                prefs.edit().putString(PREF_KEY, PREFIX + encrypt(raw)).apply()
            }
            return raw
        }
        return runCatching {
            decrypt(raw.removePrefix(PREFIX))
        }.getOrNull() // 解密失败（密钥丢失/跨设备）→ 视为未配置
    }

    /** 保存 API Key（加密后落盘） */
    fun putApiKey(context: Context, key: String) {
        val encrypted = encrypt(key)
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, PREFIX + encrypted).apply()
    }

    /** 清除已保存的 API Key */
    fun clearApiKey(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().remove(PREF_KEY).apply()
    }

    // ---- 加解密 ----

    /** 取 Keystore 密钥；不存在则生成（AES-256-GCM，仅本设备） */
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

    /** 加密：返回「iv 的 Base64.密文 Base64」 */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) +
            "." + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** 解密：解析「iv 的 Base64.密文 Base64」，GCM 校验完整性 */
    private fun decrypt(payload: String): String {
        val parts = payload.split(".", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("密文格式错误")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getSecretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
