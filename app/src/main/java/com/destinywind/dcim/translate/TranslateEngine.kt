package com.destinywind.dcim.translate

/** 统一翻译引擎接口 */
interface TranslateEngine {
    suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String>
    suspend fun testConnection(): Result<String>
}

/** 各引擎语言代码映射（内部代码：zh/en/ja/ko/...） */
object LangMapper {

    private val myMemory = mapOf(
        "zh" to "zh-CN", "en" to "en", "ja" to "ja", "ko" to "ko", "fr" to "fr",
        "de" to "de", "ru" to "ru", "es" to "es", "auto" to "autodetect",
    )

    fun forMyMemory(code: String) = myMemory[code] ?: code
    fun forMlKit(code: String) = code // ML Kit 直接使用 "zh"/"en"/"ja"/"ko"
}
