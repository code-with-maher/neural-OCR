package com.a.labs.core.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import com.a.labs.presentation.crash.CrashDisplayActivity
import kotlin.system.exitProcess

class GlobalCrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val intent = Intent(context, CrashDisplayActivity::class.java).apply {
                putExtra("ERROR_DETAILS", throwable.stackTraceToString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)

            Process.killProcess(Process.myPid())
            exitProcess(10)
        } catch (e: Exception) {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
