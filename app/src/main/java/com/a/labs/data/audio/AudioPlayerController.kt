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
import com.a.labs.data.local.room.entity.PageEntity
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

enum class AudioState { IDLE, PROCESSING, PLAYING, PAUSED, ERROR }

/** القيم المخزّنة فعليًا في [SettingsManager.ttsEngine]. */
object TtsEngineId {
    const val SYSTEM = "SYSTEM"
    const val ELEVENLABS = "ELEVENLABS"
    const val GEMINI_TTS = "GEMINI_TTS"
}

/** المحرّك الفعلي المتحكّم بالصوت حاليًا (حالة حيّة، وليست إعداد المستخدم). */
private enum class PlaybackBackend { SYSTEM_TTS, MEDIA3 }

class AudioPlayerController(
    private val context: Context,
    private val repository: BookRepository,
    private val settingsManager: SettingsManager
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val connectedControllerOrNull: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _audioState = MutableStateFlow(AudioState.IDLE)
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _highlightedParagraphIndex = MutableStateFlow(-1)
    val highlightedParagraphIndex: StateFlow<Int> = _highlightedParagraphIndex.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val generationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null

    private var loadedBookId: String? = null
    private var loadedPageNum: Int = -1
    private var activeBackend: PlaybackBackend = PlaybackBackend.SYSTEM_TTS
    private var currentParagraphsCount: Int = 1
    private var userIntendedToPlay = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (activeBackend == PlaybackBackend.SYSTEM_TTS) {
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

    // ------------------------------------------------------------------
    // اتصال الجلسة (Session) والمزامنة
    // ------------------------------------------------------------------

    private fun initializeController() {
        if (controllerFuture != null && controllerFuture?.isCancelled == false) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                // الجلسة انقطعت فعليًا (مثلًا بعد فترة في الخلفية) رغم أن الـ future
                // القديم لا يزال "مكتملًا" ظاهريًا. بدون هذا، تستمر كل الأزرار
                // بإرسال أوامر لكونترولر ميت بصمت دون أي تأثير أو خطأ.
                override fun onDisconnected(controller: MediaController) {
                    controllerFuture = null
                }
            })
            .buildAsync()
            .also { future ->
                future.addListener({ onControllerConnected(future) }, MoreExecutors.directExecutor())
            }
    }

    private fun onControllerConnected(future: ListenableFuture<MediaController>) {
        val mediaController = try { future.get() } catch (e: Exception) {
            _audioState.value = AudioState.ERROR
            _errorMessage.value = e.message
            return
        }

        syncStateFromController(mediaController)

        mediaController.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (activeBackend != PlaybackBackend.MEDIA3) return
                _audioState.value = if (isPlaying) AudioState.PLAYING else AudioState.PAUSED
                if (isPlaying) startHighlightUpdate() else stopHighlightUpdate()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (activeBackend != PlaybackBackend.MEDIA3) return
                _audioState.value = when (state) {
                    Player.STATE_ENDED -> {
                        userIntendedToPlay = false
                        _highlightedParagraphIndex.value = -1
                        AudioState.PAUSED
                    }
                    Player.STATE_READY -> if (mediaController.isPlaying) AudioState.PLAYING else AudioState.PAUSED
                    Player.STATE_BUFFERING -> AudioState.PROCESSING
                    Player.STATE_IDLE -> AudioState.IDLE
                    else -> _audioState.value
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _audioState.value = AudioState.ERROR
                userIntendedToPlay = false
                _errorMessage.value = error.message
            }
        })
    }

    /**
     * تُستدعى في كل اتصال ناجح (الأول، أو أي إعادة اتصال بعد انقطاع). مسؤوليتان:
     *
     * 1) إن لم تكن هناك صفحة محمّلة محليًا بعد ([loadedBookId] فارغة)، نحاول
     *    استخراجها من الـ MediaItem الحالي للجلسة — يحدث هذا عند إنشاء نسخة جديدة
     *    من هذا الكائن بينما الجلسة القديمة (PlaybackService) لا تزال تعمل، مثل
     *    حذف التطبيق من الأخيرة، أو الخروج من شاشة القارئ والعودة إليها.
     * 2) بغض النظر عن الحالة السابقة، إن كان المحرك الفعّال Media3، نُزامن حالة
     *    الواجهة مع الحالة *الحقيقية* للمشغّل الآن. هذه الخطوة ضرورية أيضًا عند
     *    إعادة الاتصال بعد انقطاع، لأن Player.Listener لا يُطلق تلقائيًا بحالة
     *    "الآن" عند إضافته، بل فقط عند حدوث تغيير لاحق.
     */
    private fun syncStateFromController(mediaController: MediaController) {
        if (loadedBookId == null) {
            val (restoredBookId, restoredPage) = mediaController.currentMediaItem
                ?.mediaId
                ?.split("::")
                ?.takeIf { it.size == 2 }
                ?.let { it[0] to it[1].toIntOrNull() }
                ?: return

            if (restoredPage == null) return

            loadedBookId = restoredBookId
            loadedPageNum = restoredPage
            activeBackend = PlaybackBackend.MEDIA3

            generationScope.launch {
                repository.getPageByNumber(restoredBookId, restoredPage)?.let { page ->
                    currentParagraphsCount = page.markdownContent.split("\n\n").count { it.isNotBlank() }
                }
            }
        }

        if (activeBackend != PlaybackBackend.MEDIA3) return

        userIntendedToPlay = mediaController.isPlaying
        _audioState.value = when {
            mediaController.isPlaying -> AudioState.PLAYING
            mediaController.playbackState == Player.STATE_BUFFERING -> AudioState.PROCESSING
            else -> AudioState.PAUSED
        }
        if (mediaController.isPlaying) startHighlightUpdate() else stopHighlightUpdate()
    }

    /**
     * البوابة الموحّدة والوحيدة للتعامل مع [MediaController] في هذا الملف.
     * تنتظر اكتمال الاتصال فعليًا، وتنفّذ [action] بأكملها ضمن نفس كتلة
     * withContext(Main)، فيستحيل بنيويًا استدعاء MediaController من thread خاطئ.
     */
    private suspend fun <T> withController(action: MediaController.() -> T): T? =
        withContext(Dispatchers.Main) {
            initializeController()
            val future = controllerFuture ?: return@withContext null
            val mediaController = if (future.isDone) {
                try { future.get() } catch (e: Exception) { null }
            } else {
                suspendCancellableCoroutine { cont ->
                    future.addListener({
                        val result = try { future.get() } catch (e: Exception) { null }
                        if (cont.isActive) cont.resume(result, onCancellation = null)
                    }, MoreExecutors.directExecutor())
                }
            }
            mediaController?.action()
        }

    // ------------------------------------------------------------------
    // التشغيل
    // ------------------------------------------------------------------

    fun playPage(bookId: String, pageNumber: Int) {
        initializeController()
        generationScope.launch {
            val engineId = settingsManager.ttsEngine.first()
            activeBackend = if (engineId == TtsEngineId.SYSTEM) PlaybackBackend.SYSTEM_TTS else PlaybackBackend.MEDIA3

            val isSamePage = loadedBookId == bookId && loadedPageNum == pageNumber
            if (isSamePage && _audioState.value != AudioState.ERROR) {
                toggleActivePage()
                return@launch
            }

            loadedBookId = bookId
            loadedPageNum = pageNumber
            userIntendedToPlay = true

            val page = repository.getPageByNumber(bookId, pageNumber) ?: return@launch
            currentParagraphsCount = page.markdownContent.split("\n\n").count { it.isNotBlank() }

            when (activeBackend) {
                PlaybackBackend.SYSTEM_TTS -> startSystemTts(page)
                PlaybackBackend.MEDIA3 -> startMedia3Playback(page, bookId, pageNumber, engineId)
            }
        }
    }

    private suspend fun toggleActivePage() {
        if (_audioState.value == AudioState.PLAYING) {
            userIntendedToPlay = false
            pauseInternal()
        } else {
            userIntendedToPlay = true
            resumeInternal()
        }
    }

    private suspend fun startSystemTts(page: PageEntity) {
        withController { pause() }
        systemTts.stop(manual = true)
        requestSystemAudioFocus()
        systemTts.speak(page.markdownContent)
    }

    private suspend fun startMedia3Playback(page: PageEntity, bookId: String, pageNumber: Int, engineId: String) {
        systemTts.stop(manual = true)
        abandonSystemAudioFocus()

        val audioFile = getAudioFile(page, bookId, pageNumber, engineId) ?: return

        withController {
            setMediaItem(
                MediaItem.Builder()
                    .setMediaId("$bookId::$pageNumber")
                    .setUri(audioFile.absolutePath)
                    .build()
            )
            prepare()
            play()
        }
    }

    private suspend fun getAudioFile(page: PageEntity, bookId: String, pageNumber: Int, engineId: String): File? {
        page.audioUri?.let { existingPath ->
            File(existingPath).takeIf { it.exists() }?.let { return it }
        }

        _audioState.value = AudioState.PROCESSING
        showGenerationNotification()

        val result = when (engineId) {
            TtsEngineId.ELEVENLABS -> {
                val apiKey = settingsManager.elevenKey.first()
                val voiceId = settingsManager.elevenVoiceId.first()
                ElevenLabsClient(context, httpClient, apiKey)
                    .generateSpeech(page.markdownContent, "audio_${bookId}_$pageNumber", voiceId)
            }
            else -> {
                val apiKey = settingsManager.geminiKey.first()
                GeminiTtsClient(context, httpClient, apiKey)
                    .generateSpeech(page.markdownContent, "audio_${bookId}_$pageNumber")
            }
        }

        notificationManager.cancel(GENERATION_NOTIFICATION_ID)

        val file = result.getOrNull()
        if (file != null) {
            repository.insertPages(listOf(page.copy(audioUri = file.absolutePath)))
        } else {
            _audioState.value = AudioState.ERROR
            _errorMessage.value = result.exceptionOrNull()?.message
        }
        return file
    }

    private suspend fun pauseInternal() {
        when (activeBackend) {
            PlaybackBackend.SYSTEM_TTS -> systemTts.stop(manual = !userIntendedToPlay)
            PlaybackBackend.MEDIA3 -> withController { pause() }
        }
    }

    private suspend fun resumeInternal() {
        when (activeBackend) {
            PlaybackBackend.SYSTEM_TTS -> {
                requestSystemAudioFocus()
                systemTts.resume()
            }
            PlaybackBackend.MEDIA3 -> withController { play() }
        }
    }

    // ------------------------------------------------------------------
    // التقديم / الترجيع
    // ------------------------------------------------------------------

    fun seekForward() = seekBy(SEEK_STEP_MS)
    fun seekBackward() = seekBy(-SEEK_STEP_MS)

    private fun seekBy(deltaMs: Long) {
        scope.launch {
            withController {
                seekTo((currentPosition + deltaMs).coerceAtLeast(0))
            }
        }
    }

    // ------------------------------------------------------------------
    // التظليل التدريجي أثناء التشغيل (Highlight)
    // ------------------------------------------------------------------

    private fun startHighlightUpdate() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                connectedControllerOrNull?.let { activeController ->
                    val duration = activeController.duration
                    val position = activeController.currentPosition
                    if (duration > 0) {
                        val ratio = position.toFloat() / duration.toFloat()
                        _highlightedParagraphIndex.value =
                            (ratio * currentParagraphsCount).toInt().coerceIn(0, currentParagraphsCount - 1)
                    }
                }
                delay(HIGHLIGHT_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopHighlightUpdate() = progressJob?.cancel()

    // ------------------------------------------------------------------
    // Audio Focus (لمحرك SYSTEM TTS فقط)
    // ------------------------------------------------------------------

    private fun requestSystemAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AndroidAudioAttributes.Builder()
                .setUsage(AndroidAudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioManager.requestAudioFocus(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_ACCESSIBILITY,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonSystemAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AndroidAudioAttributes.Builder()
                .setUsage(AndroidAudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioManager.abandonAudioFocusRequest(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    // ------------------------------------------------------------------
    // الإشعارات
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(GENERATION_NOTIFICATION_CHANNEL, "توليد الصوت", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun showGenerationNotification() {
        val notification = NotificationCompat.Builder(context, GENERATION_NOTIFICATION_CHANNEL)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("جاري توليد الصوت...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        notificationManager.notify(GENERATION_NOTIFICATION_ID, notification)
    }

    // ------------------------------------------------------------------
    // دورة الحياة
    // ------------------------------------------------------------------

    fun clearError() {
        _errorMessage.value = null
    }

    fun release() {
        abandonSystemAudioFocus()
        stopHighlightUpdate()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
        systemTts.release()
        scope.cancel()
        generationScope.cancel()
    }

    private companion object {
        const val SEEK_STEP_MS = 10_000L
        const val HIGHLIGHT_POLL_INTERVAL_MS = 300L
        const val GENERATION_NOTIFICATION_ID = 2002
        const val GENERATION_NOTIFICATION_CHANNEL = "audio_gen_channel"
    }
}