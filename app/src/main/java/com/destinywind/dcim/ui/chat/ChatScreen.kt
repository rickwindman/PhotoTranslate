package com.destinywind.dcim.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinywind.dcim.data.EngineId
import com.destinywind.dcim.ui.chat.ChatMessage

private val LANG_OPTIONS = listOf("自动检测" to "auto", "中文" to "zh", "英文" to "en", "日文" to "ja", "韩文" to "ko")

private fun langName(code: String) = LANG_OPTIONS.firstOrNull { it.second == code }?.first ?: code

/**
 * 聊天式输入翻译主页：
 * 语言窄条 → 消息流（右=原文气泡 / 左=译文气泡：引擎角标+复制）→ 底部输入栏。
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val fontSp = 14f * settings.chatFontScale
    val engineLabel = if (settings.defaultEngine == EngineId.LOCAL) "本地离线" else "免费在线"

    fun sendCurrent() {
        viewModel.send(input)
        input = ""
    }

    // 新消息自动滚到底部
    LaunchedEffect(state.messages.size, state.pending) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Scaffold(
        topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Text("翻译", fontSize = 18.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            }
        },
    ) { inner ->
        // imePadding 加在整列上：键盘弹出时页面整体抬升，顶栏保持可见、输入栏紧贴键盘
        Column(modifier = Modifier.fillMaxSize().padding(inner).imePadding()) {

            // ---- 语言窄条 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = { viewModel.setLanguages(target = settings.sourceLang, source = "auto") },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                ) {
                    Text(
                        if (settings.sourceLang == "auto") "自动检测" else langName(settings.sourceLang),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                IconButton(onClick = { viewModel.swapLanguages() }, modifier = Modifier.width(34.dp)) {
                    Icon(
                        Icons.Filled.SwapHoriz, contentDescription = "交换语言",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp),
                    )
                }
                Surface(
                    onClick = {
                        val others = LANG_OPTIONS.filter { it.second != "auto" && it.second != settings.targetLang }
                        viewModel.setLanguages(settings.targetLang, others.firstOrNull()?.second ?: "en")
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                ) {
                    Text(
                        langName(settings.targetLang),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { viewModel.toggleEngine() },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                ) {
                    Text(
                        engineLabel + " ▾",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }

            // ---- 消息流 ----
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "输入文字开始翻译\n在输入框打字后点发送",
                                textAlign = TextAlign.Center, color = Color(0xFF9CA3AF), fontSize = 13.sp, lineHeight = 22.sp,
                            )
                        }
                    }
                }
                items(state.messages, key = { it.id }) { m ->
                    if (m.role == ChatMessage.Role.SRC) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(
                                shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.widthIn(max = 280.dp),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(m.text, color = Color.White, fontSize = fontSp.sp, lineHeight = (fontSp * 1.5f).sp)
                                    Text(
                                        langName(m.lang), color = Color.White.copy(alpha = 0.6f), fontSize = (9 * settings.chatFontScale).sp,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.widthIn(max = 280.dp),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    if (m.text.isEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                                            Text("  翻译中…", fontSize = fontSp.sp, color = Color(0xFF9CA3AF))
                                        }
                                    } else {
                                        Text(m.text, fontSize = fontSp.sp, lineHeight = (fontSp * 1.5f).sp)
                                    }
                                    Row(
                                        modifier = Modifier.padding(top = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            m.engine, fontSize = (9 * settings.chatFontScale).sp,
                                            color = Color(0xFF065F46).copy(alpha = 0.75f),
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (m.text.isNotEmpty()) {
                                            IconButton(
                                                onClick = { clipboard.setText(AnnotatedString(m.text)) },
                                                modifier = Modifier.width(26.dp),
                                            ) {
                                                Icon(
                                                    Icons.Filled.ContentCopy, contentDescription = "复制",
                                                    modifier = Modifier.width(13.dp), tint = Color(0xFF6B7280),
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

            // ---- 底部输入栏 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入文字，发送翻译…", fontSize = 13.sp) },
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { sendCurrent() },
                    shape = RoundedCornerShape(12.dp),
                    color = if (input.isNotBlank() && state.canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    modifier = Modifier.width(56.dp).padding(bottom = 1.dp),
                ) {
                    Text(
                        "发送", color = Color.White, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
}
