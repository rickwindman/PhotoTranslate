package com.destinywind.dcim.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class EngineId { LOCAL, FREE, CLOUD_BAIDU, CLOUD_DEEPL, CLOUD_AZURE, CLOUD_TENCENT, AI }

@Serializable
data class AppSettings(
    // 通用
    val defaultEngine: EngineId = EngineId.FREE,
    val sourceLang: String = "en",
    val targetLang: String = "zh",
    val overlayOpacity: Float = 0.82f,
    val overlayFontScale: Float = 1.0f,
    val showOverlay: Boolean = true,
    // 本地翻译（ML Kit）
    val mlKitEnabled: Boolean = false,
    // 常规翻译
    val cloudEngine: EngineId = EngineId.CLOUD_BAIDU,
    val baiduAppId: String = "",
    val deeplMode: String = "free", // free / pro
    val azureRegion: String = "",
    val tencentRegion: String = "ap-guangzhou",
    // AI 翻译
    val aiEnabled: Boolean = false,
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    val aiPrompt: String = "你是一个专业的翻译助手，请将用户输入的内容翻译成目标语言，只输出译文，不要解释。",
    val aiTemperature: Float = 0.3f,
    val aiMaxTokens: Int = 2048,
    // 模型下载
    val activeModelId: String? = null,  // 当前启用的 OCR 模型（同一时间仅一个）
    val wifiOnlyDownload: Boolean = true,
    val maxModelSizeMb: Int = 500,
    val mirrorBaseUrl: String = "", // 自定义镜像 Base URL（前缀拼接，优先于内置镜像）
)

/**
 * 配置仓库：普通配置 → DataStore Preferences；密钥类 → EncryptedSharedPreferences（Jetpack Security）。
 * 变更实时生效（StateFlow）。
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val secrets: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secure_settings", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    private var secretCache: Map<String, String> = emptyMap()

    init {
        runBlocking { reload() }
    }

    suspend fun reload() {
        val base = runCatching {
            val raw = context.dataStore.data.first()[KEY_BLOB]
            if (raw != null) json.decodeFromString<AppSettings>(raw) else AppSettings()
        }.getOrDefault(AppSettings())
        // baiduAppId 虽为账号类，一并加密存储
        _settings.value = base.copy(baiduAppId = secrets.getString("baiduAppId", "") ?: "")
        secretCache = SECRET_KEYS.mapNotNull { k -> secrets.getString(k, null)?.let { k to it } }.toMap()
    }

    fun secret(key: String): String = secretCache[key] ?: ""

    suspend fun save(s: AppSettings, secretsToUpdate: Map<String, String> = emptyMap()) {
        context.dataStore.edit { it[KEY_BLOB] = json.encodeToString(s) }
        if (secretsToUpdate.isNotEmpty()) {
            secrets.edit().apply { secretsToUpdate.forEach { (k, v) -> putString(k, v) } }.apply()
        }
        secretCache = SECRET_KEYS.mapNotNull { k -> secrets.getString(k, null)?.let { k to it } }.toMap()
        _settings.value = s
    }

    suspend fun updateQuota(chars: Int, requests: Int) {
        context.dataStore.edit { p ->
            val today = java.time.LocalDate.now().toString()
            if (p[KEY_QUOTA_DATE] != today) { p[KEY_QUOTA_DATE] = today; p[KEY_QUOTA_CHARS] = "0"; p[KEY_QUOTA_REQ] = "0" }
            p[KEY_QUOTA_CHARS] = ((p[KEY_QUOTA_CHARS]?.toIntOrNull() ?: 0) + chars).toString()
            p[KEY_QUOTA_REQ] = ((p[KEY_QUOTA_REQ]?.toIntOrNull() ?: 0) + requests).toString()
        }
    }

    suspend fun quota(): Pair<Int, Int> {
        val p = context.dataStore.data.first()
        return if (p[KEY_QUOTA_DATE] != java.time.LocalDate.now().toString()) 0 to 0
        else (p[KEY_QUOTA_CHARS]?.toIntOrNull() ?: 0) to (p[KEY_QUOTA_REQ]?.toIntOrNull() ?: 0)
    }

    /** 恢复默认：清除自定义配置，回退 MyMemory；不删除已下载模型 */
    suspend fun resetDefaults() {
        context.dataStore.edit { it.remove(KEY_BLOB) }
        _settings.value = AppSettings()
    }

    companion object {
        private val KEY_BLOB = stringPreferencesKey("settings_blob")
        private val KEY_QUOTA_DATE = stringPreferencesKey("quota_date")
        private val KEY_QUOTA_CHARS = stringPreferencesKey("quota_chars")
        private val KEY_QUOTA_REQ = stringPreferencesKey("quota_requests")
        private val SECRET_KEYS = listOf(
            "baiduAppId", "baiduKey", "deeplKey", "azureKey", "tencentSecretId", "tencentSecretKey", "aiKey",
        )
    }
}
