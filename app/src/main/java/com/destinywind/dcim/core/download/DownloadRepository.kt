package com.destinywind.dcim.core.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 单模型下载状态 */
data class DownloadState(
    val modelId: String = "",
    val phase: Phase = Phase.IDLE,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val progress: Float = 0f,   // 当前文件 0..1
    val speedBps: Long = 0,
    val message: String = "",
) {
    enum class Phase { IDLE, WAITING_WIFI, RUNNING, DONE, FAILED, CANCELLED }

    val overall: Float
        get() = if (fileCount <= 0) 0f
        else ((fileIndex + progress) / fileCount).coerceIn(0f, 1f)
}

/** 下载状态中枢：Worker 写入，UI 观察 */
@Singleton
class DownloadRepository @Inject constructor() {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    fun update(state: DownloadState) {
        _states.value = _states.value + (state.modelId to state)
    }

    fun get(modelId: String): DownloadState = _states.value[modelId] ?: DownloadState(modelId = modelId)

    fun clear(modelId: String) {
        _states.value = _states.value - modelId
    }
}
