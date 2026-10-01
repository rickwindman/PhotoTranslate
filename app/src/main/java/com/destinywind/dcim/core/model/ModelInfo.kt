package com.destinywind.dcim.core.model

import kotlinx.serialization.Serializable

/** models.json 中单个模型文件 */
@Serializable
data class ModelFile(
    val name: String,          // 下载后保存的文件名：det.param / det.bin / rec.param / rec.bin / keys.txt / cls.param / cls.bin
    val url: String,           // 主下载直链
    val backupUrls: List<String> = emptyList(), // 备用直链（主链失败时依次尝试）
    val sha256: String? = null,
    val sizeBytes: Long? = null,
)

/**
 * 一个 OCR 模型条目。
 * mirrorPrefixes: 依次尝试的镜像前缀（拼在 url 前面，"" 表示官方直链兜底），支持用户自定义 Base URL。
 */
@Serializable
data class ModelInfo(
    val modelId: String,
    val name: String,
    val version: String = "",
    val languages: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    val description: String = "",
    val recHeight: Int = 48,              // rec 输入高度：v3/v4=48，旧 sim=32
    val recommended: Boolean = false,
    val custom: Boolean = false,          // 用户自定义链接添加
    val files: List<ModelFile> = emptyList(),
    val mirrorPrefixes: List<String> = emptyList(),
    val zip: Boolean = false,             // 自定义 zip 压缩包
    val expectedSha256: String? = null,   // 自定义整包校验
    val sourceUrl: String = "",           // 自定义模型来源（脱敏展示）
    val status: String = STATUS_AVAILABLE, // available / invalid
    val invalidReason: String = "",
) {
    companion object {
        const val STATUS_AVAILABLE = "available"
        const val STATUS_INVALID = "invalid"
    }
}

@Serializable
data class ModelCatalog(val version: Int = 1, val models: List<ModelInfo> = emptyList())
