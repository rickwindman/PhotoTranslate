package com.destinywind.dcim.ui.result

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinywind.dcim.core.ocr.ImageUtils
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 翻译结果页（全屏沉浸式，与拍照页一致）：
 * - 照片铺满全屏，顶部引擎栏与底部控制栏半透明浮层
 * - 原文/译文标签点击原地切换；长按隐藏译文（松开恢复）、点击单块切换该块显示模式
 * - 译文块按 OCR 四点框映射（与显示图同一 EXIF 朝向，保证原位）
 */
@Composable
fun ResultScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTranslated by remember { mutableStateOf(true) }
    var overlayHidden by remember { mutableStateOf(false) }
    var originalBlocks by remember { mutableStateOf(setOf<Int>()) }

    // 与 OCR 完全相同的解码（EXIF 转正 + 同一降采样上限），保证叠加坐标原位
    val bitmap = remember(state.imageFile) {
        state.imageFile?.let {
            runCatching { ImageUtils.decodeUpright(it, ImageUtils.SHARE_MAX_DIM) }.getOrNull()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101010))) {

        // ---------- 全屏图片层 ----------
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val areaW = maxWidth
            val areaH = maxHeight
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap == null) {
                    Text("图片加载失败", color = Color.White)
                } else {
                    val scale = minOf(areaW.value / bitmap.width, areaH.value / bitmap.height)
                    val drawW = bitmap.width * scale
                    val drawH = bitmap.height * scale
                    Box(
                        modifier = Modifier
                            .width(drawW.dp).height(drawH.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = { overlayHidden = true },
                                    onPress = {
                                        tryAwaitRelease()
                                        overlayHidden = false
                                    },
                                )
                            },
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = {
                                android.widget.ImageView(it).apply {
                                    scaleType = android.widget.ImageView.ScaleType.FIT_XY
                                    setImageBitmap(bitmap)
                                }
                            },
                        )
                        val showOverlay = showTranslated && !overlayHidden &&
                            state.phase != ResultUiState.Phase.OCR
                        if (showOverlay) {
                            state.lines.forEachIndexed { i, line ->
                                val g = quadGeometry(line.box) ?: return@forEachIndexed
                                val t = state.translations.getOrNull(i) ?: ""
                                val showOriginal = originalBlocks.contains(i)
                                val text = if (showOriginal || t.isBlank()) line.text else t
                                val dark = state.lineDark.getOrNull(i) ?: false
                                // 微信扫一扫式双层结构：
                                // 外层 = 轴对齐 AABB（不旋转），模糊背景与原图像素精确对齐，完全不透明盖住原文
                                // 内层 = 译文按原文方向与框尺寸旋转 + 自适应字号
                                val blur = state.blurredImage
                                // 模糊采样区域：AABB 外扩 2px，防止边缘露字；钳制到图内
                                val sx = (g.ax - 2f).toInt().coerceAtLeast(0)
                                val sy = (g.ay - 2f).toInt().coerceAtLeast(0)
                                val ex = (g.ax + g.aw + 2f).toInt()
                                    .coerceAtMost(blur?.width ?: Int.MAX_VALUE)
                                val ey = (g.ay + g.ah + 2f).toInt()
                                    .coerceAtMost(blur?.height ?: Int.MAX_VALUE)
                                val outerW = (ex - sx) * scale
                                val outerH = (ey - sy) * scale
                                val blockW = g.w * scale
                                val blockH = g.h * scale
                                Box(
                                    modifier = Modifier
                                        .width(outerW.dp).height(outerH.dp)
                                        .offset((sx * scale).dp, (sy * scale).dp)
                                        .pointerInput(i) {
                                            detectTapGestures(onTap = {
                                                originalBlocks =
                                                    if (showOriginal) originalBlocks - i else originalBlocks + i
                                            })
                                        },
                                ) {
                                    if (blur != null && !showOriginal) {
                                        // 背景层：从模糊大图裁出本行区域铺满（与原图像素对齐，无露字）
                                        val painter = remember(blur, sx, sy, ex, ey) {
                                            BitmapPainter(
                                                blur,
                                                srcOffset = IntOffset(sx, sy),
                                                srcSize = IntSize(ex - sx, ey - sy),
                                            )
                                        }
                                        Image(
                                            painter = painter,
                                            contentDescription = null,
                                            contentScale = ContentScale.FillBounds,
                                            modifier = Modifier.matchParentSize(),
                                        )
                                        // 文字层：译文按原文方向 + 字号，深色背景自动白字
                                        Box(
                                            modifier = Modifier.matchParentSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(blockW.dp).height(blockH.dp)
                                                    .graphicsLayer { rotationZ = g.angleDeg }
                                                    .background(
                                                        Color.White.copy(alpha = state.overlayOpacity * 0.35f),
                                                        RoundedCornerShape(3.dp),
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                AutoFitText(
                                                    text = text,
                                                    boxW = blockW.dp,
                                                    boxH = blockH.dp,
                                                    maxSp = blockH * 0.72f,
                                                    color = if (dark) Color.White else Color.Black,
                                                )
                                            }
                                        }
                                    } else {
                                        // 回退（模糊图未就绪）/ 原文模式：白纱 + 原地文字
                                        Box(
                                            modifier = Modifier.matchParentSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(blockW.dp).height(blockH.dp)
                                                    .graphicsLayer { rotationZ = g.angleDeg }
                                                    .background(
                                                        (if (showOriginal) Color(0xE6FFFFFF) else Color(0xFF1E5ADC))
                                                            .copy(alpha = state.overlayOpacity),
                                                        RoundedCornerShape(3.dp),
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                AutoFitText(
                                                    text = text,
                                                    boxW = blockW.dp,
                                                    boxH = blockH.dp,
                                                    maxSp = blockH * 0.72f,
                                                    color = if (showOriginal) Color(0xFF333333) else Color.White,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 长按隐藏提示（贴在底部控制栏上方）
            if (overlayHidden) {
                Text(
                    "已临时隐藏译文",
                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 116.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            // 识别/翻译进度遮罩
            if (state.phase == ResultUiState.Phase.OCR || state.phase == ResultUiState.Phase.TRANSLATE) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.phase == ResultUiState.Phase.OCR) "本地识别 · 检测→方向→识别→解码"
                            else "翻译中 · 失败自动降级",
                            color = Color(0xFF9AA0A6), style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // 错误（含未下载模型提示）
            if (state.phase == ResultUiState.Phase.ERROR) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(state.message, color = Color.White, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        if (state.needModelDownload) {
                            Button(onClick = onOpenSettings) { Text("去设置页下载模型") }
                        }
                        Button(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("返回拍照") }
                    }
                }
            }
        }

        // ---------- 顶部浮层：返回 + 引擎切换 + 状态 ----------
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0x66000000))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            var menuOpen by remember { mutableStateOf(false) }
            AssistChip(
                onClick = { menuOpen = true },
                label = { Text(state.engine.label) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                UiEngine.entries.forEach { e ->
                    DropdownMenuItem(text = { Text(e.label) }, onClick = {
                        menuOpen = false; viewModel.setEngine(e)
                    })
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                state.message, style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8CE99A), textAlign = TextAlign.End, modifier = Modifier.width(130.dp),
            )
        }

        // ---------- 底部浮层：原文/译文原地切换 + 语言选择 ----------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x66000000)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showTranslated,
                    onClick = { showTranslated = false },
                    label = { Text("原文") },
                )
                FilterChip(
                    selected = showTranslated,
                    onClick = { showTranslated = true },
                    label = { Text("译文") },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "长按隐藏译文",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDC1C6),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LanguageChip("源语言", state.sourceLang) { viewModel.setLanguages(it, state.targetLang) }
                Text("→", color = Color(0xFFBDC1C6))
                LanguageChip("目标语言", state.targetLang) { viewModel.setLanguages(state.sourceLang, it) }
            }
        }
    }
}

