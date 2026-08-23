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

    private var generationJob: Job? = null

    private var currentBookId: String? = null
    private var currentPageNumber: Int = -1

    private var userIntendedToPlay = false

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

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE)
            as AudioManager

    private val systemTts =
        SystemTtsWrapper(appContext).apply {

            onPlaybackStateChanged = { playing ->
                if (currentEngine() == TtsEngineId.SYSTEM) {
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

    private var geminiAudioPlayer: GeminiAudioPlayer? = null

    private val systemFocusListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->

            when (focusChange) {

                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                    if (currentEngine() == TtsEngineId.SYSTEM &&
                        _audioState.value == AudioState.PLAYING
                    ) {
                        systemTts.stop(manual = false)
                    }
                }

                AudioManager.AUDIOFOCUS_GAIN -> {

                    if (
                        currentEngine() == TtsEngineId.SYSTEM &&
                        userIntendedToPlay
                    ) {
                        systemTts.resume()
                    }
                }
            }
        }

    init {
        connect()
    }

    /**
     * Kept for compatibility with ReaderViewModel.
     *
     * Gemini no longer uses Media3, so there is no controller
     * connection to establish here.
     */
    fun connect() = Unit

    fun playPage(
        bookId: String,
        pageNumber: Int
    ) {
        generationJob?.cancel()

        generationJob = scope.launch {
            val engine = settingsManager.ttsEngine.first()

            val samePage =
                currentBookId == bookId &&
                    currentPageNumber == pageNumber

            if (samePage &&
                _audioState.value != AudioState.ERROR
            ) {
                togglePlayback(engine)
                return@launch
            }

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

                TtsEngineId.ELEVENLABS -> {
                    setError(
                        "محرك ElevenLabs غير متاح مؤقتًا."
                    )
                }

                else -> {
                    setError("محرك الصوت غير معروف.")
                }
            }
        }
    }

    private suspend fun startSystemTts(
        page: PageEntity
    ) {
        stopGemini()

        systemTts.stop(manual = true)
        requestSystemAudioFocus()

        _audioState.value = AudioState.PLAYING

        systemTts.speak(page.markdownContent)
    }

    private suspend fun startGeminiTts(
        page: PageEntity,
        bookId: String,
        pageNumber: Int
    ) {
        systemTts.stop(manual = true)
        abandonSystemAudioFocus()

        /*
         * If the page already has a generated file, use it
         * directly. Streaming is only required when generation
         * has not happened yet.
         */
        page.audioUri
            ?.let { FilePath ->
                val existingFile =
                    java.io.File(FilePath)

                if (existingFile.exists()) {
                    /*
                     * The current GeminiAudioPlayer is a streaming
                     * AudioTrack player. Existing files can be
                     * handled by a future persistent playback layer.
                     *
                     * For now we regenerate only when needed.
                     */
                }
            }

        val apiKey =
            settingsManager.geminiKey.first()

        if (apiKey.isBlank()) {
            setError("مفتاح Gemini غير موجود.")
            return
        }

        stopGemini()

        val player =
            GeminiAudioPlayer(appContext)

        geminiAudioPlayer = player

        val outputFile =
            java.io.File(
                appContext.cacheDir,
                "audio_${bookId}_${pageNumber}.wav"
            )

        try {
            _audioState.value = AudioState.PROCESSING

            player.start(
                outputFile = outputFile,
                sampleRate =
                    GeminiAudioPlayer.DEFAULT_SAMPLE_RATE,
                channels =
                    GeminiAudioPlayer.DEFAULT_CHANNELS,
                bitsPerSample =
                    GeminiAudioPlayer.DEFAULT_BITS_PER_SAMPLE
            )

            _audioState.value = AudioState.PLAYING

            val result =
                GeminiTtsClient(
                    context = appContext,
                    client = okhttp3.OkHttpClient(),
                    apiKey = apiKey
                ).generateSpeech(
                    text = page.markdownContent,
                    fileName =
                        "audio_${bookId}_${pageNumber}",
                    onAudioChunk = { pcmChunk ->
                        player.writeChunk(pcmChunk)
                    }
                )

            if (result.isFailure) {
                player.stop()

                setError(
                    result.exceptionOrNull()?.message
                        ?: "فشل توليد الصوت."
                )

                return
            }

            /*
             * Finish closes the WAV stream, fixes its header,
             * and releases AudioTrack after playback completes.
             */
            val finalFile =
                player.finish()

            repository.insertPages(
                listOf(
                    page.copy(
                        audioUri =
                            finalFile.absolutePath
                    )
                )
            )

            _audioState.value = AudioState.PAUSED

        } catch (e: kotlinx.coroutines.CancellationException) {
            player.stop()
            throw e

        } catch (e: Exception) {
            player.stop()

            setError(
                e.message ?: "حدث خطأ أثناء تشغيل الصوت."
            )

        } finally {
            if (geminiAudioPlayer === player) {
                geminiAudioPlayer = null
            }
        }
    }

    private suspend fun togglePlayback(
        engine: String
    ) {
        when (engine) {

            TtsEngineId.SYSTEM -> {

                if (_audioState.value ==
                    AudioState.PLAYING
                ) {
                    userIntendedToPlay = false
                    systemTts.stop(manual = true)
                    _audioState.value = AudioState.PAUSED
                } else {
                    userIntendedToPlay = true
                    requestSystemAudioFocus()
                    systemTts.resume()
                    _audioState.value = AudioState.PLAYING
                }
            }

            TtsEngineId.GEMINI_TTS -> {

                val player =
                    geminiAudioPlayer

                if (player == null) {
                    currentBookId?.let { bookId ->
                        if (currentPageNumber >= 0) {
                            playPage(
                                bookId,
                                currentPageNumber
                            )
                        }
                    }
                    return
                }

                if (_audioState.value ==
                    AudioState.PLAYING
                ) {
                    userIntendedToPlay = false
                    player.pause()
                    _audioState.value = AudioState.PAUSED
                } else {
                    userIntendedToPlay = true
                    player.resume()
                    _audioState.value = AudioState.PLAYING
                }
            }

            else -> {
                setError(
                    "محرك الصوت غير متاح حاليًا."
                )
            }
        }
    }

    private fun stopGemini() {
        generationJob?.let { job ->
            if (job != kotlinx.coroutines.currentCoroutineContext()
                    .let { null }
            ) {
                // Generation cancellation is handled by the
                // coroutine that owns GeminiTtsClient.
            }
        }

        geminiAudioPlayer?.stop()
        geminiAudioPlayer = null
    }

    fun pause() {
        userIntendedToPlay = false

        when (currentEngine()) {

            TtsEngineId.SYSTEM ->
                systemTts.stop(manual = true)

            TtsEngineId.GEMINI_TTS ->
                geminiAudioPlayer?.pause()
        }

        _audioState.value = AudioState.PAUSED
    }

    fun resume() {
        userIntendedToPlay = true

        when (currentEngine()) {

            TtsEngineId.SYSTEM -> {
                requestSystemAudioFocus()
                systemTts.resume()
            }

            TtsEngineId.GEMINI_TTS ->
                geminiAudioPlayer?.resume()
        }

        _audioState.value = AudioState.PLAYING
    }

    fun seekForward() {
        /*
         * Seeking streamed Gemini PCM is intentionally not handled
         * here. AudioPlayerController remains a coordinator.
         */
    }

    fun seekBackward() {
        /*
         * Seeking will be added to the persistent audio playback
         * layer later.
         */
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun disconnect() {
        userIntendedToPlay = false

        generationJob?.cancel()
        generationJob = null

        geminiAudioPlayer?.stop()
        geminiAudioPlayer = null

        systemTts.stop(manual = true)
        abandonSystemAudioFocus()

        _audioState.value = AudioState.IDLE
        _highlightedParagraphIndex.value = -1
    }

    fun release() {
        disconnect()
        systemTts.release()
        scope.cancel()
    }

    private suspend fun currentEngine(): String =
        settingsManager.ttsEngine.first()

    private fun setError(message: String) {
        _audioState.value = AudioState.ERROR
        _errorMessage.value = message
        userIntendedToPlay = false
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
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(
                        systemFocusListener
                    )
                    .build()

            audioManager.requestAudioFocus(request)

        } else {

            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                systemFocusListener,
                AudioManager.STREAM_ACCESSIBILITY,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
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
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(
                        systemFocusListener
                    )
                    .build()

            audioManager.abandonAudioFocusRequest(request)

        } else {

            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(
                systemFocusListener
            )
        }
    }
}