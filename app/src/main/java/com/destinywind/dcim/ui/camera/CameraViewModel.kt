package com.destinywind.dcim.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destinywind.dcim.core.PhotoStore
import com.destinywind.dcim.core.ocr.OcrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val ocrRepo: OcrRepository,
    private val photoStore: PhotoStore,
) : ViewModel() {

    private val _modelMissing = MutableStateFlow(false)
    val modelMissing: StateFlow<Boolean> = _modelMissing

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _modelMissing.value = ocrRepo.ensureReady() != null }
    }

    /** 拍照保存成功后记录会话并进入结果页 */
    fun onPhotoSaved(file: File, onDone: () -> Unit) {
        photoStore.set(file)
        onDone()
    }
}
