package com.a.labs.util

import android.util.Log

object Logger {
    private const val TAG = "AppLogger"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun v(message: String) {
        Log.v(TAG, message)
    }

    fun setTag(tag: String) {
        TAG = tag
    }
}