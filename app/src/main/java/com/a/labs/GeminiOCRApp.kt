package com.a.labs

import android.app.Application
import com.a.labs.core.crash.GlobalCrashHandler

class GeminiOCRApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this, defaultHandler))
    }
}