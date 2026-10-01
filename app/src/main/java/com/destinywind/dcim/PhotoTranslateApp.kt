package com.destinywind.dcim

import android.app.Application
import com.destinywind.dcim.translate.TranslationManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PhotoTranslateApp : Application() {
    @Inject lateinit var translationManager: TranslationManager

    override fun onCreate() {
        super.onCreate()
        translationManager.attachContext(this)
    }
}
