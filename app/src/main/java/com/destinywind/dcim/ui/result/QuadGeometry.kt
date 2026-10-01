package com.destinywind.dcim.ui.result

import kotlin.math.atan2
import kotlin.math.hypot

/** OCR 行四点框渲染几何：质心、未旋转宽高、旋转角（度）、轴对齐外接框（AABB） */
data class QuadGeometry(
    val cx: Float, val cy: Float,
    val w: Float, val h: Float,
    val angleDeg: Float,
    val ax: Float, val ay: Float, val aw: Float, val ah: Float,
)

/**
 * 由四点框计算行级渲染几何（左原点排序规则）：
 * ① 原点 p0 = 最左点（min x，平局取 min y）
 * ② 其余三点按相对 p0 的极角升序（屏幕 y 向下 = 顺时针）→ p1,p2,p3
 * ③ 文字方向 = 长边：d3 > d1 时行进边取 p0→p3（竖排模式），angle 按行进边方向
 */
fun quadGeometry(box: FloatArray): QuadGeometry? {
    if (box.size < 8) return null
    val px = FloatArray(4) { box[it * 2] }
    val py = FloatArray(4) { box[it * 2 + 1] }

    var oi = 0
    for (i in 1 until 4) {
        if (px[i] < px[oi] - 0.01f || (kotlin.math.abs(px[i] - px[oi]) <= 0.01f && py[i] < py[oi])) oi = i
    }

    val others = (0 until 4).filter { it != oi }.sortedBy { i ->
        atan2(py[i] - py[oi], px[i] - px[oi])
    }
    val qx = floatArrayOf(px[oi], px[others[0]], px[others[1]], px[others[2]])
    val qy = floatArrayOf(py[oi], py[others[0]], py[others[1]], py[others[2]])

    val d1 = hypot(qx[1] - qx[0], qy[1] - qy[0])
    val d3 = hypot(qx[3] - qx[0], qy[3] - qy[0])
    val vertical = d3 > d1
    val w = if (vertical) d3 else d1
    val h = if (vertical) d1 else d3
    if (w < 8f || h < 8f) return null
    val tipX = if (vertical) qx[3] else qx[1]
    val tipY = if (vertical) qy[3] else qy[1]
    var angle = Math.toDegrees(atan2((tipY - qy[0]).toDouble(), (tipX - qx[0]).toDouble())).toFloat()
    if (angle > 90f) angle -= 180f
    if (angle < -90f) angle += 180f

    val cx = (qx[0] + qx[1] + qx[2] + qx[3]) / 4f
    val cy = (qy[0] + qy[1] + qy[2] + qy[3]) / 4f
    val minX = minOf(qx[0], qx[1], qx[2], qx[3])
    val minY = minOf(qy[0], qy[1], qy[2], qy[3])
    val maxX = maxOf(qx[0], qx[1], qx[2], qx[3])
    val maxY = maxOf(qy[0], qy[1], qy[2], qy[3])
    return QuadGeometry(
        cx, cy, w.coerceAtLeast(24f), h.coerceAtLeast(16f), angle,
        minX, minY, maxX - minX, maxY - minY,
    )
}
