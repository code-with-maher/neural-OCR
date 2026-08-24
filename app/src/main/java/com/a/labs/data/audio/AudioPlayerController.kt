package com.a.labs.data.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

class AudioPlayerController(
    context: Context,
    private val repository: BookRepository,
    private val settingsManager: SettingsManager,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var playbackJob: Job? = null
    private var activeGeminiClient: GeminiTtsClient? = null

    private var currentBookId: String? = null
    private var currentPageNumber = -1
    private var currentEngineId = TtsEngineId.SYSTEM
    private var userIntendedToPlay = false
    private var isPlayingCachedFile = false

    private val _audioState = MutableStateFlow(AudioState.IDLE)
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _highlightedParagraphIndex = MutableStateFlow(-1)
    val highlightedParagraphIndex: StateFlow<Int> = _highlightedParagraphIndex.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val localExoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(appContext).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            _audioState.value = AudioState.PAUSED
                            userIntendedToPlay = false
                        }
                        Player.STATE_BUFFERING -> Unit
                        Player.STATE_READY -> {
                            if (playWhenReady) _audioState.value = AudioState.PLAYING
                        }
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlayingCachedFile) {
                        _audioState.value = if (isPlaying) AudioState.PLAYING else AudioState.PAUSED
                    }
                }
            })
        }
    }

    private val audioFocusManager = AudioFocusManager(
        context = appContext,
        onFocusLost = {
            if (_audioState.value == AudioState.PLAYING) {
                if (isPlayingCachedFile) {
                    localExoPlayer.pause()
                } else {
                    when (currentEngineId) {
                        TtsEngineId.SYSTEM -> systemTts.stop(manual = false)
                        TtsEngineId.GEMINI_TTS -> activeGeminiClient?.pause()
                    }
                }
                _audioState.value = AudioState.PAUSED
            }
        },
        onFocusGained = {
            if (userIntendedToPlay) {
                if (isPlayingCachedFile) {
                    localExoPlayer.play()
                } else {
                    when (currentEngineId) {
                        TtsEngineId.SYSTEM -> systemTts.resume()
                        TtsEngineId.GEMINI_TTS -> activeGeminiClient?.resume()
                    }
                }
                _audioState.value = AudioState.PLAYING
            }
        }
    )

    private val systemTts = SystemTtsWrapper(appContext).apply {
        onPlaybackStateChanged = { playing ->
            if (currentEngineId == TtsEngineId.SYSTEM && !isPlayingCachedFile) {
                _audioState.value = if (playing) AudioState.PLAYING else AudioState.PAUSED
            }
        }
        onHighlightProgress = { index ->
            _highlightedParagraphIndex.value = index
        }
    }

    fun connect() = Unit

    fun playPage(bookId: String, pageNumber: Int) {
        playbackJob?.cancel()

        playbackJob = scope.launch {
            val engine = settingsManager.ttsEngine.first()
            currentEngineId = engine

            val samePage = currentBookId == bookId && currentPageNumber == pageNumber

            if (samePage && _audioState.value != AudioState.ERROR) {
                togglePlayback()
                return@launch
            }

            stopCurrentPlayback()

            currentBookId = bookId
            currentPageNumber = pageNumber
            userIntendedToPlay = true

            val page = repository.getPageByNumber(bookId, pageNumber) ?: run {
                setError("تعذر العثور على الصفحة.")
                return@launch
            }

            _highlightedParagraphIndex.value = -1

            val cachedAudioPath = page.audioUri
            if (!cachedAudioPath.isNullOrBlank() && File(cachedAudioPath).exists() && engine != TtsEngineId.SYSTEM) {
                playLocalCachedFile(File(cachedAudioPath))
                return@launch
            }

            when (engine) {
                TtsEngineId.SYSTEM -> startSystemTts(page)
                TtsEngineId.GEMINI_TTS -> startGeminiTts(page, bookId, pageNumber)
                TtsEngineId.ELEVENLABS -> setError("محرك ElevenLabs غير متاح مؤقتًا.")
                else -> setError("محرك الصوت غير معروف.")
            }
        }
    }

    private fun playLocalCachedFile(file: File) {
        isPlayingCachedFile = true
        audioFocusManager.requestFocus()
        localExoPlayer.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        localExoPlayer.prepare()
        localExoPlayer.play()
        _audioState.value = AudioState.PLAYING
    }

    private fun startSystemTts(page: PageEntity) {
        isPlayingCachedFile = false
        stopGemini()
        audioFocusManager.requestFocus()
        _audioState.value = AudioState.PLAYING
        systemTts.speak(page.markdownContent)
    }

    private suspend fun startGeminiTts(
        page: PageEntity,
        bookId: String,
        pageNumber: Int
    ) {
        isPlayingCachedFile = false
        systemTts.stop(manual = true)
        audioFocusManager.requestFocus()

        val apiKey = settingsManager.geminiKey.first()
        if (apiKey.isBlank()) {
            setError("مفتاح Gemini غير موجود.")
            return
        }

        val client = GeminiTtsClient(
            context = appContext,
            client = httpClient,
            apiKey = apiKey
        )
        activeGeminiClient = client

        try {
            _audioState.value = AudioState.PROCESSING

            val result = client.generateSpeech(
                text = page.markdownContent,
                fileName = "audio_${bookId}_${pageNumber}"
            )

            if (result.isFailure) {
                setError(result.exceptionOrNull()?.message ?: "فشل توليد الصوت.")
                return
            }

            val file = result.getOrNull()
            if (file == null || !file.exists()) {
                setError("لم يتم إنشاء ملف الصوت.")
                return
            }

            repository.insertPages(
                listOf(page.copy(audioUri = file.absolutePath))
            )

            _audioState.value = AudioState.PAUSED
        } catch (e: CancellationException) {
            client.stop()
            throw e
        } catch (e: Exception) {
            client.stop()
            setError(e.message ?: "حدث خطأ أثناء توليد الصوت.")
        } finally {
            activeGeminiClient = null
        }
    }

    private suspend fun togglePlayback() {
        if (isPlayingCachedFile) {
            if (localExoPlayer.isPlaying) {
                userIntendedToPlay = false
                localExoPlayer.pause()
                _audioState.value = AudioState.PAUSED
            } else {
                userIntendedToPlay = true
                audioFocusManager.requestFocus()
                localExoPlayer.play()
                _audioState.value = AudioState.PLAYING
            }
            return
        }

        when (currentEngineId) {
            TtsEngineId.SYSTEM -> {
                if (_audioState.value == AudioState.PLAYING) {
                    userIntendedToPlay = false
                    systemTts.stop(manual = true)
                    _audioState.value = AudioState.PAUSED
                } else {
                    userIntendedToPlay = true
                    audioFocusManager.requestFocus()
                    systemTts.resume()
                    _audioState.value = AudioState.PLAYING
                }
            }

            TtsEngineId.GEMINI_TTS -> {
                if (_audioState.value == AudioState.PLAYING) {
                    userIntendedToPlay = false
                    playbackJob?.cancel()
                    activeGeminiClient?.pause()
                    _audioState.value = AudioState.PAUSED
                } else {
                    currentBookId?.let { bookId ->
                        if (currentPageNumber >= 0) {
                            playPage(bookId, currentPageNumber)
                        }
                    }
                }
            }

            else -> setError("محرك الصوت غير متاح حاليًا.")
        }
    }

    private fun stopCurrentPlayback() {
        userIntendedToPlay = false
        if (isPlayingCachedFile) {
            localExoPlayer.stop()
            localExoPlayer.clearMediaItems()
            isPlayingCachedFile = false
        }
        systemTts.stop(manual = true)
        stopGemini()
        audioFocusManager.abandonFocus()
        _audioState.value = AudioState.IDLE
        _highlightedParagraphIndex.value = -1
    }

    private fun stopGemini() {
        activeGeminiClient?.stop()
        activeGeminiClient = null
    }

    fun pause() {
        userIntendedToPlay = false
        if (isPlayingCachedFile) {
            localExoPlayer.pause()
        } else {
            when (currentEngineId) {
                TtsEngineId.SYSTEM -> systemTts.stop(manual = true)
                TtsEngineId.GEMINI_TTS -> activeGeminiClient?.pause()
            }
        }
        _audioState.value = AudioState.PAUSED
    }

    fun resume() {
        userIntendedToPlay = true
        audioFocusManager.requestFocus()
        if (isPlayingCachedFile) {
            localExoPlayer.play()
            _audioState.value = AudioState.PLAYING
        } else {
            when (currentEngineId) {
                TtsEngineId.SYSTEM -> {
                    systemTts.resume()
                    _audioState.value = AudioState.PLAYING
                }

                TtsEngineId.GEMINI_TTS -> {
                    currentBookId?.let { bookId ->
                        if (currentPageNumber >= 0) {
                            playPage(bookId, currentPageNumber)
                        }
                    }
                }
            }
        }
    }

    fun seekForward() {
        if (isPlayingCachedFile) {
            val current = localExoPlayer.currentPosition
            localExoPlayer.seekTo((current + 10_000).coerceAtMost(localExoPlayer.duration))
        }
    }

    fun seekBackward() {
        if (isPlayingCachedFile) {
            val current = localExoPlayer.currentPosition
            localExoPlayer.seekTo((current - 10_000).coerceAtLeast(0))
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun disconnect() {
        userIntendedToPlay = false
        playbackJob?.cancel()
        playbackJob = null
        if (isPlayingCachedFile) {
            localExoPlayer.stop()
            localExoPlayer.clearMediaItems()
            isPlayingCachedFile = false
        }
        systemTts.stop(manual = true)
        stopGemini()
        audioFocusManager.abandonFocus()
        _audioState.value = AudioState.IDLE
        _highlightedParagraphIndex.value = -1
    }

    fun release() {
        disconnect()
        systemTts.release()
        localExoPlayer.release()
        scope.cancel()
    }

    private fun setError(message: String) {
        userIntendedToPlay = false
        _audioState.value = AudioState.ERROR
        _errorMessage.value = message
    }
}