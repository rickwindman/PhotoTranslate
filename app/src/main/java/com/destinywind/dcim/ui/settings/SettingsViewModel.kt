package com.destinywind.dcim.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destinywind.dcim.core.download.DownloadState
import com.destinywind.dcim.core.download.ModelDownloadController
import com.destinywind.dcim.core.model.ModelInfo
import com.destinywind.dcim.core.model.ModelFile
import com.destinywind.dcim.core.model.ModelRepository
import com.destinywind.dcim.core.ocr.OcrEngine
import com.destinywind.dcim.data.AppSettings
import com.destinywind.dcim.data.EngineId
import com.destinywind.dcim.data.SettingsRepository
import com.destinywind.dcim.translate.AiEngine
import com.destinywind.dcim.translate.AzureEngine
import com.destinywind.dcim.translate.BaiduEngine
import com.destinywind.dcim.translate.DeepLEngine
import com.destinywind.dcim.translate.MlKitEngine
import com.destinywind.dcim.translate.TencentEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ModelRow(
    val info: ModelInfo,
    val installed: Boolean,
    val sizeOnDisk: Long,
    val download: DownloadState,
)

data class MlKitRow(val code: String, val name: String, val downloaded: Boolean)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val modelRows: List<ModelRow> = emptyList(),
    val mlKitRows: List<MlKitRow> = emptyList(),
    val quotaChars: Int = 0,
    val quotaRequests: Int = 0,
    val totalModelBytes: Long = 0,
    val toast: String = "",
    /** 正在启用中的模型 id（用于行内加载指示） */
    val activatingModelId: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val settingsRepo: SettingsRepository,
    private val modelRepo: ModelRepository,
    private val downloadController: ModelDownloadController,
    private val ocrEngine: OcrEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(settings = settingsRepo.settings.value))
    val state: StateFlow<SettingsUiState> = _state

    init {
        refreshModels()
        refreshMlKit()
        viewModelScope.launch {
            val (c, r) = settingsRepo.quota()
            _state.value = _state.value.copy(quotaChars = c, quotaRequests = r)
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            // 磁盘统计放后台，避免主线程 IO 卡顿
            val (rows, total) = withContext(Dispatchers.Default) {
                val r = modelRepo.all().map { m ->
                    ModelRow(m, modelRepo.isInstalled(m.modelId), modelRepo.installedSize(m.modelId), downloadController.stateOf(m.modelId))
                }
                r to modelRepo.totalInstalledSize()
            }
            _state.value = _state.value.copy(modelRows = rows, totalModelBytes = total)
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

    // ---------- OCR 模型 ----------
    fun downloadModel(modelId: String) {
        val s = settingsRepo.settings.value
        downloadController.enqueue(modelId, s.wifiOnlyDownload, s.mirrorBaseUrl)
        refreshModels()
        toast("已开始下载")
    }

    fun cancelDownload(modelId: String) { downloadController.cancel(modelId); refreshModels(); toast("已取消下载") }

    fun enableModel(modelId: String) {
        if (_state.value.activatingModelId != null) return // 防止重复点击
        _state.value = _state.value.copy(activatingModelId = modelId)
        viewModelScope.launch {
            val prev = settingsRepo.settings.value.activeModelId
            // 先写配置 → 卡片立即高亮，用户马上看到反馈
            settingsRepo.save(settingsRepo.settings.value.copy(activeModelId = modelId))
            refreshModels()
            val m = modelRepo.find(modelId)
            // 模型加载（15MB+ 文件 IO + 原生初始化）必须放后台线程，否则主线程卡死
            val ok = m != null && withContext(Dispatchers.Default) {
                ocrEngine.initFromDir(modelRepo.modelDir(modelId), m.recHeight)
            }
            if (!ok) {
                // 初始化失败回滚到之前的启用状态
                settingsRepo.save(settingsRepo.settings.value.copy(activeModelId = prev))
            }
            _state.value = _state.value.copy(activatingModelId = null)
            refreshModels()
            toast(if (ok) "已启用，推理引擎已重新加载" else "启用失败：引擎初始化异常，已恢复原状态")
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val m = modelRepo.find(modelId) ?: return@launch
            withContext(Dispatchers.Default) {
                if (m.custom) modelRepo.removeCustom(modelId)
                else modelRepo.modelDir(modelId).deleteRecursively()
            }
            val cur = settingsRepo.settings.value
            if (cur.activeModelId == modelId) settingsRepo.save(cur.copy(activeModelId = null))
            ocrEngine.release()
            refreshModels()
            toast("已删除")
        }
    }

    fun clearAllCaches() {
        modelRepo.clearAllModelCaches()
        ocrEngine.release()
        viewModelScope.launch {
            val cur = settingsRepo.settings.value
            settingsRepo.save(cur.copy(activeModelId = null))
        }
        refreshModels()
        toast("已清除全部模型缓存")
    }

    /**
     * 通过链接添加模型（自定义模型）：
     * 仅 https（除非允许不安全源）；体积上限校验；zip 或单文件+字典；下载后校验必要文件。
     */
    fun addCustomModel(
        name: String, version: String, languages: List<String>,
        mainUrl: String, backupUrls: List<String>, dictUrl: String,
        expectedSha256: String, isNcnn: Boolean,
        allowUnsafe: Boolean, onResult: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch {
            val s = settingsRepo.settings.value
            val maxBytes = s.maxModelSizeMb.toLong() * 1024 * 1024
            if (name.isBlank()) { onResult(false, "模型名称必填"); return@launch }
            if (mainUrl.isBlank()) { onResult(false, "主下载链接必填"); return@launch }
            val allowedSchemes = if (allowUnsafe) listOf("http://", "https://") else listOf("https://")
            if (allowedSchemes.none { mainUrl.startsWith(it) }) { onResult(false, "仅允许 https 直链（可在表单中显式允许不安全源）"); return@launch }
            backupUrls.forEach { u -> if (allowedSchemes.none { u.startsWith(it) }) { onResult(false, "备用链接须为 https"); return@launch } }

            val isZip = mainUrl.substringBefore('?').endsWith(".zip")
            val fileName = if (isZip) "model.zip" else mainUrl.substringAfterLast('/').substringBefore('?').ifBlank { "model.bin" }
            val sizeHint = headContentLength(mainUrl)
            if (sizeHint != null && sizeHint > maxBytes) {
                onResult(false, "模型体积 ${sizeHint / 1024 / 1024}MB 超过上限 ${s.maxModelSizeMb}MB，请在设置中调整上限后重试")
                return@launch
            }
            val modelId = "custom_" + System.currentTimeMillis()
            val files = mutableListOf(ModelFile(name = fileName, url = mainUrl, backupUrls = backupUrls))
            if (!isZip && dictUrl.isNotBlank()) {
                files.add(ModelFile(name = "keys.txt", url = dictUrl))
            }
            modelRepo.addCustom(
                ModelInfo(
                    modelId = modelId, name = name, version = version, languages = languages,
                    sizeBytes = sizeHint ?: 0, description = "自定义模型", custom = true,
                    files = files, zip = isZip, expectedSha256 = expectedSha256.ifBlank { null },
                    sourceUrl = mainUrl.take(48) + if (mainUrl.length > 48) "…" else "",
                ),
            )
            downloadController.enqueue(modelId, s.wifiOnlyDownload, s.mirrorBaseUrl)
            refreshModels()
            onResult(true, "已加入下载队列")
        }
    }

    private fun headContentLength(url: String): Long? = runCatching {
        val client = okhttp3.OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use {
            it.header("Content-Length")?.toLongOrNull()
        }
    }.getOrNull()

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
    fun save(settings: AppSettings, secrets: Map<String, String>, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepo.save(settings, secrets)
            _state.value = _state.value.copy(settings = settings)
            toast("设置已保存")
            onDone()
        }
    }

    fun resetDefaults() {
        viewModelScope.launch {
            settingsRepo.resetDefaults()
            _state.value = _state.value.copy(settings = settingsRepo.settings.value)
            toast("已恢复默认（不删除已下载模型）")
        }
    }

    fun clearTranslationCache() {
        File(appContext.cacheDir, "translation_cache.json").delete()
        toast("已清除翻译缓存")
    }

    /** 各引擎测试连接 */
    fun testConnection(engineId: EngineId, s: AppSettings, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val engine = when (engineId) {
                EngineId.CLOUD_BAIDU -> BaiduEngine(settingsRepo.secret("baiduAppId").ifBlank { s.baiduAppId }, settingsRepo.secret("baiduKey"))
                EngineId.CLOUD_DEEPL -> DeepLEngine(settingsRepo.secret("deeplKey"), s.deeplMode)
                EngineId.CLOUD_AZURE -> AzureEngine(settingsRepo.secret("azureKey"), s.azureRegion)
                EngineId.CLOUD_TENCENT -> TencentEngine(settingsRepo.secret("tencentSecretId"), settingsRepo.secret("tencentSecretKey"), s.tencentRegion)
                EngineId.AI -> AiEngine(s.aiBaseUrl, s.aiModel, settingsRepo.secret("aiKey"), s.aiPrompt, s.aiTemperature, s.aiMaxTokens)
                else -> null
            }
            val r = engine?.testConnection()
            r?.fold(
                onSuccess = { onResult(true, it) },
                onFailure = { onResult(false, "连接失败：${it.message}") },
            ) ?: onResult(false, "请先填写配置")
        }
    }
}

