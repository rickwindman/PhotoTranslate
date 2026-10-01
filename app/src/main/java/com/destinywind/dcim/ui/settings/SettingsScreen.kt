package com.destinywind.dcim.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinywind.dcim.data.EngineId
import java.util.Locale

/** 设置页（v1.0.1 精简版）：通用设置 / 本地翻译语言包 / 关于 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 设置直接读写持久层（自动保存），不再需要"保存"按钮
    val editing = state.settings

    // 弹窗提示
    val toast = state.toast
    if (toast.isNotEmpty()) {
        LaunchedToast(text = toast) { viewModel.consumeToast() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        // 顶栏（设置改动自动保存，无需"保存/取消"按钮）
        Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.resetDefaults() }) { Text("恢复默认") }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {

            // ① 通用设置
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("⚙ 通用设置", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        DropdownRow("默认翻译引擎", listOf(
                            EngineId.LOCAL to "本地翻译（离线）", EngineId.FREE to "免费翻译（MyMemory）",
                        ), editing.defaultEngine) { viewModel.update(editing.copy(defaultEngine = it)) }
                        DropdownRowText("源语言", listOf(
                            "auto" to "自动（混合语言）", "en" to "英文", "zh" to "中文", "ja" to "日文", "ko" to "韩文",
                        ), editing.sourceLang) { viewModel.update(editing.copy(sourceLang = it)) }
                        DropdownRowText("目标语言", listOf(
                            "zh" to "中文（简体）", "en" to "英文", "ja" to "日文", "ko" to "韩文",
                        ), editing.targetLang) { viewModel.update(editing.copy(targetLang = it)) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("聊天字号 ×${"%.1f".format(Locale.US, editing.chatFontScale)}", modifier = Modifier.weight(1f))
                            Slider(
                                value = editing.chatFontScale,
                                onValueChange = { viewModel.update(editing.copy(chatFontScale = (it * 10).toInt() / 10f)) },
                                valueRange = 0.8f..1.6f,
                                modifier = Modifier.width(160.dp),
                            )
                        }
                        Text("提示：源语言选“自动”时本地翻译会自动识别语言", fontSize = 10.sp, color = Color(0xFF999999))
                    }
                }
            }

            // ② 本地翻译（ML Kit）
            item {
                Group("🌐 本地翻译（离线 ML Kit）") {
                    Text("离线翻译模型（语言包），首次联网下载后完全离线可用", fontSize = 11.sp, color = Color(0xFF8A8A8A))
                    Spacer(Modifier.height(6.dp))
                    state.mlKitRows.forEach { row ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(row.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(if (row.downloaded) "✓ 已下载 · 可离线" else "约 30 MB · 未下载", fontSize = 11.sp, color = Color(0xFF666666))
                                }
                                if (row.downloaded) {
                                    Text("删除", color = Color(0xFFEA4335), fontSize = 12.sp, modifier = Modifier.clickable { viewModel.deleteMlKit(row.code) })
                                } else {
                                    Text("下载", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.downloadMlKit(row.code) })
                                }
                            }
                        }
                    }
                }
            }

            // ③ 免费翻译
            item {
                Group("🆓 免费翻译（MyMemory）") {
                    Text("引擎：MyMemory · 匿名可用，无需密钥", fontSize = 12.sp)
                    Text("今日已用：${state.quotaChars} 字符 / ${state.quotaRequests} 次请求（约 5,000 字符/日额度）", fontSize = 12.sp, color = Color(0xFF666666))
                    Text("清除翻译缓存", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.clearTranslationCache() }.padding(top = 4.dp))
                }
            }

            // 版本号
            item {
                Text("翻译 v1.0.1 (1) · com.destinywind.dcim", fontSize = 10.sp, color = Color(0xFF999999), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
        }
    }
}

/** 可展开分组卡片 */
@Composable
private fun Group(title: String, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandVertically(animationSpec = androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
            exit = androidx.compose.animation.shrinkVertically(animationSpec = androidx.compose.animation.core.tween(200)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp)) { content() }
        }
    }
}

@Composable
private fun DropdownRow(label: String, options: List<Pair<EngineId, String>>, current: EngineId, onPick: (EngineId) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            AssistChip(onClick = { open = true }, label = { Text(options.firstOrNull { it.first == current }?.second ?: "?") })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
                }
            }
        }
    }
}

@Composable
private fun DropdownRowText(label: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            AssistChip(onClick = { open = true }, label = { Text(options.firstOrNull { it.first == current }?.second ?: current) })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
                }
            }
        }
    }
}

/** 底部轻提示（自动消失） */
@Composable
private fun LaunchedToast(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(2200)
        onDone()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(bottom = 48.dp)
                .background(Color(0xD9202028), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
