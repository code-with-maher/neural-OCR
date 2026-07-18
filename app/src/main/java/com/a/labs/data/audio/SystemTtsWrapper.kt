package com.a.labs.data.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class SystemTtsWrapper(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var paragraphs = listOf<String>()
    private var currentIndex = 0
    private var isManuallyPaused = false

    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onHighlightProgress: ((Int) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                val arabicLocale = Locale.Builder().setLanguage("ar").build()
                tts?.language = if (tts?.isLanguageAvailable(arabicLocale) ?: -1 >= TextToSpeech.LANG_AVAILABLE) arabicLocale else Locale.getDefault()

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onPlaybackStateChanged?.invoke(true)
                        onHighlightProgress?.invoke(currentIndex)
                    }

                    override fun onDone(utteranceId: String?) {
                        if (!isManuallyPaused && currentIndex < paragraphs.size - 1) {
                            currentIndex++
                            readCurrent()
                        } else if (currentIndex == paragraphs.size - 1) {
                            onPlaybackStateChanged?.invoke(false)
                            onHighlightProgress?.invoke(-1)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onPlaybackStateChanged?.invoke(false)
                    }
                })
            }
        }
    }

    fun speak(content: String, startIndex: Int = 0) {
        if (!isReady) return
        paragraphs = content.split("\n\n").filter { it.isNotBlank() }
        currentIndex = startIndex.coerceIn(0, paragraphs.size - 1)
        isManuallyPaused = false
        readCurrent()
    }

    private fun readCurrent() {
        if (currentIndex < paragraphs.size) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "paragraph_$currentIndex")
            tts?.speak(paragraphs[currentIndex], TextToSpeech.QUEUE_FLUSH, params, "paragraph_$currentIndex")
        }
    }

    fun stop(manual: Boolean = true) {
        isManuallyPaused = manual
        tts?.stop()
        onPlaybackStateChanged?.invoke(false)
    }

    fun resume() {
        if (paragraphs.isNotEmpty()) {
            isManuallyPaused = false
            readCurrent()
        }
    }

    fun release() {
        tts?.shutdown()
    }
}
