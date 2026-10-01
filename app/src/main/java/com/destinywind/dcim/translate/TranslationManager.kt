package com.destinywind.dcim.translate

import com.destinywind.dcim.data.AppSettings
import com.destinywind.dcim.data.EngineId
import com.destinywind.dcim.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 翻译管理器：引擎选择 + 去重 + 降级链。
 * 降级顺序：当前引擎失败 → 免费翻译（MyMemory）→ 返回失败原因。
 */
@Singleton
class TranslationManager @Inject constructor(
    private val settingsRepo: SettingsRepository,
) {

    @Volatile private var appContext: android.content.Context? = null
    fun attachContext(ctx: android.content.Context) { appContext = ctx }

    fun engineFor(id: EngineId, s: AppSettings): TranslateEngine? = when (id) {
        EngineId.LOCAL -> MlKitEngine()
        EngineId.FREE -> appContext?.let { MyMemoryEngine(settingsRepo, it.cacheDir) }
    }

    /**
     * 翻译一组文本（去重：相同文本只请求一次）。
     * 返回与输入等长的结果列表；失败时 Result.failure。
     */
    suspend fun translateLines(
        lines: List<String>, engineId: EngineId, source: String, target: String,
    ): Result<List<String>> {
        val s = settingsRepo.settings.value
        val engine = engineFor(engineId, s)
            ?: return Result.failure(IllegalStateException("翻译引擎不可用"))

        val unique = lines.distinct()
        val map = HashMap<String, String>(unique.size)
        var firstError: Throwable? = null

        for (text in unique) {
            val r = engine.translate(text, source, target)
            r.fold(
                onSuccess = { map[text] = it },
                onFailure = { firstError = it },
            )
        }

        val allOk = unique.all { map.containsKey(it) }
        if (!allOk) {
            // 降级：当前引擎失败 → MyMemory
            if (engineId != EngineId.FREE) {
                val fallback = appContext?.let { MyMemoryEngine(settingsRepo, it.cacheDir) }
                if (fallback != null) {
                    for (text in unique.filter { !map.containsKey(it) }) {
                        fallback.translate(text, source, target).fold(
                            onSuccess = { map[text] = it },
                            onFailure = { },
                        )
                    }
                }
            }
        }
        if (!unique.all { map.containsKey(it) }) {
            return Result.failure(
                firstError ?: IllegalStateException("翻译失败，请检查网络或引擎配置"),
            )
        }
        return Result.success(lines.map { map[it] ?: it })
    }
}
