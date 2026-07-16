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
    private var userIntendedToPlay = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (currentEngine == "SYSTEM") {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (_audioState.value == AudioState.PLAYING) {
                        systemTts.stop(manual = false)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (userIntendedToPlay) {
                        systemTts.resume()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    userIntendedToPlay = false
                    systemTts.stop(manual = true)
                }
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
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    init {
        createNotificationChannel()
        initializeController()
    }

    private fun initializeController() {
        if (controllerFuture != null && !controllerFuture!!.isCancelled) {
            return
        }
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val currentController = controller

                // إعادة مزامنة الحالة إذا كانت خدمة PlaybackService لا تزال تعمل من نسخة سابقة
                // (يحدث هذا عند حذف التطبيق من التطبيقات الأخيرة أثناء التشغيل ثم إعادة فتحه،
                // حيث تُنشأ نسخة جديدة من AudioPlayerController بحالة ابتدائية فارغة بينما
                // الجلسة الفعلية القديمة ما زالت تشتغل).
                if (currentController != null && loadedBookId == null) {
                    val restoredId = currentController.currentMediaItem?.mediaId
                    if (restoredId != null && restoredId.contains("::")) {
                        val parts = restoredId.split("::")
                        val restoredPage = parts.getOrNull(1)?.toIntOrNull()
                        if (parts.isNotEmpty() && restoredPage != null) {
                            loadedBookId = parts[0]
                            loadedPageNum = restoredPage
                            // قيمة داخلية مؤقتة فقط (وليست من الإعدادات المحفوظة)، الهدف منها
                            // فقط تفعيل منطق التعامل مع MediaController بدل منطق SYSTEM TTS،
                            // إلى أن يتم استدعاء playPage() فتُحدَّث القيمة الحقيقية من DataStore.
                            currentEngine = "RESTORED"
                            userIntendedToPlay = currentController.isPlaying

                            _audioState.value = when {
                                currentController.isPlaying -> AudioState.PLAYING
                                currentController.playbackState == Player.STATE_BUFFERING -> AudioState.PROCESSING
                                else -> AudioState.PAUSED
                            }
                            if (currentController.isPlaying) startHighlightUpdate()

                            // إعادة حساب عدد الفقرات لأجل التظليل (highlight) الصحيح أثناء التشغيل المستعاد
                            generationScope.launch {
                                repository.getPageByNumber(loadedBookId!!, loadedPageNum)?.let { page ->
                                    currentParagraphsCount = page.markdownContent.split("\n\n").filter { it.isNotBlank() }.size
                                }
                            }
                        }
                    }
                }

                currentController?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (currentEngine != "SYSTEM") {
                            _audioState.value = if (isPlaying) AudioState.PLAYING else AudioState.PAUSED
                            if (isPlaying) startHighlightUpdate() else stopHighlightUpdate()
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (currentEngine != "SYSTEM") {
                            when (state) {
                                Player.STATE_ENDED -> {
                                    _audioState.value = AudioState.PAUSED
                                    userIntendedToPlay = false
                                    _highlightedParagraphIndex.value = -1
                                }
                                Player.STATE_READY -> {
                                    _audioState.value = if (currentController.isPlaying) AudioState.PLAYING else AudioState.PAUSED
                                }
                                Player.STATE_BUFFERING -> {
                                    _audioState.value = AudioState.PROCESSING
                                }
                                Player.STATE_IDLE -> {
                                    _audioState.value = AudioState.IDLE
                                }
                            }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        _audioState.value = AudioState.ERROR
                        userIntendedToPlay = false
                        _errorMessage.value = error.message
                    }
                })
            } catch (e: Exception) {
                _audioState.value = AudioState.ERROR
                _errorMessage.value = e.message
            }
        }, MoreExecutors.directExecutor())
    }

    fun playPage(bookId: String, pageNumber: Int) {
        initializeController()
        generationScope.launch {
            val engine = settingsManager.ttsEngine.first()
            currentEngine = engine

            if (loadedBookId == bookId && loadedPageNum == pageNumber && _audioState.value != AudioState.ERROR) {
                if (_audioState.value == AudioState.PLAYING) {
                    userIntendedToPlay = false
                    pauseInternal()
                } else {
                    userIntendedToPlay = true
                    resumeInternal()
                }
                return@launch
            }

            loadedBookId = bookId
            loadedPageNum = pageNumber
            userIntendedToPlay = true

            val page = repository.getPageByNumber(bookId, pageNumber) ?: return@launch
            currentParagraphsCount = page.markdownContent.split("\n\n").filter { it.isNotBlank() }.size

            if (engine == "SYSTEM") {
                scope.launch { awaitController()?.pause() }
                requestSystemAudioFocus()
                systemTts.speak(page.markdownContent)
            } else {
                systemTts.stop(manual = true)
                abandonSystemAudioFocus()
                val audioFile = getAudioFile(page, bookId, pageNumber, engine)
                audioFile?.let {
                    scope.launch {
                        val activeController = awaitController() ?: return@launch
                        val mediaItem = MediaItem.Builder()
                            .setMediaId("$bookId::$pageNumber")
                            .setUri(it.absolutePath)
                            .build()
                        activeController.setMediaItem(mediaItem)
                        activeController.prepare()
                        activeController.play()
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
        else {
            _audioState.value = AudioState.ERROR
            _errorMessage.value = result.exceptionOrNull()?.message
        }
        return file
    }

    private fun requestSystemAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AndroidAudioAttributes.Builder().setUsage(AndroidAudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH).build()
            audioManager.requestAudioFocus(AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attr).setOnAudioFocusChangeListener(focusChangeListener).build())
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_ACCESSIBILITY, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonSystemAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AndroidAudioAttributes.Builder().setUsage(AndroidAudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH).build()
            audioManager.abandonAudioFocusRequest(AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attr).setOnAudioFocusChangeListener(focusChangeListener).build())
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private suspend fun pauseInternal() {
        if (currentEngine == "SYSTEM") {
            systemTts.stop(manual = !userIntendedToPlay)
        } else {
            withContext(Dispatchers.Main) { awaitController()?.pause() }
        }
    }

    private suspend fun resumeInternal() {
        if (currentEngine == "SYSTEM") {
            requestSystemAudioFocus()
            systemTts.resume()
        } else {
            withContext(Dispatchers.Main) { awaitController()?.play() }
        }
    }

    private fun startHighlightUpdate() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val activeController = controller
                if (activeController != null) {
                    val duration = activeController.duration
                    val pos = activeController.currentPosition
                    if (duration > 0) {
                        _highlightedParagraphIndex.value = ((pos.toFloat() / duration.toFloat()) * currentParagraphsCount).toInt().coerceIn(0, currentParagraphsCount - 1)
                    }
                }
                delay(300)
            }
        }
    }

    private fun stopHighlightUpdate() = progressJob?.cancel()

    // البوابة الموحّدة الوحيدة للوصول إلى MediaController في هذا الملف.
    // تنتظر اكتمال الاتصال بدل الاعتماد على isDone فقط، وتضمن أن نتيجة الانتظار
    // والوصول للكونترولر يتمّان دائمًا على الـ Main thread (المطلوب من Media3).
    private suspend fun awaitController(): MediaController? = withContext(Dispatchers.Main) {
        initializeController()
        val future = controllerFuture ?: return@withContext null
        if (future.isDone) {
            try { future.get() } catch (e: Exception) { null }
        } else {
            suspendCancellableCoroutine { cont ->
                future.addListener({
                    val result = try { future.get() } catch (e: Exception) { null }
                    if (cont.isActive) cont.resume(result)
                }, MoreExecutors.directExecutor())
            }
        }
    }

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

    fun seekForward() {
        scope.launch {
            val activeController = awaitController() ?: return@launch
            activeController.seekTo(activeController.currentPosition + 10000)
        }
    }

    fun seekBackward() {
        scope.launch {
            val activeController = awaitController() ?: return@launch
            activeController.seekTo(activeController.currentPosition - 10000)
        }
    }

    fun release() { 
        abandonSystemAudioFocus()
        stopHighlightUpdate() 
        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
        systemTts.release()
    }
}