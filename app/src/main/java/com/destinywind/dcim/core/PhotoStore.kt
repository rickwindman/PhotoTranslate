package com.destinywind.dcim.core

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 当前拍摄的照片（拍照页 → 结果页共享） */
data class CaptureSession(val file: File, val width: Int = 0, val height: Int = 0)

@Singleton
class PhotoStore @Inject constructor() {
    private val _current = MutableStateFlow<CaptureSession?>(null)
    val current: StateFlow<CaptureSession?> = _current
    fun set(file: File) { _current.value = CaptureSession(file) }
    fun clear() { _current.value = null }
    fun uri(): Uri? = _current.value?.file?.let { Uri.fromFile(it) }
}
