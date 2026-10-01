package com.destinywind.dcim.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** JSON 工具（各云端引擎共用） */
internal val engineJson = Json { ignoreUnknownKeys = true }
internal val jsonMedia = "application/json; charset=utf-8".toMediaType()

/** 百度翻译：AppID + 密钥，MD5 签名 */
class BaiduEngine(private val appId: String, private val key: String) : TranslateEngine {

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val salt = System.currentTimeMillis().toString()
                val sign = md5(appId + text + salt + key)
                val url = "https://fanyi-api.baidu.com/api/trans/vip/translate" +
                    "?q=${java.net.URLEncoder.encode(text, "UTF-8")}" +
                    "&from=${LangMapper.forBaidu(sourceLang)}&to=${LangMapper.forBaidu(targetLang)}" +
                    "&appid=$appId&salt=$salt&sign=$sign"
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val body = resp.body?.string() ?: throw IOException("空响应")
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val obj = engineJson.parseToJsonElement(body).jsonObject
                    obj["error_code"]?.let { throw IOException("百度错误 ${it.jsonPrimitive.content}: ${obj["error_msg"]?.jsonPrimitive?.content}") }
                    obj["trans_result"]?.jsonArray?.joinToString("\n") { it.jsonObject["dst"]?.jsonPrimitive?.content ?: "" }
                        ?: throw IOException("响应异常: $body")
                }
            }
        }

    override suspend fun testConnection(): Result<String> = translate("hello", "en", "zh").map { "连接成功：$it" }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** DeepL：API Key（free/pro 自动选择主机） */
class DeepLEngine(private val key: String, private val mode: String) : TranslateEngine {

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val host = if (mode == "pro") "api.deepl.com" else "api-free.deepl.com"
                val body = buildJsonObject {
                    put("text", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(text)) })
                    put("target_lang", kotlinx.serialization.json.JsonPrimitive(LangMapper.forDeepL(targetLang)))
                    if (sourceLang != "auto") put("source_lang", kotlinx.serialization.json.JsonPrimitive(LangMapper.forDeepL(sourceLang)))
                }
                val req = Request.Builder()
                    .url("https://$host/v2/translate")
                    .header("Authorization", "DeepL-Auth-Key $key")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val b = resp.body?.string() ?: throw IOException("空响应")
                    if (!resp.isSuccessful) throw IOException("DeepL HTTP ${resp.code}: $b")
                    engineJson.parseToJsonElement(b).jsonObject["translations"]?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?: throw IOException("响应异常")
                }
            }
        }

    override suspend fun testConnection(): Result<String> =
        translate("hello", "en", "zh").map { "连接成功：$it" }
}

/** 微软 Azure Translator：订阅密钥 + region */
class AzureEngine(private val key: String, private val region: String) : TranslateEngine {

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.cognitive.microsofttranslator.com/translate" +
                    "?api-version=3.0&to=${LangMapper.forAzure(targetLang)}" +
                    if (sourceLang != "auto") "&from=${LangMapper.forAzure(sourceLang)}" else ""
                val body = buildJsonArray { add(buildJsonObject { put("Text", kotlinx.serialization.json.JsonPrimitive(text)) }) }
                val req = Request.Builder().url(url)
                    .header("Ocp-Apim-Subscription-Key", key)
                    .header("Ocp-Apim-Subscription-Region", region)
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val b = resp.body?.string() ?: throw IOException("空响应")
                    if (!resp.isSuccessful) throw IOException("Azure HTTP ${resp.code}: $b")
                    engineJson.parseToJsonElement(b).jsonArray.firstOrNull()?.jsonObject
                        ?.get("translations")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content ?: throw IOException("响应异常")
                }
            }
        }

    override suspend fun testConnection(): Result<String> =
        translate("hello", "en", "zh").map { "连接成功：$it" }
}

/** 腾讯云翻译 TMT：TC3-HMAC-SHA256 签名（v3） */
class TencentEngine(private val secretId: String, private val secretKey: String, private val region: String) : TranslateEngine {

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val service = "tmt"
                val host = "tmt.tencentcloudapi.com"
                val timestamp = System.currentTimeMillis() / 1000
                val body = buildJsonObject {
                    put("SourceText", kotlinx.serialization.json.JsonPrimitive(text))
                    put("Source", kotlinx.serialization.json.JsonPrimitive(LangMapper.forTencent(sourceLang)))
                    put("Target", kotlinx.serialization.json.JsonPrimitive(LangMapper.forTencent(targetLang)))
                    put("ProjectId", kotlinx.serialization.json.JsonPrimitive(0))
                }
                val payload = body.toString()
                val canonicalRequest = "POST\n/\n\n" +
                    "content-type:application/json; charset=utf-8\nhost:$host\nx-tc-action:texttranslate\n" +
                    "\ncontent-type;host;x-tc-action\n" + sha256Hex(payload)
                val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n" +
                    "${java.time.LocalDate.ofEpochDay(timestamp / 86400).format(java.time.format.DateTimeFormatter.ISO_DATE)}/$service/tc3_request\n" +
                    sha256Hex(canonicalRequest)
                val kDate = hmac(("TC3$secretKey").toByteArray(), java.time.LocalDate.ofEpochDay(timestamp / 86400)
                    .format(java.time.format.DateTimeFormatter.ISO_DATE).toByteArray())
                val kService = hmac(kDate, service.toByteArray())
                val kSigning = hmac(kService, "tc3_request".toByteArray())
                val signature = hex(hmac(kSigning, stringToSign.toByteArray()))

                val auth = "TC3-HMAC-SHA256 Credential=$secretId/" +
                    "${java.time.LocalDate.ofEpochDay(timestamp / 86400).format(java.time.format.DateTimeFormatter.ISO_DATE)}/$service/tc3_request, " +
                    "SignedHeaders=content-type;host;x-tc-action, Signature=$signature"
                val req = Request.Builder().url("https://$host")
                    .header("Authorization", auth)
                    .header("X-TC-Action", "TextTranslate")
                    .header("X-TC-Version", "2018-03-21")
                    .header("X-TC-Region", region)
                    .header("X-TC-Timestamp", timestamp.toString())
                    .post(payload.toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val b = resp.body?.string() ?: throw IOException("空响应")
                    val obj = engineJson.parseToJsonElement(b).jsonObject
                    obj["Response"]?.jsonObject?.get("Error")?.let { e ->
                        throw IOException("腾讯错误 ${e.jsonObject["Code"]?.jsonPrimitive?.content}")
                    }
                    obj["Response"]?.jsonObject?.get("TargetText")?.jsonPrimitive?.content
                        ?: throw IOException("响应异常: $b")
                }
            }
        }

    override suspend fun testConnection(): Result<String> =
        translate("hello", "en", "zh").map { "连接成功：$it" }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
