package com.a.labs.data.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

class SystemTtsWrapper(private val context: Context) {

    suspend fun generateSpeech(text: String, fileName: String): Result<File> = suspendCancellableCoroutine { continuation ->
        var tts: TextToSpeech? = null
        val outputFile = File(context.cacheDir, "$fileName.wav")

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // استخدام Builder بدلاً من الـ Constructor القديم
                val arabicLocale = Locale.Builder().setLanguage("ar").build()
                
                if (tts?.isLanguageAvailable(arabicLocale) ?: -1 >= TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = arabicLocale
                } else {
                    tts?.language = Locale.getDefault()
                }

                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, fileName)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        tts?.stop()
                        tts?.shutdown()
                        if (continuation.isActive) continuation.resume(Result.success(outputFile))
                    }
                    override fun onError(utteranceId: String?) {
                        tts?.stop()
                        tts?.shutdown()
                        if (continuation.isActive) continuation.resume(Result.failure(Exception("System TTS Error")))
                    }
                })

                val result = tts?.synthesizeToFile(text, params, outputFile, fileName)
                if (result != TextToSpeech.SUCCESS) {
                    tts?.shutdown()
                    if (continuation.isActive) continuation.resume(Result.failure(Exception("Failed to start synthesis")))
                }
            } else {
                if (continuation.isActive) continuation.resume(Result.failure(Exception("TTS Initialization Failed")))
            }
        }

        continuation.invokeOnCancellation {
            tts?.stop()
            tts?.shutdown()
        }
    }
}