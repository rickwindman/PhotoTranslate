package com.destinywind.dcim.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destinywind.dcim.core.PhotoStore
import com.destinywind.dcim.core.ocr.OcrLine
import com.destinywind.dcim.core.ocr.OcrRepository
import com.destinywind.dcim.data.EngineId
import com.destinywind.dcim.data.SettingsRepository
import com.destinywind.dcim.translate.TranslationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 结果页引擎下拉的四个选项 */
enum class UiEngine(val label: String) {
    LOCAL("本地翻译（离线）"), FREE("免费翻译 MyMemory"), CLOUD("常规翻译"), AI("AI 翻译");
}

data class ResultUiState(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val imageFile: File? = null,
    val lines: List<OcrLine> = emptyList(),
    val translations: List<String> = emptyList(),
    val engine: UiEngine = UiEngine.FREE,
    val sourceLang: String = "en",
    val targetLang: String = "zh",
    val needModelDownload: Boolean = false,
    val overlayFontScale: Float = 1.0f,
    val overlayOpacity: Float = 0.82f,
) {
    enum class Phase { IDLE, OCR, TRANSLATE, DONE, ERROR }
}

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val photoStore: PhotoStore,
    private val ocrRepo: OcrRepository,
    private val translationManager: TranslationManager,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ResultUiState())
    val state: StateFlow<ResultUiState> = _state

    init {
        val s = settingsRepo.settings.value
        _state.value = _state.value.copy(
            engine = when (s.defaultEngine) {
                EngineId.LOCAL -> UiEngine.LOCAL
                EngineId.AI -> UiEngine.AI
                EngineId.FREE -> UiEngine.FREE
                else -> UiEngine.CLOUD
            },
            sourceLang = s.sourceLang,
            targetLang = s.targetLang,
            overlayFontScale = s.overlayFontScale,
            overlayOpacity = s.overlayOpacity,
        )
        process()
    }

    fun process() {
        val session = photoStore.current.value ?: run {
            _state.value = _state.value.copy(phase = ResultUiState.Phase.ERROR, message = "没有可处理的图片")
            return
        }
        _state.value = _state.value.copy(imageFile = session.file, phase = ResultUiState.Phase.OCR, message = "正在识别文字…")
        viewModelScope.launch {
            // 1. OCR（本地 PaddleOCR，所有引擎的前置步骤）
            val readyError = ocrRepo.ensureReady()
            if (readyError != null) {
                _state.value = _state.value.copy(phase = ResultUiState.Phase.ERROR, message = readyError, needModelDownload = true)
                return@launch
            }
            val (bitmap, lines) = try {
                ocrRepo.detectFile(session.file)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(phase = ResultUiState.Phase.ERROR, message = "识别失败：${t.message}")
                return@launch
            }
            bitmap.recycle() // 仅需要文本与坐标
            if (lines.isEmpty()) {
                _state.value = _state.value.copy(phase = ResultUiState.Phase.DONE, lines = emptyList(), translations = emptyList(), message = "未识别到文字")
                return@launch
            }
            _state.value = _state.value.copy(lines = lines, phase = ResultUiState.Phase.TRANSLATE, message = "正在翻译…")
            retranslate()
        }
    }

    /** 引擎切换后立即对当前图片重新翻译，无需重新拍照 */
    fun setEngine(engine: UiEngine) {
        _state.value = _state.value.copy(engine = engine)
        retranslate()
    }

    fun setLanguages(source: String, target: String) {
        _state.value = _state.value.copy(sourceLang = source, targetLang = target)
        retranslate()
    }

    private fun retranslate() {
        val s = _state.value
        if (s.lines.isEmpty()) return
        _state.value = s.copy(phase = ResultUiState.Phase.TRANSLATE, message = "正在翻译…")
        viewModelScope.launch {
            val engineId = when (s.engine) {
                UiEngine.LOCAL -> EngineId.LOCAL
                UiEngine.FREE -> EngineId.FREE
                UiEngine.CLOUD -> settingsRepo.settings.value.cloudEngine
                UiEngine.AI -> EngineId.AI
            }
            val texts = s.lines.map { it.text }
            translationManager.translateLines(texts, engineId, s.sourceLang, s.targetLang).fold(
                onSuccess = { tr ->
                    _state.value = _state.value.copy(phase = ResultUiState.Phase.DONE, translations = tr, message = "识别 ${s.lines.size} 块 · 已翻译")
                },
                onFailure = { e ->
                    // 降级到仅展示 OCR 原文并提示原因
                    _state.value = _state.value.copy(
                        phase = ResultUiState.Phase.DONE,
                        translations = s.lines.map { "" },
                        message = "翻译失败：${e.message}（仅显示原文）",
                    )
                },
            )
        }
    }
}
