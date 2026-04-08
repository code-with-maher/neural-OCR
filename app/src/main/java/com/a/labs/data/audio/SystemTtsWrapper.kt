package com.a.labs.data.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class SystemTtsWrapper(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                val arabicLocale = Locale.Builder().setLanguage("ar").build()
                
                if (tts?.isLanguageAvailable(arabicLocale) ?: -1 >= TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = arabicLocale
                } else {
                    tts?.language = Locale.getDefault()
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onPlaybackStateChanged?.invoke(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        onPlaybackStateChanged?.invoke(false)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onPlaybackStateChanged?.invoke(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onPlaybackStateChanged?.invoke(false)
                    }
                })
            }
        }
    }

    fun speak(text: String) {
        if (!isReady) return
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sys_tts_id")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "sys_tts_id")
    }

    fun stop() {
        if (isReady && tts?.isSpeaking == true) {
            tts?.stop()
            onPlaybackStateChanged?.invoke(false)
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
    }
}