package com.destinywind.dcim.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.destinywind.dcim.core.model.ModelRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 模型下载 Worker：多镜像兜底 + Range 断点续传 + SHA256 校验 + 原子替换 + zip 安全解压。
 * 失败自动切换下一个镜像；临时 .part 文件中断后可续传。
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val modelRepo: ModelRepository,
    private val downloadRepo: DownloadRepository,
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val model = modelRepo.find(modelId) ?: return@withContext Result.failure()
        val dir = modelRepo.modelDir(modelId)

        fun emit(phase: DownloadState.Phase, fileIndex: Int = 0, progress: Float = 0f, speed: Long = 0, msg: String = "") {
            downloadRepo.update(
                DownloadState(modelId, phase, fileIndex, model.files.size, progress, speed, msg)
            )
        }

        try {
            emit(DownloadState.Phase.RUNNING, msg = "准备下载")

            // Wi-Fi 策略：仅 Wi-Fi 自动下载时，非 Wi-Fi 挂起等待
            val settingsWifiOnly = inputData.getBoolean(KEY_WIFI_ONLY, true)
            if (settingsWifiOnly && !isWifi()) {
                emit(DownloadState.Phase.WAITING_WIFI, msg = "等待 Wi-Fi 连接…")
                return@withContext Result.retry()
            }

            if (model.zip) {
                downloadZipModel(model, dir) { idx, prog, speed, msg -> emit(DownloadState.Phase.RUNNING, idx, prog, speed, msg) }
                val invalid = ZipSafe.validateNcnnModelDir(dir)
                if (invalid != null) {
                    modelRepo.markInvalid(modelId, invalid)
                    emit(DownloadState.Phase.FAILED, msg = invalid)
                    return@withContext Result.failure()
                }
            } else {
                for ((idx, file) in model.files.withIndex()) {
                    downloadFile(file.url, file.sha256, file.sizeBytes, dir, File(dir, file.name), file.backupUrls) { prog, speed, msg ->
                        emit(DownloadState.Phase.RUNNING, idx, prog, speed, msg)
                    }
                }
            }
            modelRepo.markInvalid(modelId, "")
            emit(DownloadState.Phase.DONE, fileIndex = model.files.size, progress = 1f)
            Result.success()
        } catch (se: SecurityException) {
            modelRepo.markInvalid(modelId, se.message ?: "下载校验失败")
            emit(DownloadState.Phase.FAILED, msg = se.message ?: "失败")
            Result.failure()
        } catch (ce: CanceledException) {
            emit(DownloadState.Phase.CANCELLED, msg = "已取消")
            Result.failure()
        } catch (e: Exception) {
            emit(DownloadState.Phase.FAILED, msg = e.message ?: "网络错误")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /** 单文件下载：备用链+多镜像依次尝试，.part 断点续传，SHA256 校验，下载完原子替换 */
    private fun downloadFile(
        url: String, sha256: String?, expectedSize: Long?, dir: File, finalFile: File,
        backupUrls: List<String> = emptyList(),
        onProgress: (Float, Long, String) -> Unit,
    ) {
        val candidates = buildList {
            (modelPrefixes + "").distinct().forEach { add("${it}$url") }
            backupUrls.forEach { add(it) }
        }
        var lastErr: Exception? = null
        for (candidate in candidates) {
            try {
                singleMirror(candidate, sha256, expectedSize, dir, finalFile, onProgress)
                return
            } catch (ce: CanceledException) {
                throw ce
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IOException("所有镜像下载失败")
    }

    private fun singleMirror(
        url: String, sha256: String?, expectedSize: Long?, dir: File, finalFile: File,
        onProgress: (Float, Long, String) -> Unit,
    ) {
        dir.mkdirs()
        val part = File(dir, finalFile.name + ".part")
        val total: Long = expectedSize ?: run {
            val head = client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                if (!resp.isSuccessful) -1L else resp.header("Content-Length")?.toLongOrNull() ?: -1L
            }
            if (head <= 0) -1L else head
        }
        var downloaded = if (part.isFile && total > 0 && part.length() <= total) part.length() else 0L
        if (downloaded > 0 && total > 0 && downloaded == total) {
            // 已完成，校验后替换
            verifyAndPublish(part, finalFile, sha256)
            onProgress(1f, 0, "完成")
            return
        }

        val reqB = Request.Builder().url(url)
            .header("Accept-Encoding", "identity")
            .addHeader("Range", "bytes=$downloaded-")
        client.newCall(reqB.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 416) throw IOException("HTTP ${resp.code}")
            if (resp.code == 416) { // range 越界=已下完
                verifyAndPublish(part, finalFile, sha256); onProgress(1f, 0, "完成"); return
            }
            val resumeOk = resp.code == 206
            if (!resumeOk) { downloaded = 0; part.delete() }
            val body = resp.body ?: throw IOException("空响应")
            val contentLen = body.contentLength()
            val realTotal = if (resumeOk && total > 0) total else if (contentLen > 0) contentLen + downloaded else -1L
            var src: java.io.InputStream = body.byteStream()
            // 部分服务器忽略 Accept-Encoding 对 gzip 的强制，检测魔数解包
            src = GzipGuard.wrap(src)
            val start = System.currentTimeMillis()
            src.use { input ->
                val digest = if (sha256 != null || expectedSize != null) java.security.MessageDigest.getInstance("SHA-256") else null
                java.io.FileOutputStream(part, downloaded > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastTick = start
                    var lastBytes = downloaded
                    while (true) {
                        if (isStopped) throw CanceledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        digest?.update(buf, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 400) {
                            val speed = (downloaded - lastBytes) * 1000 / (now - lastTick).coerceAtLeast(1)
                            val frac = if (realTotal > 0) downloaded.toFloat() / realTotal else 0f
                            onProgress(frac.coerceIn(0f, 0.999f), speed, if (realTotal > 0) "下载中" else "下载中（未知大小）")
                            lastTick = now; lastBytes = downloaded
                        }
                    }
                    out.flush()
                }
                // 完整性校验
                if (realTotal > 0 && downloaded != realTotal) {
                    throw IOException("文件不完整（$downloaded/$realTotal），稍后续传")
                }
                if (sha256 != null && digest != null) {
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!hex.equals(sha256, ignoreCase = true)) {
                        part.delete()
                        throw IOException("SHA256 校验失败")
                    }
                }
            }
            verifyAndPublish(part, finalFile, sha256)
            onProgress(1f, 0, "完成")
        }
    }

    private fun verifyAndPublish(part: File, finalFile: File, sha256: String?) {
        if (sha256 != null) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            part.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(sha256, ignoreCase = true)) { part.delete(); throw IOException("SHA256 校验失败") }
        }
        finalFile.delete()
        if (!part.renameTo(finalFile)) {
            // 原子替换失败时复制替换
            part.copyTo(finalFile, overwrite = true)
            part.delete()
        }
    }

    private fun downloadZipModel(
        model: com.destinywind.dcim.core.model.ModelInfo, dir: File,
        onProgress: (Int, Float, Long, String) -> Unit,
    ) {
        val tmpZip = File(dir, "model.zip.part")
        var lastErr: Exception? = null
        for (prefix in (model.mirrorPrefixes + "").distinct()) {
            try {
                val url = prefix + (model.files.firstOrNull()?.url ?: model.sourceUrl)
                singleMirror(url, model.expectedSha256, null, dir, File(dir, "model.zip")) { p, s, m ->
                    onProgress(0, p, s, m)
                }
                onProgress(0, 1f, 0, "解压中…")
                val zipFile = File(dir, "model.zip")
                zipFile.inputStream().use { ZipSafe.extract(it, dir) }
                zipFile.delete(); tmpZip.delete()
                onProgress(0, 1f, 0, "解压完成")
                return
            } catch (ce: CanceledException) { throw ce }
            catch (e: Exception) { lastErr = e }
        }
        throw lastErr ?: IOException("zip 模型下载失败")
    }

    private fun isWifi(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    class CanceledException : Exception("已取消")

    companion object {
        const val KEY_MODEL_ID = "modelId"
        const val KEY_WIFI_ONLY = "wifiOnly"
        /** 由 ModelRepository 注入的自定义镜像前缀，在 enqueue 时传入 */
        var modelPrefixes: List<String> = emptyList()
    }
}

/** 检测并解包被强制 gzip 的响应流 */
object GzipGuard {
    fun wrap(src: java.io.InputStream): java.io.InputStream {
        if (!src.markSupported()) return src
        src.mark(2)
        val b0 = src.read(); val b1 = src.read()
        src.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(src) else src
    }
}
