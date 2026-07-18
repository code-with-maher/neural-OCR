package com.a.labs

import android.app.Application
import com.a.labs.core.crash.GlobalCrashHandler

class GeminiOCRApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // إعداد الفخ: تسجيل معالج الأخطاء فور تشغيل التطبيق
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this, defaultHandler))
    }
}

