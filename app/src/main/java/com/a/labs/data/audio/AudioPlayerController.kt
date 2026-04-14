package com.a.labs.data.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.a.labs.R
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.remote.api.ElevenLabsClient
import com.a.labs.data.remote.api.GeminiTtsClient
import com.a.labs.data.repository.BookRepository
import com.a.labs.worker.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private var wasPlayingBeforeInterrupt = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_audioState.value == AudioState.PLAYING) {
                    wasPlayingBeforeInterrupt = true
                    pauseInternal()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlayingBeforeInterrupt) {
                    wasPlayingBeforeInterrupt = false
                    resumeInternal()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeInterrupt = false
                pauseInternal()
            }
        }
    }

    private val systemTts = SystemTtsWrapper(context).apply {
        onPlaybackStateChanged = { playing ->
            _audioState.value = if (playing) AudioState.PLAYING else AudioState.PAUSED
        }
        onHighlightProgress = { index -> _highlightedParagraphIndex.value = index }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.MINUTES)
        .build()

    init {
        createNotificationChannel()
         initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (currentEngine != "SYSTEM") {
                        _audioState.value = if (isPlaying) AudioState.PLAYING else AudioState.PAUSED
                        if (isPlaying) startHighlightUpdate() else stopHighlightUpdate()
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
                if (_audioState.value == AudioState.PLAYING) pauseInternal() else resumeInternal()
                return@launch
            }

            loadedBookId = bookId
            loadedPageNum = pageNumber
            val page = repository.getPageByNumber(bookId, pageNumber) ?: return@launch
            currentParagraphsCount = page.markdownContent.split("\n\n").filter { it.isNotBlank() }.size

            requestAudioFocus()

            if (engine == "SYSTEM") {
                scope.launch(Dispatchers.Main) { controller?.pause() }
                systemTts.speak(page.markdownContent)
            } else {
                systemTts.stop()
                val audioFile = getAudioFile(page, bookId, pageNumber, engine)
                audioFile?.let {
                    scope.launch(Dispatchers.Main) {
                        controller?.setMediaItem(MediaItem.fromUri(it.absolutePath))
                        controller?.prepare()
                        controller?.play()
                    }
                }
            }
        }
    }

    private suspend fun getAudioFile(page: com.a.labs.data.local.room.entity.PageEntity, bookId: String, pageNumber: Int, engine: String): File? {
        if (page.audioUri != null && File(page.audioUri).exists()) return File(page.audioUri)
        
        _audioState.value = AudioState.PROCESSING
        showGenerationNotification()
        val apiKeyGemini = settingsManager.geminiKey.first()
        val apiKeyEleven = settingsManager.elevenKey.first()
        val voiceId = settingsManager.elevenVoiceId.first()

        val result = if (engine == "ELEVENLABS") {
            ElevenLabsClient(context, httpClient, apiKeyEleven).generateSpeech(page.markdownContent, "audio_${bookId}_$pageNumber", voiceId)
        } else {
            GeminiTtsClient(context, httpClient, apiKeyGemini).generateSpeech(page.markdownContent, "audio_${bookId}_$pageNumber")
        }

        notificationManager.cancel(2002)
        val file = result.getOrNull()
        if (file != null) repository.insertPages(listOf(page.copy(audioUri = file.absolutePath)))
        else _audioState.value = AudioState.ERROR
        return file
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AndroidAudioAttributes.Builder().setUsage(AndroidAudioAttributes.USAGE_MEDIA).setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH).build()
            audioManager.requestAudioFocus(AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attr).setOnAudioFocusChangeListener(focusChangeListener).build())
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun pauseInternal() {
        if (currentEngine == "SYSTEM") systemTts.stop(manual = false) else  controller?.pause()
    }

    private fun resumeInternal() {
        requestAudioFocus()
        if (currentEngine == "SYSTEM") systemTts.resume() else controller?.play()
    }

    private fun startHighlightUpdate() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val duration = controller?.duration ?: 1L
                val pos = controller?.currentPosition ?: 0L
                _highlightedParagraphIndex.value = ((pos.toFloat() / duration.toFloat()) * currentParagraphsCount).toInt().coerceIn(0, currentParagraphsCount - 1)
                delay(300)
            }
        }
    }

    private fun stopHighlightUpdate() = progressJob?.cancel()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(NotificationChannel("audio_gen_channel", "توليد الصوت", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun showGenerationNotification() {
        val n = NotificationCompat.Builder(context, "audio_gen_channel").setContentTitle(context.getString(R.string.app_name)).setContentText("جاري توليد الصوت...").setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build()
        notificationManager.notify(2002, n)
    }

    fun clearError() { _errorMessage.value = null }
    fun seekForward() = controller?.seekTo((controller?.currentPosition ?: 0L) + 10000)
    fun seekBackward() = controller?.seekTo((controller?.currentPosition ?: 0L) - 10000)
    fun release() { controllerFuture?.let { MediaController.releaseFuture(it) }; systemTts.release(); stopHighlightUpdate()  }
}