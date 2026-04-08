package com.a.labs.data.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.a.labs.core.AppLogger
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.remote.api.ElevenLabsClient
import com.a.labs.data.remote.api.GeminiTtsClient
import com.a.labs.data.repository.BookRepository
import com.a.labs.worker.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AudioPlayerController(
    private val context: Context,
    private val repository: BookRepository,
    private val settingsManager: SettingsManager
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController? get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentProgress = MutableStateFlow(0L)
    val currentProgress: StateFlow<Long> = _currentProgress.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    // نطاق منفصل يضمن عدم موت طلب التوليد إذا خرج المستخدم من الشاشة
    private val generationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null
    
    private var loadedBookId: String? = null
    private var loadedPageNum: Int = -1
    private var currentEngine: String = "SYSTEM"

    private val systemTts = SystemTtsWrapper(context).apply {
        onPlaybackStateChanged = { playing ->
            _isPlaying.value = playing
        }
    }

    // رفع المهلة لـ 15 دقيقة
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    init {
        initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (currentEngine != "SYSTEM") {
                        _isPlaying.value = isPlaying
                        if (isPlaying) startProgressUpdate() else stopProgressUpdate()
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (currentEngine != "SYSTEM") {
                        if (state == Player.STATE_READY) _duration.value = controller?.duration ?: 0L
                        else if (state == Player.STATE_ENDED) _isPlaying.value = false
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    scope.launch {
                        val isLoggingEnabled = settingsManager.isLoggingEnabled.first()
                        AppLogger.log(context, isLoggingEnabled, "Media3 Error:  ${error.message}")
                    }
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun playPage(bookId: String, pageNumber: Int) {
        generationScope.launch {
            val engine = settingsManager.ttsEngine.first()
            currentEngine = engine

            if (loadedBookId == bookId && loadedPageNum == pageNumber) {
                togglePlay()
                return@launch
            }

            loadedBookId = bookId
            loadedPageNum = pageNumber
            val isLoggingEnabled = settingsManager.isLoggingEnabled.first()
            val page = repository.getPageByNumber(bookId, pageNumber) ?: return@launch

            if (engine == "SYSTEM") {
                scope.launch(Dispatchers.Main) { controller?.pause() }
                systemTts.speak(page.markdownContent)
                AppLogger.log(context, isLoggingEnabled, "بدء القراءة الفورية عبر System TTS")
                return@launch
            }

            systemTts.stop()

            val audioFile = if (page.audioUri != null && File(page.audioUri).exists()) {
                File(page.audioUri)
            } else {
                AppLogger.log(context, isLoggingEnabled, "جاري طلب الصوت من السيرفر...")
                val fileName = "audio_${bookId}_$pageNumber"
                val apiKeyGemini = settingsManager.geminiKey.first()
                val apiKeyEleven = settingsManager.elevenKey.first()
                
                val result = if (engine == "ELEVENLABS") {
                    ElevenLabsClient(context, httpClient, apiKeyEleven).generateSpeech(page.markdownContent, fileName)
                } else {
                    GeminiTtsClient(context, httpClient, apiKeyGemini).generateSpeech(page.markdownContent, fileName)
                }

                val file = result.getOrNull()
                if (file != null) {
                    repository.insertPages(listOf(page.copy(audioUri = file.absolutePath)))
                    file
                } else {
                    AppLogger.log(context, isLoggingEnabled, "فشل الصوت: ${result.exceptionOrNull()?.message}")
                    null
                }
            }

            audioFile?.let {
                scope.launch(Dispatchers.Main) {
                    val mediaItem = MediaItem.fromUri(it.absolutePath)
                    controller?.setMediaItem(mediaItem)
                    controller?.prepare()
                    controller?.play()
                }
            }
        }
    }

    fun togglePlay() {
        if (currentEngine == "SYSTEM") {
            if (_isPlaying.value) systemTts.stop()
            else {
                generationScope.launch {
                    val page = repository.getPageByNumber(loadedBookId ?: "", loadedPageNum)
                    page?.let { systemTts.speak(it.markdownContent) }
                }
            }
        } else {
            scope.launch(Dispatchers.Main) {
                if (controller?.isPlaying == true) {
                    controller?.pause()
                } else {
                    if (controller?.playbackState == Player.STATE_ENDED) controller?.seekTo(0)
                    controller?.play()
                }
            }
        }
    }

    fun seekForward() = if (currentEngine != "SYSTEM") controller?.seekTo((controller?.currentPosition ?: 0L) + 10000) else Unit
    fun seekBackward() = if (currentEngine != "SYSTEM") controller?.seekTo((controller?.currentPosition ?: 0L) - 10000) else Unit

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                _currentProgress.value = controller?.currentPosition ?: 0L
                delay(500)
            }
        }
    }

    private fun stopProgressUpdate() { progressJob?.cancel() }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        systemTts.release()
         stopProgressUpdate()
     }
}