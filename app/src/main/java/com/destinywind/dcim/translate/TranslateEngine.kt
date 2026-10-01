package com.destinywind.dcim.translate

/** 统一翻译引擎接口：所有引擎以 OCR 识别出的文本为输入 */
interface TranslateEngine {
    suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String>
    suspend fun testConnection(): Result<String>
}

/** 各引擎语言代码映射（内部代码：zh/en/ja/ko/fr/de/ru/es/...） */
object LangMapper {

    private val myMemory = mapOf(
        "zh" to "zh-CN", "en" to "en", "ja" to "ja", "ko" to "ko", "fr" to "fr",
        "de" to "de", "ru" to "ru", "es" to "es", "auto" to "autodetect",
    )
    private val baidu = mapOf(
        "zh" to "zh", "en" to "en", "ja" to "jp", "ko" to "kor", "fr" to "fra",
        "de" to "de", "ru" to "ru", "es" to "spa", "auto" to "auto",
    )
    private val deepl = mapOf(
        "zh" to "ZH", "en" to "EN", "ja" to "JA", "ko" to "KO", "fr" to "FR",
        "de" to "DE", "ru" to "RU", "es" to "ES",
    )
    private val azure = mapOf(
        "zh" to "zh-Hans", "en" to "en", "ja" to "ja", "ko" to "ko", "fr" to "fr",
        "de" to "de", "ru" to "ru", "es" to "es",
    )
    private val tencent = mapOf(
        "zh" to "zh", "en" to "en", "ja" to "jp", "ko" to "ko", "fr" to "fr",
        "de" to "de", "ru" to "ru", "es" to "es", "auto" to "auto",
    )

    fun forMyMemory(code: String) = myMemory[code] ?: code
    fun forBaidu(code: String) = baidu[code] ?: code
    fun forDeepL(code: String) = deepl[code] ?: code.uppercase()
    fun forAzure(code: String) = azure[code] ?: code
    fun forTencent(code: String) = tencent[code] ?: code
    fun forMlKit(code: String) = code // ML Kit 直接使用 "zh"/"en"/"ja"/"ko"
}
