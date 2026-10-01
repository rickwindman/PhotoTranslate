package com.destinywind.dcim.core.ocr

/** 单条 OCR 结果：文本、置信度、四点边界框（x0,y0,x1,y1,x2,y2,x3,y3，图片像素坐标） */
data class OcrLine(
    val text: String,
    val confidence: Float,
    val box: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is OcrLine && other.text == text && other.box.contentEquals(box)
    override fun hashCode(): Int = text.hashCode() * 31 + box.contentHashCode()
}
