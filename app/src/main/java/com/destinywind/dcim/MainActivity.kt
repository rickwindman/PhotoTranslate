package com.destinywind.dcim

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.destinywind.dcim.ui.chat.ChatScreen
import com.destinywind.dcim.ui.chat.PendingProcessText
import com.destinywind.dcim.ui.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
}

/**
 * 入口 Activity（v1.0.1 精简版）：
 * - 聊天式翻译主页 + 设置页，单栈导航
 * - 支持其他应用"选中文字 → 翻译"（ACTION_PROCESS_TEXT）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleProcessText(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(
                        navController = nav,
                        startDestination = Routes.CHAT,
                        // 页面过渡：淡入淡出 + 轻微水平位移，避免生硬切换
                        enterTransition = {
                            fadeIn(androidx.compose.animation.core.tween(240)) +
                                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(240)) { it / 10 }
                        },
                        exitTransition = { fadeOut(androidx.compose.animation.core.tween(180)) },
                        popEnterTransition = { fadeIn(androidx.compose.animation.core.tween(240)) },
                        popExitTransition = {
                            fadeOut(androidx.compose.animation.core.tween(180)) +
                                androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(240)) { it / 10 }
                        },
                    ) {
                        composable(Routes.CHAT) {
                            ChatScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } })
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleProcessText(intent)
    }

    /** 其他应用选中文本分享过来：塞进全局流，ChatScreen/ViewModel 自动翻译 */
    private fun handleProcessText(intent: Intent?) {
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (intent?.action == Intent.ACTION_PROCESS_TEXT && text.isNotEmpty()) {
            PendingProcessText.flow.value = text
        }
    }
}
