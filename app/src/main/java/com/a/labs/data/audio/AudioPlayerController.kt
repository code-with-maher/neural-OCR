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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

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
    private var progressJob: Job? = null
    private val httpClient = OkHttpClient()

    init {
        initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) startProgressUpdate() else stopProgressUpdate()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        _duration.value = controller?.duration ?: 0L
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    scope.launch {
                        val isLoggingEnabled = settingsManager.isLoggingEnabled.first()
                        AppLogger.log(context, isLoggingEnabled, "Media3 Player Error: ${error.message}")
                    }
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun playPage(bookId: String, pageNumber: Int) {
        scope.launch {
            val isLoggingEnabled = settingsManager.isLoggingEnabled.first()
            val page = repository.getPageByNumber(bookId, pageNumber) ?: return@launch
            val engine = settingsManager.ttsEngine.first()
            val apiKeyGemini = settingsManager.geminiKey.first()
            val apiKeyEleven = settingsManager.elevenKey.first()

            AppLogger.log(context, isLoggingEnabled, "محاولة تشغيل الصوت للصفحة $pageNumber باستخدام محرك: $engine")

            val audioFile = if (page.audioUri != null && File(page.audioUri).exists()) {
                AppLogger.log(context, isLoggingEnabled, "تم العثور على ملف صوتي جاهز محلياً.")
                File(page.audioUri)
            } else {
                 AppLogger.log(context, isLoggingEnabled, "لا يوجد ملف صوتي، جاري التوليد...")
                val fileName = "audio_${bookId}_$pageNumber"
                val result = when (engine) {
                    "ELEVENLABS" -> ElevenLabsClient(context, httpClient, apiKeyEleven).generateSpeech(page.markdownContent, fileName)
                    "GEMINI_TTS" -> GeminiTtsClient(context, httpClient, apiKeyGemini).generateSpeech(page.markdownContent, fileName)
                    else -> SystemTtsWrapper(context).generateSpeech(page.markdownContent, fileName)
                }

                val file = result.getOrNull()
                if (file != null) {
                    AppLogger.log(context, isLoggingEnabled, "تم توليد الملف الصوتي بنجاح وحفظه.")
                    val updatedPage = page.copy(audioUri = file.absolutePath)
                    repository.insertPages(listOf(updatedPage))
                    file
                } else {
                    AppLogger.log(context, isLoggingEnabled, "فشل توليد الصوت: ${result.exceptionOrNull()?.localizedMessage}")
                    null
                }
            }

            audioFile?.let {
                val mediaItem = MediaItem.fromUri(it.absolutePath)
                controller?.setMediaItem(mediaItem)
                controller?.prepare()
                controller?.play()
                AppLogger.log(context, isLoggingEnabled, "تم إرسال الملف إلى مشغل Media3 للبدء.")
            }
        }
    }

    fun togglePlay() {
        if (controller?.isPlaying == true) controller?.pause() else controller?.play()
    }

    fun seekForward() = controller?.seekTo((controller?.currentPosition ?: 0L) + 10000)
    fun seekBackward() = controller?.seekTo((controller?.currentPosition ?: 0L) - 10000)

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
        stopProgressUpdate()
     }
}