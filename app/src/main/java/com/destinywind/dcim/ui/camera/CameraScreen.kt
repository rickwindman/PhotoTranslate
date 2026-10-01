package com.destinywind.dcim.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.concurrent.Executors

/** 拍照页：CameraX 预览 + 拍照 + 前后摄切换 + 设置入口（启动即此页，无首页） */
@Composable
fun CameraScreen(
    onCaptured: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val modelMissing by viewModel.modelMissing.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    // 预览视图与绑定（前后摄切换时重新绑定）
    val previewView = remember { PreviewView(context) }
    LaunchedEffect(lensFacing, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        // 关键：让 EXIF 方向与屏幕方向一致，否则照片会转 90°
        imageCapture.targetRotation = previewView.display.rotation
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview, imageCapture,
            )
        } catch (_: Exception) { }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView }, update = { })
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("需要相机权限才能拍照翻译", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("授予权限") }
            }
        }

        // 右上角设置
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
        }

        // 无模型提示（一键跳设置）
        if (modelMissing) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp)
                    .background(Color(0xCC1E1E28), RoundedCornerShape(12.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("⚠ 尚未下载用于识别图片文字的模型，无法识别。点击去设置页下载", color = Color(0xFF8AB4F8), style = MaterialTheme.typography.bodySmall)
            }
        }

        // 底部：前后摄 + 快门
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            }) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "切换前后摄像头", tint = Color.White)
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .border(5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                    .padding(6.dp)
                    .background(Color.White, CircleShape)
                    .clickable {
                        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                        imageCapture.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                mainHandler.post { viewModel.onPhotoSaved(file, onCaptured) }
                            }
                            override fun onError(exception: ImageCaptureException) { }
                        })
                    },
            )
            Spacer(Modifier.size(48.dp))
        }
    }
}
