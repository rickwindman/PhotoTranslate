package com.destinywind.dcim.core.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** 图片解码工具：EXIF 方向统一修正（OCR 与结果页显示必须使用同一份朝向） */
object ImageUtils {

    /** 解码并按 EXIF 方向转正。OCR 输入与结果页显示都必须走这里，保证坐标一致 */
    fun decodeUpright(file: File): Bitmap {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
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
