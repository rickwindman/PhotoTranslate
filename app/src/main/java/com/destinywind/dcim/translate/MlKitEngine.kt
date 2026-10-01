package com.destinywind.dcim.translate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 本地翻译引擎：ML Kit On-Device Translation（无需 Play 服务）。
 * 语言包首次联网下载后完全离线。
 */
class MlKitEngine : TranslateEngine {

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 源语言"自动"：用 ML Kit Language Identification 在线识别（本地模型，离线可用）
                val src = if (sourceLang == "auto")
                    toMlKitLang(autoDetect(text)) else toMlKitLang(sourceLang)
                val tgt = toMlKitLang(targetLang)
                val options = TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
                val translator = Translation.getClient(options)
                try {
                    // 模型未下载时会联网下载；已下载则直接离线使用
                    Tasks.await(translator.downloadModelIfNeeded(), 120, TimeUnit.SECONDS)
                    Tasks.await(translator.translate(text), 30, TimeUnit.SECONDS)
                } finally {
                    translator.close()
                }
            }.recoverCatching { t ->
                throw IllegalStateException("本地翻译失败：${t.message}（请到设置-本地翻译下载语言包）", t)
            }
        }

    override suspend fun testConnection(): Result<String> =
        translate("hello", "en", "zh").map { "本地翻译正常：$it" }

    private suspend fun autoDetect(text: String): String = withContext(Dispatchers.IO) {
        runCatching {
            Tasks.await(LanguageIdentification.getClient().identifyLanguage(text), 10, TimeUnit.SECONDS)
        }.getOrDefault("en")
    }

    private fun toMlKitLang(code: String): String = when (code) {
        "zh" -> TranslateLanguage.CHINESE
        "en" -> TranslateLanguage.ENGLISH
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "fr" -> TranslateLanguage.FRENCH
        "de" -> TranslateLanguage.GERMAN
        "ru" -> TranslateLanguage.RUSSIAN
        "es" -> TranslateLanguage.SPANISH
        else -> code
    }

    companion object {
        /** 可选语言对（展示用） */
        val languageNames = mapOf(
            "zh" to "中文（简体）", "en" to "英文", "ja" to "日文", "ko" to "韩文",
            "fr" to "法语", "de" to "德语", "ru" to "俄语", "es" to "西班牙语",
        )

        /** 已下载的语言包 */
        suspend fun downloadedLanguages(): List<String> = withContext(Dispatchers.IO) {
            runCatching {
                Tasks.await(
                    RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
                    10, TimeUnit.SECONDS,
                ).map { it.language }
            }.getOrDefault(emptyList())
        }

        /** 下载语言包（异步，进度由 ML Kit 自管） */
        suspend fun downloadLanguage(code: String): Result<Unit> = withContext(Dispatchers.IO) {
            runCatching {
                val model = TranslateRemoteModel.Builder(toMlKitLangStatic(code)).build()
                Tasks.await(RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()), 300, TimeUnit.SECONDS)
                Unit
            }
        }

        suspend fun deleteLanguage(code: String): Result<Unit> = withContext(Dispatchers.IO) {
            runCatching {
                val model = TranslateRemoteModel.Builder(toMlKitLangStatic(code)).build()
                Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(model), 30, TimeUnit.SECONDS)
                Unit
            }
        }

        private fun toMlKitLangStatic(code: String): String = when (code) {
            "zh" -> TranslateLanguage.CHINESE
            "en" -> TranslateLanguage.ENGLISH
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "ru" -> TranslateLanguage.RUSSIAN
            "es" -> TranslateLanguage.SPANISH
            else -> code
        }
    }
}