private val languages = listOf(
    "auto" to "自动", "zh" to "中文", "en" to "英文", "ja" to "日文", "ko" to "韩文",
)

/** OCR 四点框渲染几何：中心、未旋转宽高、旋转角（度，直接用于 rotationZ）、轴对齐外接框（AABB） */
private data class QuadGeometry(
    val cx: Float, val cy: Float,
    val w: Float, val h: Float,
    val angleDeg: Float,
    val ax: Float, val ay: Float, val aw: Float, val ah: Float,
)

/** 由四点框计算渲染几何（左原点排序规则）；无效框返回 null */
private fun quadGeometry(box: FloatArray): QuadGeometry? {
    if (box.size < 8) return null
    val px = FloatArray(4) { box[it * 2] }
    val py = FloatArray(4) { box[it * 2 + 1] }

    // ① 原点 p0 = 最左点（min x，平局取 min y）
    var oi = 0
    for (i in 1 until 4) {
        if (px[i] < px[oi] - 0.01f || (kotlin.math.abs(px[i] - px[oi]) <= 0.01f && py[i] < py[oi])) oi = i
    }

    // ② 其余三点按相对 p0 的极角升序（屏幕 y 向下 = 顺时针）→ p1,p2,p3
    val others = (0 until 4).filter { it != oi }.sortedBy { i ->
        atan2(py[i] - py[oi], px[i] - px[oi])
    }
    val qx = floatArrayOf(px[oi], px[others[0]], px[others[1]], px[others[2]])
    val qy = floatArrayOf(py[oi], py[others[0]], py[others[1]], py[others[2]])

    // ③ 文字方向 = 长边：d3 > d1 时行进边取 p0→p3（竖排模式）
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
    // 轴对齐外接框（模糊背景层用：与原图像素对齐）
    val minX = minOf(qx[0], qx[1], qx[2], qx[3])
    val minY = minOf(qy[0], qy[1], qy[2], qy[3])
    val maxX = maxOf(qx[0], qx[1], qx[2], qx[3])
    val maxY = maxOf(qy[0], qy[1], qy[2], qy[3])
    return QuadGeometry(
        cx, cy, w.coerceAtLeast(24f), h.coerceAtLeast(16f), angle,
        minX, minY, maxX - minX, maxY - minY,
    )
}

