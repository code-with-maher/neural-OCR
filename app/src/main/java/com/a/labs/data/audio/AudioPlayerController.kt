package com.a.labs.data.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.a.labs.R
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

enum class AudioState { IDLE, PROCESSING, PLAYING, PAUSED, ERROR }

class AudioPlayerController(
    private val context: Context,
    private val repository: BookRepository,
    private val settingsManager: SettingsManager
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController? get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _audioState = MutableStateFlow(AudioState.IDLE)
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _highlightedParagraphIndex = MutableStateFlow(-1)
    val highlightedParagraphIndex: StateFlow<Int> = _highlightedParagraphIndex.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val generationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null

    private var loadedBookId: String? = null
    private var loadedPageNum: Int = -1
    private var currentEngine: String = "SYSTEM"
    private var currentParagraphsCount: Int = 1

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifyId = 2002

    private val systemTts = SystemTtsWrapper(context).apply {
        onPlaybackStateChanged = { playing ->
            _audioState.value = if (playing) AudioState.PLAYING else AudioState.PAUSED
        }
        onHighlightProgress = { charIndex ->
            if (charIndex == -1) {
                _highlightedParagraphIndex.value = -1
            } else {
                scope.launch {
                    val page = repository.getPageByNumber(loadedBookId ?: "", loadedPageNum)
                    page?.let { p ->
                        val textUpToChar = p.markdownContent.substring(0, charIndex.coerceAtMost(p.markdownContent.length))
                        val paragraphsBefore = textUpToChar.split("\n\n").size - 1
                        _highlightedParagraphIndex.value = paragraphsBefore.coerceAtLeast(0)
                    }
                }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    init {
        createNotificationChannel()
        initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context,  ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (currentEngine != "SYSTEM") {
                        _audioState.value = if (isPlaying) AudioState.PLAYING else AudioState.PAUSED
                        if (isPlaying) startHighlightUpdate() else stopHighlightUpdate()
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (currentEngine != "SYSTEM") {
                        if (state == Player.STATE_ENDED) {
                            _audioState.value = AudioState.PAUSED
                            _highlightedParagraphIndex.value = -1
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    _audioState.value = AudioState.ERROR
                    _errorMessage.value = "حدث خطأ في المشغل الصوتي: ${error.message}"
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun clearError() { _errorMessage.value = null }

    fun playPage(bookId: String, pageNumber: Int) {
        generationScope.launch {
            val engine = settingsManager.ttsEngine.first()
            currentEngine = engine

            if (loadedBookId == bookId && loadedPageNum == pageNumber && _audioState.value != AudioState.ERROR) {
                togglePlay()
                return@launch
            }

            loadedBookId = bookId
            loadedPageNum = pageNumber
            val page = repository.getPageByNumber(bookId, pageNumber) ?: return@launch
            
            val paragraphs = page.markdownContent.split("\n\n").filter { it.isNotBlank() }
            currentParagraphsCount = paragraphs.size.coerceAtLeast(1)

            if (engine == "SYSTEM") {
                scope.launch(Dispatchers.Main) { controller?.pause() }
                systemTts.speak(page.markdownContent)
                return@launch
            }

            systemTts.stop()

            val audioFile = if (page.audioUri != null && File(page.audioUri).exists()) {
                File(page.audioUri)
            } else {
                _audioState.value = AudioState.PROCESSING
                showGenerationNotification()
                
                val fileName = "audio_${bookId}_$pageNumber"
                val apiKeyGemini = settingsManager.geminiKey.first()
                val apiKeyEleven = settingsManager.elevenKey.first()
                val elevenVoiceId = settingsManager.elevenVoiceId.first()

                val result = if (engine == "ELEVENLABS") {
                    if (apiKeyEleven.isBlank()) {
                        reportError("مفتاح ElevenLabs غير موجود، يرجى إضافته من الإعدادات.")
                        return@launch
                    }
                    ElevenLabsClient(context, httpClient, apiKeyEleven).generateSpeech(page.markdownContent, fileName, elevenVoiceId)
                } else {
                    if (apiKeyGemini.isBlank()) {
                        reportError("مفتاح Gemini غير موجود، يرجى إضافته من الإعدادات.")
                        return@launch
                    }
                    GeminiTtsClient(context, httpClient, apiKeyGemini).generateSpeech(page.markdownContent, fileName)
                }

                val file = result.getOrNull()
                notificationManager.cancel(notifyId)
                
                if (file != null) {
                    repository.insertPages(listOf(page.copy(audioUri = file.absolutePath)))
                    file
                } else {
                    reportError("فشل توليد الصوت: ${result.exceptionOrNull()?.message}")
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

    private fun togglePlay() {
        if (currentEngine == "SYSTEM") {
            if (_audioState.value == AudioState.PLAYING) systemTts.stop()
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

    private fun reportError(msg: String) {
        notificationManager.cancel(notifyId)
        _audioState.value = AudioState.ERROR
        _errorMessage.value = msg
    }

    private fun startHighlightUpdate() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val duration = controller?.duration ?: 1L
                val currentPos = controller?.currentPosition ?: 0L
                if (duration > 0 && currentParagraphsCount > 0) {
                    val progressRatio = currentPos.toFloat() / duration.toFloat()
                    val index = (progressRatio * currentParagraphsCount).toInt().coerceIn(0, currentParagraphsCount - 1)
                    _highlightedParagraphIndex.value = index
                }
                delay(200)
            }
        }
    }

    private fun stopHighlightUpdate() { progressJob?.cancel() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("audio_gen_channel", "توليد الصوت", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showGenerationNotification() {
        val appName = context.getString(R.string.app_name)
        val notification = NotificationCompat.Builder(context, "audio_gen_channel")
            .setContentTitle(appName)
            .setContentText("جاري معالجة وتوليد الصوت بذكاء...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        notificationManager.notify(notifyId, notification)
    }

    fun seekForward() = if (currentEngine != "SYSTEM") controller?.seekTo((controller?.currentPosition ?: 0L) + 10000) else Unit
    fun seekBackward() = if (currentEngine != "SYSTEM") controller?.seekTo((controller?.currentPosition ?: 0L) - 10000) else Unit

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        systemTts.release()
        stopHighlightUpdate()
     }
}