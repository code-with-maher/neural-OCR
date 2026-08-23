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
import kotlin.coroutines.resume

enum class AudioState {
    IDLE,
    PROCESSING,
    PLAYING,
    PAUSED,
    ERROR
}

object TtsEngineId {
    const val SYSTEM = "SYSTEM"
    const val GEMINI_TTS = "GEMINI_TTS"
}

private enum class PlaybackBackend {
    SYSTEM_TTS,
    GEMINI_STREAM,
    MEDIA3
}

class AudioPlayerController(
    context: Context,
    private val repository: BookRepository,
    private val settingsManager: SettingsManager
) {
    private val appContext = context.applicationContext

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val connectedControllerOrNull: MediaController?
        get() = if (controllerFuture?.isDone == true) {
            try {
                controllerFuture?.get()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

    private val _audioState = MutableStateFlow(AudioState.IDLE)
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _highlightedParagraphIndex = MutableStateFlow(-1)
    val highlightedParagraphIndex: StateFlow<Int> =
        _highlightedParagraphIndex.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> =
        _errorMessage.asStateFlow()

    private val scope =
        CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val generationScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var progressJob: Job? = null
    private var generationJob: Job? = null

    private var loadedBookId: String? = null
    private var loadedPageNum: Int = -1

    private var activeBackend =
        PlaybackBackend.SYSTEM_TTS

    private var currentParagraphsCount = 1
    private var userIntendedToPlay = false

    private var geminiClient: GeminiTtsClient? = null

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE)
            as AudioManager

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

    private val focusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->

            if (activeBackend != PlaybackBackend.SYSTEM_TTS) {
                return@OnAudioFocusChangeListener
            }

            when (focusChange) {

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

                AudioManager.AUDIOFOCUS_LOSS -> {
                    userIntendedToPlay = false
                    systemTts.stop(manual = true)
                }
            }
        }

    private val systemTts =
        SystemTtsWrapper(appContext).apply {

            onPlaybackStateChanged = { playing ->
                _audioState.value =
                    if (playing) {
                        AudioState.PLAYING
                    } else {
                        AudioState.PAUSED
                    }
            }

            onHighlightProgress = { index ->
                _highlightedParagraphIndex.value = index
            }
        }

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.MINUTES)
            .readTimeout(15, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.MINUTES)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()

    init {
        createNotificationChannel()
        connect()
    }

    fun connect() {
        if (
            controllerFuture != null &&
            controllerFuture?.isCancelled == false
        ) {
            connectedControllerOrNull?.let {
                syncStateFromController(it)
            }
            return
        }

        val sessionToken =
            SessionToken(
                appContext,
                ComponentName(
                    appContext,
                    PlaybackService::class.java
                )
            )

        controllerFuture =
            MediaController.Builder(
                appContext,
                sessionToken
            )
                .setListener(
                    object : MediaController.Listener {

                        override fun onDisconnected(
                            controller: MediaController
                        ) {
                            controllerFuture = null
                        }
                    }
                )
                .buildAsync()
                .also { future ->

                    future.addListener(
                        {
                            onControllerConnected(future)
                        },
                        MoreExecutors.directExecutor()
                    )
                }
    }

    private fun onControllerConnected(
        future: ListenableFuture<MediaController>
    ) {
        val mediaController =
            try {
                future.get()
            } catch (e: Exception) {
                _audioState.value = AudioState.ERROR
                _errorMessage.value = e.message
                return
            }

        syncStateFromController(mediaController)

        mediaController.addListener(
            object : Player.Listener {

                override fun onIsPlayingChanged(
                    isPlaying: Boolean
                ) {
                    if (activeBackend != PlaybackBackend.MEDIA3) {
                        return
                    }

                    _audioState.value =
                        if (isPlaying) {
                            AudioState.PLAYING
                        } else {
                            AudioState.PAUSED
                        }

                    if (isPlaying) {
                        startHighlightUpdate()
                    } else {
                        stopHighlightUpdate()
                    }
                }

                override fun onPlaybackStateChanged(
                    state: Int
                ) {
                    if (activeBackend != PlaybackBackend.MEDIA3) {
                        return
                    }

                    _audioState.value =
                        when (state) {

                            Player.STATE_ENDED -> {
                                userIntendedToPlay = false
                                _highlightedParagraphIndex.value = -1
                                AudioState.PAUSED
                            }

                            Player.STATE_READY -> {
                                if (mediaController.isPlaying) {
                                    AudioState.PLAYING
                                } else {
                                    AudioState.PAUSED
                                }
                            }

                            Player.STATE_BUFFERING ->
                                AudioState.PROCESSING

                            Player.STATE_IDLE ->
                                AudioState.IDLE

                            else ->
                                _audioState.value
                        }
                }

                override fun onPlayerError(
                    error: PlaybackException
                ) {
                    _audioState.value = AudioState.ERROR
                    userIntendedToPlay = false
                    _errorMessage.value = error.message
                }
            }
        )
    }

    private fun syncStateFromController(
        mediaController: MediaController
    ) {
        if (loadedBookId == null) {

            val restored =
                mediaController.currentMediaItem
                    ?.mediaId
                    ?.split("::")
                    ?.takeIf { it.size == 2 }
                    ?.let {
                        it[0] to it[1].toIntOrNull()
                    }
                    ?: return

            val restoredBookId = restored.first
            val restoredPage = restored.second

            if (restoredPage == null) {
                return
            }

            loadedBookId = restoredBookId
            loadedPageNum = restoredPage
            activeBackend = PlaybackBackend.MEDIA3

            generationScope.launch {
                repository
                    .getPageByNumber(
                        restoredBookId,
                        restoredPage
                    )
                    ?.let { page ->

                        currentParagraphsCount =
                            page.markdownContent
                                .split("\n\n")
                                .count { it.isNotBlank() }
                    }
            }
        }

        if (activeBackend != PlaybackBackend.MEDIA3) {
            return
        }

        userIntendedToPlay = mediaController.isPlaying

        _audioState.value =
            when {
                mediaController.isPlaying ->
                    AudioState.PLAYING

                mediaController.playbackState ==
                    Player.STATE_BUFFERING ->
                    AudioState.PROCESSING

                else ->
                    AudioState.PAUSED
            }

        if (mediaController.isPlaying) {
            startHighlightUpdate()
        } else {
            stopHighlightUpdate()
        }
    }

    private suspend fun <T> withController(
        action: MediaController.() -> T
    ): T? =
        withContext(Dispatchers.Main) {

            connect()

            val future =
                controllerFuture
                    ?: return@withContext null

            val mediaController =
                if (future.isDone) {

                    try {
                        future.get()
                    } catch (_: Exception) {
                        null
                    }

                } else {

                    suspendCancellableCoroutine { cont ->

                        future.addListener(
                            {
                                val result =
                                    try {
                                        future.get()
                                    } catch (_: Exception) {
                                        null
                                    }

                                if (cont.isActive) {
                                    cont.resume(result)
                                }
                            },
                            MoreExecutors.directExecutor()
                        )
                    }
                }

            mediaController?.action()
        }

    fun playPage(
        bookId: String,
        pageNumber: Int
    ) {
        connect()

        generationJob?.cancel()

        generationJob =
            generationScope.launch {

                val engineId =
                    settingsManager.ttsEngine.first()

                val isSamePage =
                    loadedBookId == bookId &&
                        loadedPageNum == pageNumber

                if (
                    isSamePage &&
                    _audioState.value != AudioState.ERROR
                ) {
                    toggleActivePage()
                    return@launch
                }

                stopCurrentPlayback()

                loadedBookId = bookId
                loadedPageNum = pageNumber
                userIntendedToPlay = true

                val page =
                    repository.getPageByNumber(
                        bookId,
                        pageNumber
                    ) ?: return@launch

                currentParagraphsCount =
                    page.markdownContent
                        .split("\n\n")
                        .count { it.isNotBlank() }

                when (engineId) {

                    TtsEngineId.SYSTEM -> {
                        activeBackend =
                            PlaybackBackend.SYSTEM_TTS

                        startSystemTts(page)
                    }

                    TtsEngineId.GEMINI_TTS -> {
                        activeBackend =
                            PlaybackBackend.GEMINI_STREAM

                        startGeminiPlayback(
                            page,
                            bookId,
                            pageNumber
                        )
                    }

                    else -> {
                        _audioState.value =
                            AudioState.ERROR

                        _errorMessage.value =
                            "محرك الصوت غير مدعوم حالياً."
                    }
                }
            }
    }

    private suspend fun toggleActivePage() {
        when (activeBackend) {

            PlaybackBackend.SYSTEM_TTS,
            PlaybackBackend.GEMINI_STREAM -> {

                if (_audioState.value ==
                    AudioState.PLAYING
                ) {
                    userIntendedToPlay = false
                    pauseInternal()
                } else {
                    userIntendedToPlay = true
                    resumeInternal()
                }
            }

            PlaybackBackend.MEDIA3 -> {

                if (_audioState.value ==
                    AudioState.PLAYING
                ) {
                    userIntendedToPlay = false
                    pauseInternal()
                } else {
                    userIntendedToPlay = true
                    resumeInternal()
                }
            }
        }
    }

    private suspend fun startSystemTts(
        page: PageEntity
    ) {
        withController {
            pause()
        }

        systemTts.stop(manual = true)

        requestSystemAudioFocus()

        _audioState.value = AudioState.PLAYING

        systemTts.speak(
            page.markdownContent
        )
    }

    private suspend fun startGeminiPlayback(
        page: PageEntity,
        bookId: String,
        pageNumber: Int
    ) {
        systemTts.stop(manual = true)
        abandonSystemAudioFocus()

        val apiKey =
            settingsManager.geminiKey.first()

        if (apiKey.isBlank()) {
            _audioState.value = AudioState.ERROR
            _errorMessage.value =
                "مفتاح Gemini غير موجود."
            return
        }

        val client =
            GeminiTtsClient(
                context = appContext,
                client = httpClient,
                apiKey = apiKey
            )

        geminiClient = client

        _audioState.value =
            AudioState.PROCESSING

        showGenerationNotification()

        val result =
            client.generateSpeech(
                text = page.markdownContent,
                fileName =
                    "audio_${bookId}_$pageNumber"
            )

        notificationManager.cancel(
            GENERATION_NOTIFICATION_ID
        )

        geminiClient = null

        if (!userIntendedToPlay) {
            return
        }

        val file = result.getOrNull()

        if (file == null) {
            _audioState.value =
                AudioState.ERROR

            _errorMessage.value =
                result.exceptionOrNull()?.message
                    ?: "فشل في توليد الصوت."

            return
        }

        repository.insertPages(
            listOf(
                page.copy(
                    audioUri = file.absolutePath
                )
            )
        )

        /*
         * GeminiAudioPlayer has already played the stream.
         *
         * The generated WAV is now ready for subsequent
         * Media3 playback.
         */
        if (_audioState.value != AudioState.PAUSED) {
            _audioState.value =
                AudioState.PAUSED
        }
    }

    private suspend fun startMedia3Playback(
        file: File,
        bookId: String,
        pageNumber: Int
    ) {
        withController {

            setMediaItem(
                MediaItem.Builder()
                    .setMediaId(
                        "$bookId::$pageNumber"
                    )
                    .setUri(
                        file.absolutePath
                    )
                    .build()
            )

            prepare()
            play()
        }
    }

    private suspend fun getExistingAudioFile(
        page: PageEntity
    ): File? {
        return page.audioUri
            ?.let(::File)
            ?.takeIf { it.exists() }
    }

    private suspend fun pauseInternal() {
        when (activeBackend) {

            PlaybackBackend.SYSTEM_TTS -> {
                systemTts.stop(
                    manual = !userIntendedToPlay
                )
            }

            PlaybackBackend.GEMINI_STREAM -> {
                geminiClient?.pause()
            }

            PlaybackBackend.MEDIA3 -> {
                withController {
                    pause()
                }
            }
        }
    }

    private suspend fun resumeInternal() {
        when (activeBackend) {

            PlaybackBackend.SYSTEM_TTS -> {
                requestSystemAudioFocus()
                systemTts.resume()
            }

            PlaybackBackend.GEMINI_STREAM -> {
                geminiClient?.resume()
            }

            PlaybackBackend.MEDIA3 -> {
                withController {
                    play()
                }
            }
        }
    }

    fun seekForward() {
        seekBy(SEEK_STEP_MS)
    }

    fun seekBackward() {
        seekBy(-SEEK_STEP_MS)
    }

    private fun seekBy(deltaMs: Long) {
        scope.launch {
            withController {
                seekTo(
                    (currentPosition + deltaMs)
                        .coerceAtLeast(0)
                )
            }
        }
    }

    private fun startHighlightUpdate() {
        progressJob?.cancel()

        progressJob =
            scope.launch {

                while (true) {

                    connectedControllerOrNull
                        ?.let { controller ->

                            val duration =
                                controller.duration

                            val position =
                                controller.currentPosition

                            if (duration > 0) {

                                val ratio =
                                    position.toFloat() /
                                        duration.toFloat()

                                _highlightedParagraphIndex.value =
                                    (
                                        ratio *
                                            currentParagraphsCount
                                    )
                                        .toInt()
                                        .coerceIn(
                                            0,
                                            currentParagraphsCount - 1
                                        )
                            }
                        }

                    delay(
                        HIGHLIGHT_POLL_INTERVAL_MS
                    )
                }
            }
    }

    private fun stopHighlightUpdate() {
        progressJob?.cancel()
        progressJob = null
    }

    private suspend fun stopCurrentPlayback() {
        userIntendedToPlay = false

        generationJob?.cancel()

        geminiClient?.stop()
        geminiClient = null

        systemTts.stop(manual = true)

        withController {
            pause()
        }

        abandonSystemAudioFocus()

        stopHighlightUpdate()
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

            audioManager.requestAudioFocus(
                AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(
                        focusChangeListener
                    )
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

            audioManager.abandonAudioFocusRequest(
                AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(
                        focusChangeListener
                    )
                    .build()
            )

        } else {

            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(
                focusChangeListener
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    GENERATION_NOTIFICATION_CHANNEL,
                    "توليد الصوت",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun showGenerationNotification() {
        val notification =
            NotificationCompat.Builder(
                appContext,
                GENERATION_NOTIFICATION_CHANNEL
            )
                .setContentTitle(
                    appContext.getString(
                        R.string.app_name
                    )
                )
                .setContentText(
                    "جاري توليد الصوت..."
                )
                .setSmallIcon(
                    R.mipmap.ic_launcher
                )
                .setOngoing(true)
                .build()

        notificationManager.notify(
            GENERATION_NOTIFICATION_ID,
            notification
        )
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun disconnect() {
        userIntendedToPlay = false

        generationJob?.cancel()
        generationJob = null

        geminiClient?.stop()
        geminiClient = null

        abandonSystemAudioFocus()
        stopHighlightUpdate()

        systemTts.stop(manual = true)

        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
    }

    fun release() {
        disconnect()

        systemTts.release()

        scope.cancel()
        generationScope.cancel()
    }

    private companion object {
        const val SEEK_STEP_MS = 10_000L
        const val HIGHLIGHT_POLL_INTERVAL_MS = 300L
        const val GENERATION_NOTIFICATION_ID = 2002
        const val GENERATION_NOTIFICATION_CHANNEL =
            "audio_gen_channel"
    }
}