/**
 * 逐块自适应字号文本：二分查找"能放进框内的最大字号"（单位 sp）。
 * 每块独立计算，同一照片中大框得大字、小框得小字；纯 measure 调用，无反复重组。
 */
@Composable
private fun AutoFitText(
    text: String,
    boxW: androidx.compose.ui.unit.Dp,
    boxH: androidx.compose.ui.unit.Dp,
    maxSp: Float,
    color: Color,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fittedSp = remember(text, boxW, boxH, maxSp) {
        val upper = maxSp.coerceIn(6f, 48f)
        val maxW = with(density) { (boxW - 6.dp).toPx().toInt().coerceAtLeast(1) }
        val maxH = with(density) { (boxH).toPx().toInt().coerceAtLeast(1) }
        fun fits(size: Float): Boolean {
            val r = textMeasurer.measure(
                text = text,
                style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.15f).sp),
                constraints = Constraints(maxWidth = maxW, maxHeight = maxH),
            )
            return !r.hasVisualOverflow
        }
        var lo = 6f
        var hi = upper
        if (fits(hi)) {
            hi  // 上限就放得下，直接用
        } else {
            // 二分收敛到最大可容纳字号
            var result = 6f
            while (hi - lo > 0.5f) {
                val mid = (lo + hi) / 2f
                if (fits(mid)) { result = mid; lo = mid } else hi = mid
            }
            result
        }
    }
    Text(
        text,
        color = color,
        fontSize = fittedSp.sp,
        lineHeight = (fittedSp * 1.15f).sp,
        textAlign = TextAlign.Center,
        softWrap = true,
        modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp),
    )
}

@Composable
private fun LanguageChip(label: String, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text("$label：${languages.firstOrNull { it.first == current }?.second ?: current} ▾") },
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        val options = languages.filter { !(it.first == "auto" && label.startsWith("目标")) }
        options.forEach { (code, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(code) })
        }
    }
}
