package com.a.labs.data.audio

import android.content.Context
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
                tts?.language = Locale.getDefault()
                val params = android.os.Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, fileName)
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        tts?.stop()
                        tts?.shutdown()
                        continuation.resume(Result.success(outputFile))
                    }
                    override fun onError(utteranceId: String?) {
                        continuation.resume(Result.failure(Exception("System TTS Error")))
                    }
                })
                
                tts?.synthesizeToFile(text, params, outputFile, fileName)
            } else {
                continuation.resume(Result.failure(Exception("TTS Initialization Failed")))
            }
        }

        continuation.invokeOnCancellation {
            tts?.stop()
            tts?.shutdown()
        }
    }
}