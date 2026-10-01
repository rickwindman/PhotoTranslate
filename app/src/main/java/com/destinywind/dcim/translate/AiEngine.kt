package com.destinywind.dcim.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** AI 翻译引擎：任意 OpenAI Chat Completion 兼容接口 */
class AiEngine(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val systemPrompt: String,
    private val temperature: Float,
    private val maxTokens: Int,
) : TranslateEngine {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json; charset=utf-8".toMediaType()

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val targetName = when (targetLang) {
                    "zh" -> "中文（简体）"; "en" -> "英文"; "ja" -> "日文"; "ko" -> "韩文"; else -> targetLang
                }
                val body = buildJsonObject {
                    put("model", kotlinx.serialization.json.JsonPrimitive(model))
                    put("temperature", kotlinx.serialization.json.JsonPrimitive(temperature.toDouble()))
                    put("max_tokens", kotlinx.serialization.json.JsonPrimitive(maxTokens))
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", kotlinx.serialization.json.JsonPrimitive("system"))
                            put("content", kotlinx.serialization.json.JsonPrimitive("$systemPrompt 目标语言：$targetName。"))
                        })
                        add(buildJsonObject {
                            put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                            put("content", kotlinx.serialization.json.JsonPrimitive(text))
                        })
                    })
                }
                val req = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/chat/completions")
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .post(body.toString().toRequestBody(media))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val b = resp.body?.string() ?: throw IOException("空响应")
                    if (!resp.isSuccessful) throw IOException("AI HTTP ${resp.code}: ${b.take(200)}")
                    json.parseToJsonElement(b).jsonObject["choices"]?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content?.trim()
                        ?: throw IOException("响应异常")
                }
            }
        }

    override suspend fun testConnection(): Result<String> =
        translate("hello", "en", "zh").map { "连接成功：$it" }

}
