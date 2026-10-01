package com.destinywind.dcim.core.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** 图片解码工具：EXIF 方向统一修正（OCR 与结果页显示必须使用同一份朝向） */
object ImageUtils {

    /**
     * OCR 与结果页显示共用的降采样上限（长边）。
     * 两处必须用同一参数解码同一文件，才能保证位图尺寸一致、叠加坐标原位。
     */
    const val SHARE_MAX_DIM = 1280

    /**
     * 解码并按 EXIF 方向转正。
     * @param maxDim 长边像素上限（0 = 不降采样）。降采样可避免 1200 万像素照片
     *               解码成 ~48MB 位图导致 OOM 闪退。
     */
    fun decodeUpright(file: File, maxDim: Int = 0): Bitmap {
        var sample = 1
        if (maxDim > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val m = maxOf(bounds.outWidth, bounds.outHeight)
            if (m > 0) {
                while (m / (sample * 2) >= maxDim) sample *= 2
            }
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("无法解码图片")
        val rotation = runCatching {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
