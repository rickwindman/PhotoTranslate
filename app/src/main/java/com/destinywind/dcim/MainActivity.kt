package com.destinywind.dcim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.destinywind.dcim.ui.camera.CameraScreen
import com.destinywind.dcim.ui.result.ResultScreen
import com.destinywind.dcim.ui.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

object Routes {
    const val CAMERA = "camera"
    const val RESULT = "result"
    const val SETTINGS = "settings"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = Routes.CAMERA) {
                        // 单栈导航：拍照页为根，结果页返回即回拍照页，不留历史堆叠
                        composable(Routes.CAMERA) {
                            CameraScreen(
                                onCaptured = { nav.navigate(Routes.RESULT) { launchSingleTop = true } },
                                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }
                            )
                        }
                        composable(Routes.RESULT) {
                            ResultScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } })
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) { Text(text) }
