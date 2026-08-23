package com.a.labs.data.audio

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.local.room.entity.PageEntity
import com.a.labs.data.remote.api.GeminiTtsClient
import com.a.labs.data.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

enum class AudioState {
    IDLE,
    PROCESSING,
    PLAYING,
    PAUSED,
    ERROR
}

object TtsEngineId {
    const val SYSTEM = "SYSTEM"
    const val ELEVENLABS = "ELEVENLABS"
    const val GEMINI_TTS = "GEMINI_TTS"
}

class AudioPlayerController(
    context: Context,
    private val repository: BookRepository,
    private val settingsManager: SettingsManager
) {
    private val appContext = context.applicationContext

    private val scope =
        CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var playbackJob: Job? = null

    private var currentBookId: String? = null
    private var currentPageNumber = -1
    private var currentEngineId = TtsEngineId.SYSTEM

    private var userIntendedToPlay = false

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE)
            as AudioManager

    private val _audioState =
        MutableStateFlow(AudioState.IDLE)

    val audioState: StateFlow<AudioState> =
        _audioState.asStateFlow()

    private val _highlightedParagraphIndex =
        MutableStateFlow(-1)

    val highlightedParagraphIndex: StateFlow<Int> =
        _highlightedParagraphIndex.asStateFlow()

    private val _errorMessage =
        MutableStateFlow<String?>(null)

    val errorMessage: StateFlow<String?> =
        _errorMessage.asStateFlow()

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.MINUTES)
            .readTimeout(15, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.MINUTES)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()

    private val geminiClient =
        GeminiTtsClient(
            context = appContext,
            client = httpClient,
            apiKey = ""
        )

    private val systemTts =
        SystemTtsWrapper(appContext).apply {

            onPlaybackStateChanged = { playing ->
                if (currentEngineId == TtsEngineId.SYSTEM) {
                    _audioState.value =
                        if (playing) {
                            AudioState.PLAYING
                        } else {
                            AudioState.PAUSED
                        }
                }
            }

            onHighlightProgress = { index ->
                _highlightedParagraphIndex.value = index
            }
        }

    private val systemFocusListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->

            if (currentEngineId != TtsEngineId.SYSTEM) {
                return@OnAudioFocusChangeListener
            }

            when (focusChange) {

                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                    if (_audioState.value == AudioState.PLAYING) {
                        systemTts.stop(manual = false)
                    }
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (userIntendedToPlay) {
                        systemTts.resume()
                    }
                }
            }
        }

    init {
        connect()
    }

    /**
     * Kept for ReaderViewModel compatibility.
     *
     * Audio playback no longer uses Media3 directly from here.
     */
    fun connect() = Unit

    fun playPage(
        bookId: String,
        pageNumber: Int
    ) {
        playbackJob?.cancel()

        playbackJob = scope.launch {

            val engine =
                settingsManager.ttsEngine.first()

            currentEngineId = engine

            val samePage =
                currentBookId == bookId &&
                    currentPageNumber == pageNumber

            if (samePage &&
                _audioState.value != AudioState.ERROR
            ) {
                togglePlayback()
                return@launch
            }

            stopCurrentPlayback()

            currentBookId = bookId
            currentPageNumber = pageNumber
            userIntendedToPlay = true

            val page =
                repository.getPageByNumber(
                    bookId,
                    pageNumber
                ) ?: run {
                    setError("تعذر العثور على الصفحة.")
                    return@launch
                }

            _highlightedParagraphIndex.value = -1

            when (engine) {

                TtsEngineId.SYSTEM ->
                    startSystemTts(page)

                TtsEngineId.GEMINI_TTS ->
                    startGeminiTts(
                        page = page,
                        bookId = bookId,
                        pageNumber = pageNumber
                    )

                TtsEngineId.ELEVENLABS ->
                    setError(
                        "محرك ElevenLabs غير متاح مؤقتًا."
                    )

                else ->
                    setError("محرك الصوت غير معروف.")
            }
        }
    }

    private suspend fun startSystemTts(
        page: PageEntity
    ) {
        stopGemini()

        requestSystemAudioFocus()

        _audioState.value =
            AudioState.PLAYING

        systemTts.speak(
            page.markdownContent
        )
    }

    private suspend fun startGeminiTts(
        page: PageEntity,
        bookId: String,
        pageNumber: Int
    ) {
        systemTts.stop(manual = true)
        abandonSystemAudioFocus()

        val apiKey =
            settingsManager.geminiKey.first()

        if (apiKey.isBlank()) {
            setError("مفتاح Gemini غير موجود.")
            return
        }

        /*
         * GeminiTtsClient owns GeminiAudioPlayer.
         * This controller only coordinates the operation.
         */
        val client =
            GeminiTtsClient(
                context = appContext,
                client = httpClient,
                apiKey = apiKey
            )

        try {
            _audioState.value =
                AudioState.PROCESSING

            val result =
                client.generateSpeech(
                    text = page.markdownContent,
                    fileName =
                        "audio_${bookId}_${pageNumber}"
                )

            if (result.isFailure) {
                setError(
                    result.exceptionOrNull()?.message
                        ?: "فشل توليد الصوت."
                )
                return
            }

            val file =
                result.getOrNull()

            if (file == null || !file.exists()) {
                setError(
                    "لم يتم إنشاء ملف الصوت."
                )
                return
            }

            repository.insertPages(
                listOf(
                    page.copy(
                        audioUri =
                            file.absolutePath
                    )
                )
            )

            _audioState.value =
                AudioState.PAUSED

        } catch (e: CancellationException) {
            client.stop()
            throw e

        } catch (e: Exception) {
            client.stop()

            setError(
                e.message
                    ?: "حدث خطأ أثناء توليد الصوت."
            )
        }
    }

    private suspend fun togglePlayback() {

        when (currentEngineId) {

            TtsEngineId.SYSTEM -> {

                if (_audioState.value ==
                    AudioState.PLAYING
                ) {
                    userIntendedToPlay = false

                    systemTts.stop(
                        manual = true
                    )

                    _audioState.value =
                        AudioState.PAUSED

                } else {
                    userIntendedToPlay = true

                    requestSystemAudioFocus()

                    systemTts.resume()

                    _audioState.value =
                        AudioState.PLAYING
                }
            }

            TtsEngineId.GEMINI_TTS -> {

                /*
                 * Gemini streaming playback is owned by
                 * GeminiTtsClient/GeminiAudioPlayer.
                 *
                 * A completed stream currently cannot be
                 * resumed from this coordinator.
                 *
                 * Replaying the page starts a new generation.
                 */
                if (_audioState.value ==
                    AudioState.PLAYING
                ) {
                    userIntendedToPlay = false

                    playbackJob?.cancel()

                    _audioState.value =
                        AudioState.PAUSED
                } else {
                    currentBookId?.let { bookId ->
                        if (currentPageNumber >= 0) {
                            playPage(
                                bookId,
                                currentPageNumber
                            )
                        }
                    }
                }
            }

            else ->
                setError(
                    "محرك الصوت غير متاح حاليًا."
                )
        }
    }

    private fun stopCurrentPlayback() {
        userIntendedToPlay = false

        systemTts.stop(
            manual = true
        )

        stopGemini()

        abandonSystemAudioFocus()

        _audioState.value =
            AudioState.IDLE

        _highlightedParagraphIndex.value = -1
    }

    private fun stopGemini() {
        /*
         * The current Gemini generation is cancelled
         * by cancelling playbackJob.
         *
         * GeminiTtsClient itself owns the active call
         * and its GeminiAudioPlayer.
         */
    }

    fun pause() {

        userIntendedToPlay = false

        when (currentEngineId) {

            TtsEngineId.SYSTEM -> {
                systemTts.stop(
                    manual = true
                )
            }

            TtsEngineId.GEMINI_TTS -> {
                /*
                 * Streaming Gemini playback is currently
                 * controlled by GeminiAudioPlayer internally.
                 * Full pause/resume across generation belongs
                 * to the dedicated playback layer.
                 */
            }
        }

        _audioState.value =
            AudioState.PAUSED
    }

    fun resume() {

        userIntendedToPlay = true

        when (currentEngineId) {

            TtsEngineId.SYSTEM -> {
                requestSystemAudioFocus()
                systemTts.resume()
            }

            TtsEngineId.GEMINI_TTS -> {
                currentBookId?.let { bookId ->
                    if (currentPageNumber >= 0) {
                        playPage(
                            bookId,
                            currentPageNumber
                        )
                    }
                }
            }
        }

        _audioState.value =
            AudioState.PLAYING
    }

    fun seekForward() {
        // Not supported by the streaming Gemini layer yet.
    }

    fun seekBackward() {
        // Not supported by the streaming Gemini layer yet.
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun disconnect() {
        userIntendedToPlay = false

        playbackJob?.cancel()
        playbackJob = null

        systemTts.stop(
            manual = true
        )

        abandonSystemAudioFocus()

        _audioState.value =
            AudioState.IDLE

        _highlightedParagraphIndex.value = -1
    }

    fun release() {
        disconnect()
        systemTts.release()
        scope.cancel()
    }

    private fun setError(
        message: String
    ) {
        userIntendedToPlay = false
        _audioState.value =
            AudioState.ERROR
        _errorMessage.value =
            message
    }

    private fun requestSystemAudioFocus() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val attributes =
                AndroidAudioAttributes.Builder()
                    .setUsage(
                        AndroidAudioAttributes
                            .USAGE_ASSISTANCE_ACCESSIBILITY
                    )
                    .setContentType(
                        AndroidAudioAttributes
                            .CONTENT_TYPE_SPEECH
                    )
                    .build()

            val request =
                AudioFocusRequest.Builder(
                    AudioManager
                        .AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(
                        attributes
                    )
                    .setOnAudioFocusChangeListener(
                        systemFocusListener
                    )
                    .build()

            audioManager.requestAudioFocus(
                request
            )

        } else {

            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                systemFocusListener,
                AudioManager.STREAM_ACCESSIBILITY,
                AudioManager
                    .AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonSystemAudioFocus() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val attributes =
                AndroidAudioAttributes.Builder()
                    .setUsage(
                        AndroidAudioAttributes
                            .USAGE_ASSISTANCE_ACCESSIBILITY
                    )
                    .setContentType(
                        AndroidAudioAttributes
                            .CONTENT_TYPE_SPEECH
                    )
                    .build()

            val request =
                AudioFocusRequest.Builder(
                    AudioManager
                        .AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(
                        attributes
                    )
                    .setOnAudioFocusChangeListener(
                        systemFocusListener
                    )
                    .build()

            audioManager.abandonAudioFocusRequest(
                request
            )

        } else {

            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(
                systemFocusListener
            )
        }
    }
} 