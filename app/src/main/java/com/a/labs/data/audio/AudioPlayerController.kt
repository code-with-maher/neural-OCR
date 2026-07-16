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

/**
 * القيم المخزّنة فعليًا في [SettingsManager.ttsEngine]. موحّدة هنا لتفادي تكرار
 * السلاسل النصية الحرفية ("magic strings") في أكثر من مكان بالملف.
 */
object TtsEngineId {
    const val SYSTEM = "SYSTEM"
    const val ELEVENLABS = "ELEVENLABS"
    const val GEMINI_TTS = "GEMINI_TTS"
}

/**
 * أي "محرّك تشغيل" فعلي يتحكّم بالصوت حاليًا. هذا مختلف عن [TtsEngineId]:
 * الأخير هو إعداد المستخدم المحفوظ، بينما هذا يعكس الحالة الحيّة الفعلية،
 * وهو ما تعتمد عليه كل قرارات التوجيه (routing) داخل هذا الملف.
 */
private enum class PlaybackBackend { SYSTEM_TTS, MEDIA3 }

class AudioPlayerController(
    private val context: Context,
    private val repository: BookRepository,
    private val settingsManager: SettingsManager
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** وصول متزامن فوري؛ آمن فقط عندما نعلم مسبقًا أن الاتصال مكتمل. */
    private val connectedControllerOrNull: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _audioState = MutableStateFlow(AudioState.IDLE)
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _highlightedParagraphIndex = MutableStateFlow(-1)
    val highlightedParagraphIndex: StateFlow<Int> = _highlightedParagraphIndex.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // SupervisorJob في كلا الـ scopes: فشل عملية واحدة (مثلاً seek فاشل) يجب ألا
    // يُسقط بقية العمليات الجارية (كحلقة تحديث التظليل أو توليد الصوت).
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
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync().also { future ->
            future.addListener({ onControllerConnected(future) }, MoreExecutors.directExecutor())
        }
    }

    private fun onControllerConnected(future: ListenableFuture<MediaController>) {
        val mediaController = try { future.get() } catch (e: Exception) {
            _audioState.value = AudioState.ERROR
            _errorMessage.value = e.message
            return
        }

        restorePlaybackStateIfNeeded(mediaController)

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
     * تُستدعى فور اكتمال الاتصال بالجلسة. إن كانت خدمة [PlaybackService] لا تزال
     * تعمل من نسخة سابقة من هذا الكونترولر (مثلًا بعد حذف التطبيق من التطبيقات
     * الأخيرة، أو مجرد الخروج من شاشة القارئ والعودة إليها) بينما هذه نسخة جديدة
     * بحالة ابتدائية فارغة، نعيد بناء الحالة من معلومات الـ MediaItem الحالي
     * بدل تركها IDLE رغم أن الصوت يعمل فعليًا.
     */
    private fun restorePlaybackStateIfNeeded(mediaController: MediaController) {
        if (loadedBookId != null) return // لدينا حالة محمّلة فعلًا، لا حاجة لاستعادة

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
        userIntendedToPlay = mediaController.isPlaying

        _audioState.value = when {
            mediaController.isPlaying -> AudioState.PLAYING
            mediaController.playbackState == Player.STATE_BUFFERING -> AudioState.PROCESSING
            else -> AudioState.PAUSED
        }
        if (mediaController.isPlaying) startHighlightUpdate()

        generationScope.launch {
            repository.getPageByNumber(restoredBookId, restoredPage)?.let { page ->
                currentParagraphsCount = page.markdownContent.split("\n\n").count { it.isNotBlank() }
            }
        }
    }

    /**
     * البوابة الموحّدة الوحيدة للوصول إلى [MediaController] في هذا الملف.
     * تنتظر اكتمال الاتصال فعليًا بدل الاعتماد على isDone فقط (وهو ما كان يسبب
     * تجاهل أول ضغطة على أزرار التقديم/الترجيع مباشرة بعد إعادة إنشاء الكونترولر)،
     * وتضمن أن كل وصول للكونترولر يتم على الـ Main thread كما تتطلب Media3.
     */
    private suspend fun awaitController(): MediaController? = withContext(Dispatchers.Main) {
        initializeController()
        val future = controllerFuture ?: return@withContext null
        if (future.isDone) {
            try { future.get() } catch (e: Exception) { null }
        } else {
            suspendCancellableCoroutine { cont ->
                future.addListener({
                    val result = try { future.get() } catch (e: Exception) { null }
                    if (cont.isActive) cont.resume(result, onCancellation = null)
                }, MoreExecutors.directExecutor())
            }
        }
    }

    // ------------------------------------------------------------------
    // التشغيل
    // ------------------------------------------------------------------

    fun playPage(bookId: String, pageNumber: Int) {
        initializeController() // بدء الاتصال فورًا وبأقصى سرعة، دون انتظار الكود أدناه
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
        withContext(Dispatchers.Main) { awaitController()?.pause() }
        systemTts.stop(manual = true) // ضمان عدم تراكب أي تشغيل سابق
        abandonMedia3Focus = false
        requestSystemAudioFocus()
        systemTts.speak(page.markdownContent)
    }

    private suspend fun startMedia3Playback(page: PageEntity, bookId: String, pageNumber: Int, engineId: String) {
        systemTts.stop(manual = true)
        abandonSystemAudioFocus()

        val audioFile = getAudioFile(page, bookId, pageNumber, engineId) ?: return
        val activeController = awaitController() ?: return

        val mediaItem = MediaItem.Builder()
            .setMediaId("$bookId::$pageNumber")
            .setUri(audioFile.absolutePath)
            .build()
        activeController.setMediaItem(mediaItem)
        activeController.prepare()
        activeController.play()
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
            PlaybackBackend.MEDIA3 -> withContext(Dispatchers.Main) { awaitController()?.pause() }
        }
    }

    private suspend fun resumeInternal() {
        when (activeBackend) {
            PlaybackBackend.SYSTEM_TTS -> {
                requestSystemAudioFocus()
                systemTts.resume()
            }
            PlaybackBackend.MEDIA3 -> withContext(Dispatchers.Main) { awaitController()?.play() }
        }
    }

    // ------------------------------------------------------------------
    // التقديم / الترجيع
    // ------------------------------------------------------------------

    fun seekForward() = seekBy(SEEK_STEP_MS)
    fun seekBackward() = seekBy(-SEEK_STEP_MS)

    private fun seekBy(deltaMs: Long) {
        scope.launch {
            val activeController = awaitController() ?: return@launch
            val target = (activeController.currentPosition + deltaMs).coerceAtLeast(0)
            activeController.seekTo(target)
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

    private var abandonMedia3Focus = false // محجوزة لتوسعات مستقبلية عند الحاجة لـ focus خاص بـ Media3

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

    /**
     * يجب استدعاؤها دائمًا عند انتهاء الحاجة لهذا الكائن (مثلًا من onCleared في
     * الـ ViewModel) لتجنّب تسريب الذاكرة: تُلغي كل الـ coroutines الجارية عبر
     * إغلاق الـ scopes، وتُحرر الاتصال بالجلسة ومحرك SYSTEM TTS.
     */
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