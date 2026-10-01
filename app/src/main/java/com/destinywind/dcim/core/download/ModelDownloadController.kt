package com.destinywind.dcim.core.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.destinywind.dcim.core.model.ModelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 下载入队/取消控制器 */
@Singleton
class ModelDownloadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepo: ModelRepository,
    private val downloadRepo: DownloadRepository,
) {
    val states: StateFlow<Map<String, DownloadState>> = downloadRepo.states

    fun stateOf(modelId: String): DownloadState = downloadRepo.get(modelId)

    fun enqueue(modelId: String, wifiOnly: Boolean, customMirrorBase: String) {
        val model = modelRepo.find(modelId) ?: return
        // Worker 里的镜像列表：自定义 Base URL 优先，其次模型自带镜像
        ModelDownloadWorker.modelPrefixes =
            (if (customMirrorBase.isNotBlank()) listOf("$customMirrorBase/") else emptyList()) + model.mirrorPrefixes
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_MODEL_ID to modelId,
                    ModelDownloadWorker.KEY_WIFI_ONLY to wifiOnly,
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("model_dl_$modelId", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(modelId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("model_dl_$modelId")
        downloadRepo.update(downloadRepo.get(modelId).copy(phase = DownloadState.Phase.CANCELLED, message = "已取消"))
    }
}
