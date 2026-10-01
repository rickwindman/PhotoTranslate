package com.destinywind.dcim.core.ocr

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PaddleOCR(NCNN) JNI 封装。
 * 推理管线复用 FeiGeChuanShu/ncnn_paddleocr（BSD-3-Clause），模型从应用私有目录加载。
 * 模型目录要求：det.param / det.bin / rec.param / rec.bin / keys.txt（可选 cls.param / cls.bin）。
 */
@Singleton
class OcrEngine @Inject constructor() {

    private var inited = false
    private var loadedDir: String? = null

    private external fun nativeInit(
        detParam: String, detBin: String,
        clsParam: String?, clsBin: String?,
        recParam: String, recBin: String,
        keysPath: String, recHeight: Int,
    ): Boolean

    private external fun nativeRelease()

    private external fun nativeDetect(bitmap: Bitmap): Array<OcrLine>

    /** 从模型目录初始化引擎；切换模型时重新加载。 */
    @Synchronized
    fun initFromDir(dir: File, recHeight: Int = 48): Boolean {
        if (inited && loadedDir == dir.absolutePath) return true
        val detParam = File(dir, "det.param")
        val detBin = File(dir, "det.bin")
        val recParam = File(dir, "rec.param")
        val recBin = File(dir, "rec.bin")
        val keys = File(dir, "keys.txt")
        if (!detParam.isFile || !detBin.isFile || !recParam.isFile || !recBin.isFile || !keys.isFile) {
            Log.w(TAG, "model files incomplete in ${dir.absolutePath}")
            return false
        }
        val ok = try {
            nativeInit(
                detParam.absolutePath, detBin.absolutePath,
                File(dir, "cls.param").takeIf { it.isFile }?.absolutePath,
                File(dir, "cls.bin").takeIf { it.isFile }?.absolutePath,
                recParam.absolutePath, recBin.absolutePath,
                keys.absolutePath, recHeight,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "nativeInit failed", t); false
        }
        inited = ok
        if (ok) loadedDir = dir.absolutePath
        return ok
    }

    @Synchronized
    fun release() {
        if (inited) { nativeRelease(); inited = false; loadedDir = null }
    }

    /** 检测+识别，返回按阅读顺序排序的文本行（后台线程调用）。 */
    fun detect(bitmap: Bitmap): List<OcrLine> {
        check(inited) { "OcrEngine not initialized" }
        return try {
            nativeDetect(bitmap).filter { it.text.isNotBlank() }
        } catch (t: Throwable) {
            Log.e(TAG, "detect failed", t); emptyList()
        }
    }

    fun isReady(): Boolean = inited

    companion object {
        private const val TAG = "OcrEngine"
        init { System.loadLibrary("paddleocrncnn") }
    }
}
