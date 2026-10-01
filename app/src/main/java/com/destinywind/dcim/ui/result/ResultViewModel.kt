package com.destinywind.dcim.ui.result

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destinywind.dcim.core.PhotoStore
import com.destinywind.dcim.core.ocr.ImageUtils
import com.destinywind.dcim.core.ocr.OcrLine
import com.destinywind.dcim.core.ocr.OcrRepository
import com.destinywind.dcim.data.EngineId
import com.destinywind.dcim.data.SettingsRepository
import com.destinywind.dcim.translate.TranslationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** 与显示位图同尺寸的强模糊版本（微信扫一扫式遮盖背景），null = 尚未生成 */
    val blurredImage: ImageBitmap? = null,
    /** 每行框内背景是否偏暗（决定译文用白字还是黑字） */
    val lineDark: List<Boolean> = emptyList(),
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
            // 微信扫一扫式遮盖：并行生成模糊背景 + 每行背景亮度（黑字/白字）
            viewModelScope.launch { prepareMask(session.file, lines) }
            retranslate()
        }
    }

    /**
     * 生成遮盖素材：
     * 1) 与显示位图同尺寸的强模糊版本（缩小 1/14 再放大，快速无依赖），供译文块背景完全盖住原文；
     * 2) 每行框内背景平均亮度（在缩略图上采样）→ 决定译文黑字/白字。
     */
    private suspend fun prepareMask(file: File, lines: List<OcrLine>) = withContext(Dispatchers.Default) {
        runCatching {
            val src = ImageUtils.decodeUpright(file, ImageUtils.SHARE_MAX_DIM)
            val smallW = (src.width / 14).coerceAtLeast(1)
            val smallH = (src.height.toDouble() * smallW / src.width).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
            val blurred = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            if (small != blurred) small.recycle()
            val px = IntArray(smallW * smallH)
            small.getPixels(px, 0, smallW, 0, 0, smallW, smallH)
            val kx = smallW.toFloat() / src.width
            val ky = smallH.toFloat() / src.height
            val dark = lines.map { line ->
                if (line.box.size < 8) return@map false
                val xs = FloatArray(4) { line.box[it * 2] }
                val ys = FloatArray(4) { line.box[it * 2 + 1] }
                val ax = (xs.min() * kx).toInt().coerceIn(0, smallW - 1)
                val ay = (ys.min() * ky).toInt().coerceIn(0, smallH - 1)
                val bx = (xs.max() * kx).toInt().coerceIn(ax + 1, smallW)
                val by = (ys.max() * ky).toInt().coerceIn(ay + 1, smallH)
                var sum = 0f; var n = 0
                val step = 2
                var y = ay
                while (y < by) {
                    var x = ax
                    while (x < bx) {
                        val p = px[y * smallW + x]
                        sum += (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
                        n++
                        x += step
                    }
                    y += step
                }
                val lum = if (n > 0) sum / n else 1f
                lum < 0.45f  // 背景偏暗 → 译文用白字
            }
            if (small != blurred && !small.isRecycled) small.recycle()
            src.recycle()
            _state.value = _state.value.copy(blurredImage = blurred.asImageBitmap(), lineDark = dark)
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
