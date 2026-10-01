package com.destinywind.dcim.core.ocr

import android.graphics.Bitmap
import com.destinywind.dcim.core.model.ModelRepository
import com.destinywind.dcim.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** OCR 执行仓库：负责加载当前启用的模型并执行识别 */
@Singleton
class OcrRepository @Inject constructor(
    private val engine: OcrEngine,
    private val modelRepo: ModelRepository,
    private val settingsRepo: SettingsRepository,
) {

    var lastActiveModelId: String? = null
        private set

    /** 确保引擎已按当前启用模型加载；成功返回 null，失败返回原因 */
    fun ensureReady(): String? {
        val active = settingsRepo.settings.value.activeModelId
            ?: modelRepo.all().firstOrNull { modelRepo.isInstalled(it.modelId) }?.modelId
            ?: return "尚未下载用于识别图片文字的模型（OCR），无法识别。请到设置页「OCR 模型」下载"
        val model = modelRepo.find(active) ?: return "启用的模型不存在，请到设置页「OCR 模型」重新选择并启用"
        if (!modelRepo.isInstalled(active)) return "模型未安装完成，请到设置页「OCR 模型」下载"
        val ok = engine.initFromDir(modelRepo.modelDir(active), model.recHeight)
        if (!ok) return "OCR 引擎初始化失败，请到设置页重试"
        lastActiveModelId = active
        return null
    }

    /** 识别图片文件（自动处理 EXIF 方向，与显示共用同一解码参数，保证坐标一致） */
    suspend fun detectFile(file: File): Pair<Bitmap, List<OcrLine>> = withContext(Dispatchers.Default) {
        val bitmap = ImageUtils.decodeUpright(file, ImageUtils.SHARE_MAX_DIM)
        val lines = engine.detect(bitmap)
        bitmap to lines
    }
}
