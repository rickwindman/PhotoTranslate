package com.destinywind.dcim.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destinywind.dcim.data.AppSettings
import com.destinywind.dcim.data.SettingsRepository
import com.destinywind.dcim.translate.MlKitEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MlKitRow(val code: String, val name: String, val downloaded: Boolean)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val mlKitRows: List<MlKitRow> = emptyList(),
    val quotaChars: Int = 0,
    val quotaRequests: Int = 0,
    val toast: String = "",
)

/** v1.0.1 精简版设置：通用（引擎/语言/字号）+ ML Kit 语言包 + 关于 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(settings = settingsRepo.settings.value))
    val state: StateFlow<SettingsUiState> = _state

    private var saveJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                if (_state.value.settings != s) _state.value = _state.value.copy(settings = s)
            }
        }
        refreshMlKit()
        viewModelScope.launch {
            val (c, r) = settingsRepo.quota()
            _state.value = _state.value.copy(quotaChars = c, quotaRequests = r)
        }
    }

    /** 设置变更：立即反映到界面，防抖 400ms 后落盘（滑条拖动等高频操作只写一次） */
    fun update(s: AppSettings) {
        _state.value = _state.value.copy(settings = s)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            settingsRepo.save(s)
        }
    }

    fun refreshMlKit() {
        viewModelScope.launch {
            val downloaded = MlKitEngine.downloadedLanguages()
            val pairs = listOf(
                "en" to "zh", "zh" to "en", "en" to "ja", "ja" to "zh", "en" to "ko", "ko" to "zh",
            )
            val names = MlKitEngine.languageNames
            _state.value = _state.value.copy(
                mlKitRows = pairs.map { (src, tgt) ->
                    MlKitRow(
                        code = "$src>$tgt",
                        name = "${names[src]} → ${names[tgt]}",
                        downloaded = downloaded.contains(src) && downloaded.contains(tgt),
                    )
                },
            )
        }
    }

    fun toast(msg: String) { _state.value = _state.value.copy(toast = msg) }
    fun consumeToast() { _state.value = _state.value.copy(toast = "") }

    // ---------- ML Kit 本地翻译语言包 ----------
    fun downloadMlKit(code: String) {
        viewModelScope.launch {
            val (src, tgt) = code.split(">")
            MlKitEngine.downloadLanguage(src)
            MlKitEngine.downloadLanguage(tgt)
            refreshMlKit()
            toast("语言包已下载，可离线使用")
        }
    }

    fun deleteMlKit(code: String) {
        viewModelScope.launch {
            val (src, tgt) = code.split(">")
            MlKitEngine.deleteLanguage(src)
            MlKitEngine.deleteLanguage(tgt)
            refreshMlKit()
            toast("已删除语言包")
        }
    }

    // ---------- 设置 ----------
    fun resetDefaults() {
        viewModelScope.launch {
            settingsRepo.resetDefaults()
            _state.value = _state.value.copy(settings = settingsRepo.settings.value)
            toast("已恢复默认")
        }
    }

    fun clearTranslationCache() {
        java.io.File(appContext.cacheDir, "translation_cache.json").delete()
        toast("已清除翻译缓存")
    }
}
