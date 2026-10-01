package com.destinywind.dcim.ui.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destinywind.dcim.data.EngineId
import com.destinywind.dcim.data.AppSettings
import com.destinywind.dcim.data.SettingsRepository
import com.destinywind.dcim.translate.TranslationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

private val Context.chatStore by preferencesDataStore(name = "chat_history")

/** 聊天消息：role=SRC 原文（右侧）/ TR 译文（左侧） */
@Serializable
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val engine: String = "",
    val lang: String = "",
    val ts: Long = 0L,
) {
    @Serializable
    enum class Role { SRC, TR }
}

/** 其他应用选中文本唤起时，经此转交 ChatViewModel 自动翻译 */
object PendingProcessText {
    val flow = MutableStateFlow<String?>(null)
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val pending: Boolean = false,
) {
    val canSend: Boolean get() = !pending
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val translationManager: TranslationManager,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    val settings: StateFlow<AppSettings> = settingsRepo.settings

    init {
        // 恢复聊天记录
        viewModelScope.launch {
            val raw = context.chatStore.data.first()[KEY_MESSAGES]
            val list = runCatching {
                if (raw != null) json.decodeFromString<List<ChatMessage>>(raw) else emptyList()
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(messages = list)
            consumePending()
        }
        // 其他应用"选中翻译"唤起
        viewModelScope.launch {
            PendingProcessText.flow.collect { t ->
                if (t != null) {
                    PendingProcessText.flow.value = null
                    send(t)
                }
            }
        }
    }

    private suspend fun consumePending() {
        PendingProcessText.flow.value?.let {
            PendingProcessText.flow.value = null
            send(it)
        }
    }

    fun send(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _state.value.pending) return
        val s = settings.value
        val now = System.currentTimeMillis()

        val srcMsg = ChatMessage(now, ChatMessage.Role.SRC, text, lang = s.sourceLang, ts = now)
        val trId = now + 1
        val trMsg = ChatMessage(trId, ChatMessage.Role.TR, "", engine = "…", lang = s.targetLang, ts = trId)

        val list = _state.value.messages + srcMsg + trMsg
        _state.value = _state.value.copy(messages = list, pending = true)
        persist(list)

        viewModelScope.launch {
            val engineLabel = when (s.defaultEngine) {
                EngineId.LOCAL -> "本地离线"
                EngineId.FREE -> "免费在线"
            }
            translationManager.translateLines(listOf(text), s.defaultEngine, s.sourceLang, s.targetLang)
                .fold(
                    onSuccess = { outs ->
                        replaceMsg(trId, ChatMessage(trId, ChatMessage.Role.TR, outs.firstOrNull() ?: "", engine = engineLabel, lang = s.targetLang, ts = trId))
                    },
                    onFailure = { e ->
                        replaceMsg(trId, ChatMessage(trId, ChatMessage.Role.TR, "翻译失败：${e.message}", engine = "失败", lang = s.targetLang, ts = trId))
                    },
                )
            _state.value = _state.value.copy(pending = false)
        }
    }

    private fun replaceMsg(id: Long, msg: ChatMessage) {
        val list = _state.value.messages.map { if (it.id == id) msg else it }
        _state.value = _state.value.copy(messages = list)
        persist(list)
    }

    fun setLanguages(source: String, target: String) {
        viewModelScope.launch {
            settingsRepo.save(settings.value.copy(sourceLang = source, targetLang = target))
        }
    }

    fun swapLanguages() {
        val s = settings.value
        if (s.sourceLang == "auto") return // 自动检测无法交换
        setLanguages(s.targetLang, s.sourceLang)
    }

    /** 引擎切换：本地离线 ↔ 免费在线 */
    fun toggleEngine() {
        val s = settings.value
        val next = if (s.defaultEngine == EngineId.LOCAL) EngineId.FREE else EngineId.LOCAL
        viewModelScope.launch { settingsRepo.save(s.copy(defaultEngine = next)) }
    }

    fun setFontScale(v: Float) {
        viewModelScope.launch { settingsRepo.save(settings.value.copy(chatFontScale = v)) }
    }

    fun clearChat() {
        _state.value = _state.value.copy(messages = emptyList())
        persist(emptyList())
    }

    private fun persist(list: List<ChatMessage>) {
        viewModelScope.launch {
            context.chatStore.edit { it[KEY_MESSAGES] = json.encodeToString(list) }
        }
    }

    companion object {
        private val KEY_MESSAGES = stringPreferencesKey("messages_json")
    }
}
