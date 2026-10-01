package com.destinywind.dcim.core.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型清单仓库：
 * - 内置清单：assets/models.json（多镜像直链 + SHA256）
 * - 自定义模型：filesDir/custom_models.json（"通过链接添加"写入，恢复默认不删除）
 * - 模型安装目录：filesDir/models/<modelId>/
 */
@Singleton
class ModelRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val customFile = File(context.filesDir, "custom_models.json")
    val modelsRoot: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models

    init { reload() }

    fun reload() {
        val builtin = runCatching {
            val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
            json.decodeFromString<ModelCatalog>(text).models
        }.getOrElse { emptyList() }
        val custom = runCatching {
            if (customFile.isFile) json.decodeFromString<List<ModelInfo>>(customFile.readText()) else emptyList()
        }.getOrElse { emptyList() }
        // 已安装的自定义模型还原状态
        _models.value = builtin + custom
    }

    fun all(): List<ModelInfo> = _models.value

    fun find(modelId: String): ModelInfo? = _models.value.firstOrNull { it.modelId == modelId }

    fun modelDir(modelId: String): File = File(modelsRoot, modelId)

    /** 模型是否已完整安装（必要文件齐全） */
    fun isInstalled(modelId: String): Boolean {
        val d = modelDir(modelId)
        return d.isDirectory &&
            File(d, "det.param").isFile && File(d, "det.bin").isFile &&
            File(d, "rec.param").isFile && File(d, "rec.bin").isFile &&
            File(d, "keys.txt").isFile
    }

    fun installedSize(modelId: String): Long = modelDir(modelId).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun totalInstalledSize(): Long = modelsRoot.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** 新增/更新自定义模型 */
    @Synchronized
    fun addCustom(model: ModelInfo) {
        val list = all().filter { !(it.custom && it.modelId == model.modelId) }
        saveCustom(list.filter { it.custom } + model)
        reload()
    }

    fun removeCustom(modelId: String) {
        saveCustom(all().filter { !(it.custom && it.modelId == modelId) })
        modelDir(modelId).deleteRecursively()
        reload()
    }

    fun markInvalid(modelId: String, reason: String) {
        val m = find(modelId)?.copy(status = ModelInfo.STATUS_INVALID, invalidReason = reason) ?: return
        if (m.custom) { saveCustom(all().filter { it.custom }.map { if (it.modelId == modelId) m else it }); reload() }
    }

    fun clearAllModelCaches() { modelsRoot.deleteRecursively(); modelsRoot.mkdirs() }

    private fun saveCustom(list: List<ModelInfo>) {
        customFile.writeText(json.encodeToString(list))
    }
}
