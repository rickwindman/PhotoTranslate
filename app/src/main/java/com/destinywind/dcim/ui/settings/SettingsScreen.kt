package com.destinywind.dcim.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinywind.dcim.core.download.DownloadState
import com.destinywind.dcim.core.model.ModelInfo
import com.destinywind.dcim.data.AppSettings
import com.destinywind.dcim.data.EngineId
import java.util.Locale

/** 设置页：可展开分组列表（本地 OCR 模型 / 本地翻译 ML Kit / 免费 / 常规 / AI / 通用） */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 设置直接读写持久层（自动保存），不再需要"保存"按钮
    val editing = state.settings
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()

    var addDialog by rememberSaveable { mutableStateOf(false) }

    // 弹窗提示
    val toast = state.toast
    if (toast.isNotEmpty()) {
        Box(Modifier.fillMaxSize()) // 占位；实际提示用简洁底部条
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

            // 通用设置（固定最顶部，不随分组展开被挤出屏幕）
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("⚙ 通用设置", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        DropdownRow("默认翻译引擎", listOf(
                            EngineId.LOCAL to "本地翻译（离线）", EngineId.FREE to "免费翻译（MyMemory）",
                            EngineId.CLOUD_BAIDU to "常规翻译（百度）", EngineId.CLOUD_DEEPL to "常规翻译（DeepL）",
                            EngineId.CLOUD_AZURE to "常规翻译（微软）", EngineId.CLOUD_TENCENT to "常规翻译（腾讯）",
                            EngineId.AI to "AI 翻译",
                        ), editing.defaultEngine) { viewModel.update(editing.copy(defaultEngine = it)) }
                        DropdownRowText("源语言", listOf(
                            "auto" to "自动（混合语言）", "en" to "英文", "zh" to "中文", "ja" to "日文", "ko" to "韩文",
                        ), editing.sourceLang) { viewModel.update(editing.copy(sourceLang = it)) }
                        DropdownRowText("目标语言", listOf(
                            "zh" to "中文（简体）", "en" to "英文", "ja" to "日文", "ko" to "韩文",
                        ), editing.targetLang) { viewModel.update(editing.copy(targetLang = it)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("译文叠加透明度 ${"%.0f".format(Locale.US, editing.overlayOpacity * 100)}%", modifier = Modifier.weight(1f))
                            Slider(value = editing.overlayOpacity, onValueChange = { viewModel.update(editing.copy(overlayOpacity = it)) }, valueRange = 0.4f..1f, modifier = Modifier.width(160.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("译文叠加字号 ×${"%.1f".format(Locale.US, editing.overlayFontScale)}", modifier = Modifier.weight(1f))
                            Slider(value = editing.overlayFontScale, onValueChange = { viewModel.update(editing.copy(overlayFontScale = it)) }, valueRange = 0.7f..1.6f, modifier = Modifier.width(160.dp))
                        }
                        Text("提示：源语言选“自动”时本地翻译会自动识别语言", fontSize = 10.sp, color = Color(0xFF999999))
                    }
                }
            }

            // OCR 模型
            item {
                Group("🔍 OCR 模型（PaddleOCR 离线识别）", initiallyOpen = true) {
                    Text("用于识别图片里的文字，所有翻译引擎都依赖它；未下载将无法识别", fontSize = 11.sp, color = Color(0xFF8A8A8A))
                    Spacer(Modifier.height(6.dp))
                    state.modelRows.forEach { row ->
                        ModelCard(
                            row = row,
                            active = state.settings.activeModelId == row.info.modelId,
                            activating = state.activatingModelId == row.info.modelId,
                            onDownload = { viewModel.downloadModel(row.info.modelId) },
                            onCancel = { viewModel.cancelDownload(row.info.modelId) },
                            onEnable = { viewModel.enableModel(row.info.modelId) },
                            onDelete = { viewModel.deleteModel(row.info.modelId) },
                        )
                    }
                    Button(onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 通过链接添加 OCR 模型") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editing.mirrorBaseUrl,
                        onValueChange = { viewModel.update(editing.copy(mirrorBaseUrl = it)) },
                        label = { Text("自定义镜像 Base URL（可选，优先于内置镜像）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("仅 Wi-Fi 自动下载", modifier = Modifier.weight(1f))
                        Switch(checked = editing.wifiOnlyDownload, onCheckedChange = { viewModel.update(editing.copy(wifiOnlyDownload = it)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("单模型体积上限：${editing.maxModelSizeMb} MB", modifier = Modifier.weight(1f))
                        Slider(
                            value = editing.maxModelSizeMb.toFloat(),
                            onValueChange = { viewModel.update(editing.copy(maxModelSizeMb = it.toInt())) },
                            valueRange = 100f..2000f,
                            modifier = Modifier.width(160.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已占用 ${state.totalModelBytes / 1024 / 1024} MB", fontSize = 12.sp, color = Color(0xFF666666))
                        Spacer(Modifier.weight(1f))
                        Text("清除全部模型缓存", color = Color(0xFFEA4335), fontSize = 12.sp, modifier = Modifier.clickable { viewModel.clearAllCaches() })
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
                Group("🆓 免费翻译（MyMemory · 默认引擎）") {
                    Text("引擎：MyMemory · 匿名可用，无需密钥", fontSize = 12.sp)
                    Text("今日已用：${state.quotaChars} 字符 / ${state.quotaRequests} 次请求（约 5,000 字符/日额度）", fontSize = 12.sp, color = Color(0xFF666666))
                    Text("清除翻译缓存", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.clearTranslationCache() }.padding(top = 4.dp))
                }
            }

            // ④ 常规翻译
            item {
                Group("☁ 常规翻译（自配密钥）") {
                    // 当前常规引擎选择
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            EngineId.CLOUD_BAIDU to "百度", EngineId.CLOUD_DEEPL to "DeepL",
                            EngineId.CLOUD_AZURE to "微软", EngineId.CLOUD_TENCENT to "腾讯",
                        ).forEach { (id, label) ->
                            FilterChip(selected = editing.cloudEngine == id, onClick = { viewModel.update(editing.copy(cloudEngine = id)) }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    when (editing.cloudEngine) {
                        EngineId.CLOUD_BAIDU -> {
                            SecretField("AppID", secrets, "baiduAppId") { viewModel.updateSecret("baiduAppId", it) }
                            SecretField("密钥（MD5 签名）", secrets, "baiduKey") { viewModel.updateSecret("baiduKey", it) }
                            TestRow("测试连接") { viewModel.testConnection(EngineId.CLOUD_BAIDU, editing) { ok, msg -> viewModel.toast(msg) } }
                        }
                        EngineId.CLOUD_DEEPL -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("接口类型", modifier = Modifier.weight(1f))
                                listOf("free" to "Free", "pro" to "Pro").forEach { (v, label) ->
                                    FilterChip(selected = editing.deeplMode == v, onClick = { viewModel.update(editing.copy(deeplMode = v)) }, label = { Text(label) })
                                }
                            }
                            SecretField("API Key", secrets, "deeplKey") { viewModel.updateSecret("deeplKey", it) }
                            TestRow("测试连接") { viewModel.testConnection(EngineId.CLOUD_DEEPL, editing) { ok, msg -> viewModel.toast(msg) } }
                        }
                        EngineId.CLOUD_AZURE -> {
                            OutlinedTextField(
                                value = editing.azureRegion, onValueChange = { viewModel.update(editing.copy(azureRegion = it)) },
                                label = { Text("Region（如 eastasia）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            SecretField("订阅密钥", secrets, "azureKey") { viewModel.updateSecret("azureKey", it) }
                            TestRow("测试连接") { viewModel.testConnection(EngineId.CLOUD_AZURE, editing) { ok, msg -> viewModel.toast(msg) } }
                        }
                        EngineId.CLOUD_TENCENT -> {
                            SecretField("SecretId", secrets, "tencentSecretId") { viewModel.updateSecret("tencentSecretId", it) }
                            SecretField("SecretKey（v3 签名）", secrets, "tencentSecretKey") { viewModel.updateSecret("tencentSecretKey", it) }
                            TestRow("测试连接") { viewModel.testConnection(EngineId.CLOUD_TENCENT, editing) { ok, msg -> viewModel.toast(msg) } }
                        }
                        else -> {}
                    }
                    Text("密钥经 Jetpack Security 加密存储，不会写入代码或日志", fontSize = 10.sp, color = Color(0xFF999999))
                }
            }

            // ⑤ AI 翻译
            item {
                Group("🤖 AI 翻译（OpenAI 兼容 · 默认关闭）") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用 AI 翻译", modifier = Modifier.weight(1f))
                        Switch(checked = editing.aiEnabled, onCheckedChange = { viewModel.update(editing.copy(aiEnabled = it)) })
                    }
                    OutlinedTextField(
                        value = editing.aiBaseUrl, onValueChange = { viewModel.update(editing.copy(aiBaseUrl = it)) },
                        label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = editing.aiModel, onValueChange = { viewModel.update(editing.copy(aiModel = it)) },
                        label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    SecretField("API Key", secrets, "aiKey") { viewModel.updateSecret("aiKey", it) }
                    OutlinedTextField(
                        value = editing.aiPrompt, onValueChange = { viewModel.update(editing.copy(aiPrompt = it)) },
                        label = { Text("系统提示词") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        Text("温度 ${"%.1f".format(Locale.US, editing.aiTemperature)}", modifier = Modifier.weight(1f))
                        Slider(
                            value = editing.aiTemperature, onValueChange = { viewModel.update(editing.copy(aiTemperature = (it * 10).toInt() / 10f)) },
                            valueRange = 0f..2f, modifier = Modifier.width(160.dp),
                        )
                    }
                    OutlinedTextField(
                        value = editing.aiMaxTokens.toString(),
                        onValueChange = { viewModel.update(editing.copy(aiMaxTokens = it.toIntOrNull() ?: 2048)) },
                        label = { Text("最大生成长度") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    TestRow("测试连接") { viewModel.testConnection(EngineId.AI, editing) { ok, msg -> viewModel.toast(msg) } }
                }
            }

            // 版本号
            item {
                Text("拍照翻译 v1.0.11 (12) · com.destinywind.dcim", fontSize = 10.sp, color = Color(0xFF999999), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
        }
    }

    if (addDialog) {
        AddModelDialog(
            onDismiss = { addDialog = false },
            onSubmit = { name, ver, langs, main, backups, dict, sha, ncnn, unsafe ->
                viewModel.addCustomModel(name, ver, langs, main, backups, dict, sha, ncnn, unsafe) { ok, msg ->
                    viewModel.toast(msg)
                    if (ok) addDialog = false
                }
            },
        )
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

/** OCR 模型卡片：状态 + 下载/启用/删除 */
@Composable
private fun ModelCard(
    row: ModelRow,
    active: Boolean,
    activating: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onEnable: () -> Unit,
    onDelete: () -> Unit,
) {
    val m = row.info
    // 高亮颜色平滑过渡，启用/取消启用不生硬
    val containerColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (active) Color(0xFFE8F0FE) else MaterialTheme.colorScheme.surface,
        animationSpec = androidx.compose.animation.core.tween(250),
        label = "modelCardColor",
    )
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = containerColor,
    )) {
        Column(modifier = Modifier.padding(12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name + if (m.custom) "（自定义）" else "", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${"%.1f".format(Locale.US, m.sizeBytes / 1024.0 / 1024.0)} MB", fontSize = 11.sp, color = Color(0xFF888888))
            }
            Text(
                "${m.version} · ${m.languages.joinToString("/")} · ${m.description}" +
                    if (m.status == ModelInfo.STATUS_INVALID) " · ⚠ ${m.invalidReason}" else "",
                fontSize = 11.sp, color = Color(0xFF666666),
            )
            val dl = row.download
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                when {
                    dl.phase == DownloadState.Phase.RUNNING || dl.phase == DownloadState.Phase.WAITING_WIFI -> {
                        Text(if (dl.phase == DownloadState.Phase.WAITING_WIFI) dl.message else "下载中 ${"%.0f".format(Locale.US, dl.overall * 100)}%", fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("取消", color = Color(0xFFEA4335), fontSize = 11.sp, modifier = Modifier.clickable { onCancel() })
                    }
                    row.installed -> {
                        if (activating) {
                            // 启用中：行内转圈，明确告知正在加载引擎
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("启用中，正在加载模型…", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        } else {
                            Text(if (active) "✓ 已启用" else "已安装", color = Color(0xFF34A853), fontSize = 11.sp, modifier = Modifier.weight(1f))
                            if (!active) Text("启用", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.clickable { onEnable() }.padding(end = 12.dp))
                            Text("删除", color = Color(0xFFEA4335), fontSize = 12.sp, modifier = Modifier.clickable { onDelete() })
                        }
                    }
                    dl.phase == DownloadState.Phase.FAILED -> {
                        Text("失败：${dl.message}", color = Color(0xFFEA4335), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("重试", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.clickable { onDownload() })
                    }
                    else -> {
                        Text(if (m.status == ModelInfo.STATUS_INVALID) "不可用" else "未下载", fontSize = 11.sp, color = Color(0xFF888888), modifier = Modifier.weight(1f))
                        Text("下载", color = Color.White, fontSize = 12.sp, modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .clickable { onDownload() }
                            .padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                }
            }
            if (dl.phase == DownloadState.Phase.RUNNING) {
                LinearProgressIndicator(progress = { dl.overall }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("${dl.message} · ${dl.speedBps / 1024} KB/s", fontSize = 10.sp, color = Color(0xFF999999))
            }
        }
    }
}

@Composable
private fun SecretField(label: String, secrets: Map<String, String>, key: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = secrets[key] ?: "",
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
    )
}

@Composable
private fun TestRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) { Text(label) }
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

/** 通过链接添加模型（表单：名称/版本/语言/主链/备用链/字典/校验值/格式/不安全源） */
@Composable
private fun AddModelDialog(
    onDismiss: () -> Unit,
    onSubmit: (name: String, version: String, languages: List<String>, main: String, backups: List<String>, dict: String, sha: String, ncnn: Boolean, allowUnsafe: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var langs by remember { mutableStateOf(setOf("中")) }
    var main by remember { mutableStateOf("") }
    var backups by remember { mutableStateOf("") }
    var dict by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var ncnn by remember { mutableStateOf(true) }
    var unsafe by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("通过链接添加 OCR 模型", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("模型名称 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("模型版本") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf("中", "英", "日", "韩", "其他").forEach { l ->
                        FilterChip(selected = langs.contains(l), onClick = { langs = if (langs.contains(l)) langs - l else langs + l }, label = { Text(l) })
                    }
                }
                OutlinedTextField(value = main, onValueChange = { main = it }, label = { Text("主下载链接 *（https 直链，.zip 或 .param/.bin）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = backups, onValueChange = { backups = it }, label = { Text("备用链接（每行一个）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = dict, onValueChange = { dict = it }, label = { Text("字典文件链接（单文件模式时必填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = sha, onValueChange = { sha = it }, label = { Text("校验值 SHA256（可选，填写则强制校验）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NCNN 格式（关闭则按 Paddle Lite .nb 处理）", fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Switch(checked = ncnn, onCheckedChange = { ncnn = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许不安全源（http）", fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Checkbox(checked = unsafe, onCheckedChange = { unsafe = it })
                }
                if (error.isNotEmpty()) Text(error, color = Color(0xFFEA4335), fontSize = 11.sp)
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank() || main.isBlank()) { error = "名称与主链接必填"; return@Button }
                            onSubmit(name, version, langs.toList(), main.trim(), backups.lines().map { it.trim() }.filter { it.isNotEmpty() }, dict.trim(), sha.trim(), ncnn, unsafe)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("提交下载") }
                }
                Text("安全：默认仅 https · 体积上限见设置 · zip 防路径穿越 · 校验通过才注册启用", fontSize = 9.sp, color = Color(0xFF999999), modifier = Modifier.padding(top = 6.dp))
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
