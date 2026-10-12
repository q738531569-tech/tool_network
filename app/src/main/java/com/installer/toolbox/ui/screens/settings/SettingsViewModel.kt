package com.installer.toolbox.ui.screens.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.installer.toolbox.core.agent.DeepSeekClient
import com.installer.toolbox.util.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel：DeepSeek API Key 与模型名管理。
 *
 * 与 AI 页共用同一个加密存储（SecurePrefs：Android Keystore AES-GCM，
 * 明文旧数据自动迁移加密）；AI 页通过 refreshKey() 感知这里保存/清除的变化。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    /** 当前已保存的 API Key（空串 = 未配置） */
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    /** 模型名（设置页可配置；解析顺序：手动选择的 model_name → API 返回的第一个 default_model → 硬编码兜底） */
    private val _modelName = MutableStateFlow(DeepSeekClient.DEFAULT_MODEL)
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** API /models 返回的第一个模型（默认模型，供未手动选模型时使用） */
    private val _defaultModel = MutableStateFlow(DeepSeekClient.DEFAULT_MODEL)
    val defaultModel: StateFlow<String> = _defaultModel.asStateFlow()

    /** API 返回的可用模型列表（下拉框选项；失败/未配置 Key 时为空，UI 用当前模型兜底） */
    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    /** 模型列表拉取中（下拉框 supportingText 显示"获取中…"） */
    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private val agentPrefs = getApplication<Application>()
        .getSharedPreferences("agent", Context.MODE_PRIVATE)

    init {
        _apiKey.value = SecurePrefs.getApiKey(getApplication()).orEmpty()
        // 模型解析：手动选择优先，其次 API 第一个（缓存），最后硬编码兜底
        val chosen = agentPrefs.getString("model_name", null)?.trim()?.ifBlank { null }
        val apiFirst = agentPrefs.getString("default_model", null)?.trim()?.ifBlank { null }
        _modelName.value = chosen ?: apiFirst ?: DeepSeekClient.DEFAULT_MODEL
        _defaultModel.value = apiFirst ?: DeepSeekClient.DEFAULT_MODEL
    }

    /** 从 DeepSeek /models 端点拉取可用模型列表（下拉选项由 API 返回；失败/无 Key 静默保持空） */
    fun refreshModels() {
        if (_modelsLoading.value || _apiKey.value.isBlank()) return
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                val list = DeepSeekClient.listModels(_apiKey.value)
                _availableModels.value = list
                // 默认模型 = 接口返回的第一个（用户要求），缓存给 AI 页未手动选模型时使用
                if (list.isNotEmpty()) {
                    _defaultModel.value = list.first()
                    agentPrefs.edit().putString("default_model", list.first()).apply()
                }
            } catch (_: Exception) {
                // 网络异常/Key 无效：保持空列表，下拉框用兜底选项
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    /** 保存 Key（trim 后加密落盘；空值忽略） */
    fun saveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        SecurePrefs.putApiKey(getApplication(), trimmed)
        _apiKey.value = trimmed
    }

    /** 清除已保存的 Key */
    fun clearKey() {
        SecurePrefs.clearApiKey(getApplication())
        _apiKey.value = ""
    }

    /** 保存模型名（空白=恢复默认 deepseek-v4-flash） */
    fun saveModelName(name: String) {
        val trimmed = name.trim().ifBlank { DeepSeekClient.DEFAULT_MODEL }
        _modelName.value = trimmed
        agentPrefs.edit().putString("model_name", trimmed).apply()
    }
}
