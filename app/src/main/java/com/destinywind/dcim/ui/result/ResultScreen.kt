package com.destinywind.dcim.ui.result

import android.graphics.Bitmap
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinywind.dcim.core.ocr.ImageUtils

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

    // 与 OCR 完全相同的解码（EXIF 转正），保证叠加坐标原位
    val bitmap = remember(state.imageFile) {
        state.imageFile?.let { runCatching { ImageUtils.decodeUpright(it) }.getOrNull() }
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
                                val t = state.translations.getOrNull(i) ?: ""
                                val showOriginal = originalBlocks.contains(i)
                                val text = if (showOriginal || t.isBlank()) line.text else t
                                val xs = List(4) { line.box[it * 2] }
                                val ys = List(4) { line.box[it * 2 + 1] }
                                val minX = (xs.min() * scale).toFloat()
                                val minY = (ys.min() * scale).toFloat()
                                val w = ((xs.max() - xs.min()) * scale).coerceAtLeast(24f)
                                val h = ((ys.max() - ys.min()) * scale).coerceAtLeast(16f)
                                val fontSize = ((h / (if (text.length > 14) 2 else 1)) * 0.8f)
                                    .coerceIn(8f, 20f)
                                Box(
                                    modifier = Modifier
                                        .width(w.dp).height(h.dp)
                                        .offset(minX.dp, minY.dp)
                                        .background(
                                            if (showOriginal) Color(0xE6FFFFFF) else Color(0xD31E5ADC),
                                            RoundedCornerShape(3.dp),
                                        )
                                        .pointerInput(i) {
                                            detectTapGestures(onTap = {
                                                originalBlocks =
                                                    if (showOriginal) originalBlocks - i else originalBlocks + i
                                            })
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text,
                                        color = if (showOriginal) Color(0xFF333333) else Color.White,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.15).sp,
                                        maxLines = 3,
                                        modifier = Modifier.padding(horizontal = 3.dp),
                                        textAlign = TextAlign.Center,
                                    )
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
