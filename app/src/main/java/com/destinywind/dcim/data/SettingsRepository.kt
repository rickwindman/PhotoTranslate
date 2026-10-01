package com.destinywind.dcim.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class EngineId { LOCAL, FREE }

@Serializable
data class AppSettings(
    val defaultEngine: EngineId = EngineId.LOCAL,
    val sourceLang: String = "auto",
    val targetLang: String = "zh",
    /** 聊天气泡字号倍率（0.8~1.6） */
    val chatFontScale: Float = 1.0f,
    /** ML Kit 离线翻译语言包仅 Wi-Fi 下载 */
    val wifiOnlyDownload: Boolean = true,
)

/**
 * 配置仓库：DataStore Preferences 存 JSON blob，变更实时生效（StateFlow）。
 * v1.0.1（chat 分支）：仅本地/免费两个引擎，无密钥类配置。
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    init {
        runBlocking { reload() }
    }

    suspend fun reload() {
        val base = runCatching {
            val raw = context.dataStore.data.first()[KEY_BLOB]
            if (raw != null) json.decodeFromString<AppSettings>(raw) else AppSettings()
        }.getOrDefault(AppSettings())
        _settings.value = base
    }

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { it[KEY_BLOB] = json.encodeToString(s) }
        _settings.value = s
    }

    /** 恢复默认 */
    suspend fun resetDefaults() {
        context.dataStore.edit { it.remove(KEY_BLOB) }
        _settings.value = AppSettings()
    }

    /** 免费引擎当日用量统计（字符数 / 请求数） */
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

    companion object {
        private val KEY_BLOB = stringPreferencesKey("settings_blob")
        private val KEY_QUOTA_DATE = stringPreferencesKey("quota_date")
        private val KEY_QUOTA_CHARS = stringPreferencesKey("quota_chars")
        private val KEY_QUOTA_REQ = stringPreferencesKey("quota_requests")
    }
}
