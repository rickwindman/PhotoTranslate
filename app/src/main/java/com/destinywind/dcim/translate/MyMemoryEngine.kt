package com.destinywind.dcim.translate

import android.util.LruCache
import com.destinywind.dcim.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 免费翻译引擎：MyMemory（匿名可用，无需密钥）。
 * - 长文本自动分段（≤450 字符/段）
 * - 结果缓存（内存 LRU + 磁盘 JSON，key = 原文+语言对+引擎），命中缓存不消耗配额
 * - 当日字符数/请求数统计（配额提醒）
 * - 429/网络异常重试 2 次（指数退避）后降级
 */
class MyMemoryEngine(
    private val settingsRepo: SettingsRepository,
    cacheDir: File,
) : TranslateEngine {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val memCache = LruCache<String, String>(512)
    private val diskCache = File(cacheDir, "translation_cache.json")

    private val dailyQuotaChars = 5000 // MyMemory 匿名配额量级（按 IP 限流），用于提醒

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            val segments = splitSegments(text)
            val out = StringBuilder()
            var usedChars = 0
            var usedRequests = 0
            for (seg in segments) {
                if (seg.isBlank()) continue
                val key = cacheKey(seg, sourceLang, targetLang)
                val cached = memCache.get(key) ?: diskGet(key)
                if (cached != null) { out.append(cached); continue }
                val translated = requestWithRetry(seg, sourceLang, targetLang)
                    .getOrElse { return@withContext Result.failure(it) }
                usedChars += seg.length
                usedRequests += 1
                putCache(key, translated)
                out.append(translated)
            }
            settingsRepo.updateQuota(usedChars, usedRequests)
            val quota = settingsRepo.quota()
            val text0 = if (quota.first >= dailyQuotaChars) "${out}\n\n⚠ 今日免费额度可能已用完" else out.toString()
            Result.success(text0)
        }

    private suspend fun requestWithRetry(text: String, src: String, tgt: String): Result<String> {
        var lastErr: Throwable? = null
        for (attempt in 0..2) { // 重试最多 2 次
            if (attempt > 0) delay(1000L shl attempt) // 指数退避
            val r = requestOnce(text, src, tgt)
            if (r.isSuccess) return r
            lastErr = r.exceptionOrNull()
            val msg = lastErr?.message ?: ""
            if (msg.contains("429") || msg.contains("LIMIT") || msg.contains("quota", true)) {
                return Result.failure(IllegalStateException("今日免费额度已用完，请切换常规翻译 / AI 翻译 / 仅看原文"))
            }
        }
        return Result.failure(lastErr ?: IllegalStateException("免费翻译失败"))
    }

    private fun requestOnce(text: String, src: String, tgt: String): Result<String> = runCatching {
        val langpair = "${LangMapper.forMyMemory(src)}|${LangMapper.forMyMemory(tgt)}"
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        val url = "https://api.mymemory.translated.net/get" +
            "?q=$encoded&langpair=$langpair&format=plain&de=destinywind%40dcim.app"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 429) throw IOException("429 额度限流")
            val body = response.body?.string() ?: throw IOException("空响应")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val obj = json.parseToJsonElement(body).jsonObject
            val status = obj["responseStatus"]?.toString() ?: "0"
            val translated = obj["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.content ?: ""
            if (translated.contains("MYMEMORY WARNING", true)) {
                throw IOException("LIMIT 已达免费额度上限")
            }
            if (status != "200" || translated.isBlank()) {
                val details = obj["responseDetails"]?.jsonPrimitive?.content ?: ""
                throw IOException("MyMemory 错误: $status $details")
            }
            translated
        }
    }

    private fun splitSegments(text: String, maxLen: Int = 450): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (line in text.split(Regex("(?<=\n)"))) {
            if (cur.length + line.length > maxLen) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
                var rest = line
                while (rest.length > maxLen) { out.add(rest.take(maxLen)); rest = rest.drop(maxLen) }
                cur.append(rest)
            } else cur.append(line)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun cacheKey(text: String, src: String, tgt: String) = "$src>$tgt>$text"

    @Synchronized
    private fun diskGet(key: String): String? {
        if (!diskCache.isFile) return null
        return runCatching {
            json.parseToJsonElement(diskCache.readText()).jsonObject[key]?.jsonPrimitive?.content
        }.getOrNull()
    }

    @Synchronized
    private fun putCache(key: String, value: String) {
        memCache.put(key, value)
        runCatching {
            val old = if (diskCache.isFile)
                json.parseToJsonElement(diskCache.readText()).jsonObject else buildJsonObject { }
            val updated = buildJsonObject {
                old.forEach { (k, v) -> put(k, v) }
                put(key, value)
            }
            diskCache.writeText(updated.toString())
        }
    }

    fun clearCache() {
        memCache.evictAll()
        diskCache.delete()
    }

    override suspend fun testConnection(): Result<String> =
        translate("hello", "en", "zh").map { "连接成功：$it" }
}
