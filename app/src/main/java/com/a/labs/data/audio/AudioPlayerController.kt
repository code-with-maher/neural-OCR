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
    SYSTEM,
    GEMINI,
    MEDIA3
}

class AudioPlayerController(
    context: Context,
    private val repository: BookRepository,
    private val settingsManager: SettingsManager
) {
    private val appContext = context.applicationContext

    private val scope =
        CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val ioScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.MINUTES)
            .readTimeout(15, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.MINUTES)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var generationJob: Job? = null
    private var highlightJob: Job? = null

    private var geminiTts: GeminiTtsClient? = null

    private var currentBookId: String? = null
    private var currentPageNumber = -1
    private var paragraphCount = 1

    private var backend = PlaybackBackend.SYSTEM
    private var userWantsPlayback = false

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

            onHighlightProgress = {
                _highlightedParagraphIndex.value = it
            }
        }

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->

            if (backend != PlaybackBackend.SYSTEM) return@OnAudioFocusChangeListener

            when (change) {

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (_audioState.value == AudioState.PLAYING) {
                        systemTts.stop(manual = false)
                    }
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (userWantsPlayback) {
                        systemTts.resume()
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    userWantsPlayback = false
                    systemTts.stop(manual = true)
                }
            }
        }

    init {
        createNotificationChannel()
        connect()
    }

    fun connect() {
        if (
            controllerFuture != null &&
            controllerFuture?.isCancelled == false
        ) {
            connectedController?.let(::syncControllerState)
            return
        }

        val token =
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
                token
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
                        { onControllerReady(future) },
                        MoreExecutors.directExecutor()
                    )
                }
    }

    private val connectedController: MediaController?
        get() =
            if (controllerFuture?.isDone == true) {
                try {
                    controllerFuture?.get()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

    private fun onControllerReady(
        future: ListenableFuture<MediaController>
    ) {
        val controller =
            try {
                future.get()
            } catch (e: Exception) {
                setError(e)
                return
            }

        syncControllerState(controller)

        controller.addListener(
            object : Player.Listener {

                override fun onIsPlayingChanged(
                    isPlaying: Boolean
                ) {
                    if (backend != PlaybackBackend.MEDIA3) return

                    _audioState.value =
                        if (isPlaying) {
                            AudioState.PLAYING
                        } else {
                            AudioState.PAUSED
                        }

                    if (isPlaying) {
                        startHighlightUpdates()
                    } else {
                        stopHighlightUpdates()
                    }
                }

                override fun onPlaybackStateChanged(
                    state: Int
                ) {
                    if (backend != PlaybackBackend.MEDIA3) return

                    when (state) {

                        Player.STATE_BUFFERING ->
                            _audioState.value =
                                AudioState.PROCESSING

                        Player.STATE_READY ->
                            _audioState.value =
                                if (controller.isPlaying) {
                                    AudioState.PLAYING
                                } else {
                                    AudioState.PAUSED
                                }

                        Player.STATE_ENDED -> {
                            userWantsPlayback = false
                            _highlightedParagraphIndex.value = -1
                            _audioState.value = AudioState.PAUSED
                        }

                        Player.STATE_IDLE ->
                            _audioState.value = AudioState.IDLE
                    }
                }

                override fun onPlayerError(
                    error: PlaybackException
                ) {
                    setError(error)
                }
            }
        )
    }

    private fun syncControllerState(
        controller: MediaController
    ) {
        if (currentBookId == null) {

            val mediaId =
                controller.currentMediaItem?.mediaId
                    ?: return

            val parts = mediaId.split("::")

            if (parts.size != 2) return

            val page = parts[1].toIntOrNull()
                ?: return

            currentBookId = parts[0]
            currentPageNumber = page
            backend = PlaybackBackend.MEDIA3

            ioScope.launch {
                repository
                    .getPageByNumber(parts[0], page)
                    ?.let {
                        paragraphCount =
                            paragraphCount(it.markdownContent)
                    }
            }
        }

        if (backend != PlaybackBackend.MEDIA3) return

        userWantsPlayback = controller.isPlaying

        _audioState.value =
            when {
                controller.isPlaying ->
                    AudioState.PLAYING

                controller.playbackState ==
                    Player.STATE_BUFFERING ->
                    AudioState.PROCESSING

                else ->
                    AudioState.PAUSED
            }

        if (controller.isPlaying) {
            startHighlightUpdates()
        }
    }

    fun playPage(
        bookId: String,
        pageNumber: Int
    ) {
        generationJob?.cancel()

        generationJob =
            ioScope.launch {

                val engine =
                    settingsManager.ttsEngine.first()

                val samePage =
                    currentBookId == bookId &&
                        currentPageNumber == pageNumber

                if (samePage) {
                    toggle()
                    return@launch
                }

                stop()

                currentBookId = bookId
                currentPageNumber = pageNumber
                userWantsPlayback = true

                val page =
                    repository.getPageByNumber(
                        bookId,
                        pageNumber
                    ) ?: return@launch

                paragraphCount =
                    paragraphCount(page.markdownContent)

                when (engine) {

                    TtsEngineId.SYSTEM -> {
                        backend = PlaybackBackend.SYSTEM
                        playSystem(page)
                    }

                    TtsEngineId.GEMINI_TTS -> {
                        backend = PlaybackBackend.GEMINI
                        playGemini(
                            page,
                            bookId,
                            pageNumber
                        )
                    }

                    else -> {
                        setError(
                            Exception(
                                "Unsupported TTS engine: $engine"
                            )
                        )
                    }
                }
            }
    }

    private suspend fun playSystem(
        page: PageEntity
    ) {
        pauseMedia3()

        systemTts.stop(manual = true)
        requestSystemAudioFocus()

        _audioState.value = AudioState.PLAYING

        systemTts.speak(
            page.markdownContent
        )
    }

    private suspend fun playGemini(
        page: PageEntity,
        bookId: String,
        pageNumber: Int
    ) {
        pauseMedia3()
        systemTts.stop(manual = true)
        abandonSystemAudioFocus()

        val apiKey =
            settingsManager.geminiKey.first()

        if (apiKey.isBlank()) {
            setError(
                Exception("Gemini API key is missing")
            )
            return
        }

        val client =
            GeminiTtsClient(
                context = appContext,
                client = httpClient,
                apiKey = apiKey
            )

        geminiTts = client

        _audioState.value =
            AudioState.PROCESSING

        showGenerationNotification()

        val result =
            try {
                client.generateSpeech(
                    text = page.markdownContent,
                    fileName =
                        "audio_${bookId}_$pageNumber"
                )
            } finally {
                notificationManager.cancel(
                    GENERATION_NOTIFICATION_ID
                )
            }

        geminiTts = null

        if (!userWantsPlayback) return

        val file = result.getOrNull()

        if (file == null) {
            setError(
                result.exceptionOrNull()
                    ?: Exception("Gemini TTS failed")
            )
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
         * GeminiAudioPlayer has already handled
         * streaming playback.
         *
         * The file is now persisted for future
         * Media3 playback.
         */
        _audioState.value = AudioState.PAUSED
    }

    private suspend fun pauseMedia3() {
        withController {
            pause()
        }
    }

    private suspend fun toggle() {
        when (backend) {

            PlaybackBackend.SYSTEM -> {
                if (_audioState.value == AudioState.PLAYING) {
                    userWantsPlayback = false
                    systemTts.stop(manual = true)
                } else {
                    userWantsPlayback = true
                    requestSystemAudioFocus()
                    systemTts.resume()
                }
            }

            PlaybackBackend.GEMINI -> {
                if (_audioState.value == AudioState.PLAYING) {
                    userWantsPlayback = false
                    geminiTts?.pause()
                } else {
                    userWantsPlayback = true
                    geminiTts?.resume()
                }
            }

            PlaybackBackend.MEDIA3 -> {
                if (_audioState.value == AudioState.PLAYING) {
                    userWantsPlayback = false
                    withController { pause() }
                } else {
                    userWantsPlayback = true
                    withController { play() }
                }
            }
        }
    }

    fun seekForward() {
        seek(SEEK_STEP_MS)
    }

    fun seekBackward() {
        seek(-SEEK_STEP_MS)
    }

    private fun seek(delta: Long) {
        scope.launch {
            withController {
                seekTo(
                    (currentPosition + delta)
                        .coerceAtLeast(0)
                )
            }
        }
    }

    private fun startHighlightUpdates() {
        highlightJob?.cancel()

        highlightJob =
            scope.launch {

                while (true) {

                    val controller =
                        connectedController
                            ?: break

                    val duration =
                        controller.duration

                    if (duration > 0) {

                        val ratio =
                            controller.currentPosition
                                .toFloat() /
                                duration.toFloat()

                        _highlightedParagraphIndex.value =
                            (
                                ratio * paragraphCount
                            )
                                .toInt()
                                .coerceIn(
                                    0,
                                    paragraphCount - 1
                                )
                    }

                    delay(HIGHLIGHT_INTERVAL)
                }
            }
    }

    private fun stopHighlightUpdates() {
        highlightJob?.cancel()
        highlightJob = null
    }

    private suspend fun stop() {
        userWantsPlayback = false

        geminiTts?.stop()
        geminiTts = null

        systemTts.stop(manual = true)

        withController {
            pause()
        }

        abandonSystemAudioFocus()
        stopHighlightUpdates()
    }

    private suspend fun <T> withController(
        action: MediaController.() -> T
    ): T? =
        withContext(Dispatchers.Main) {

            connect()

            val future =
                controllerFuture
                    ?: return@withContext null

            val controller =
                if (future.isDone) {
                    try {
                        future.get()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    suspendCancellableCoroutine { continuation ->
                        future.addListener(
                            {
                                val result =
                                    try {
                                        future.get()
                                    } catch (_: Exception) {
                                        null
                                    }

                                if (continuation.isActive) {
                                    continuation.resume(result)
                                }
                            },
                            MoreExecutors.directExecutor()
                        )
                    }
                }

            controller?.action()
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
                    AudioManager
                        .AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(
                        focusListener
                    )
                    .build()
            )
        } else {

            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
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
                    AudioManager
                        .AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(
                        focusListener
                    )
                    .build()
            )
        } else {

            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(
                focusListener
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    GENERATION_CHANNEL,
                    "توليد الصوت",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun showGenerationNotification() {
        notificationManager.notify(
            GENERATION_NOTIFICATION_ID,
            NotificationCompat.Builder(
                appContext,
                GENERATION_CHANNEL
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
        )
    }

    private fun setError(error: Throwable) {
        _audioState.value = AudioState.ERROR
        _errorMessage.value = error.message
        userWantsPlayback = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun disconnect() {
        userWantsPlayback = false

        generationJob?.cancel()
        generationJob = null

        geminiTts?.stop()
        geminiTts = null

        systemTts.stop(manual = true)
        abandonSystemAudioFocus()
        stopHighlightUpdates()

        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
    }

    fun release() {
        disconnect()
        systemTts.release()
        scope.cancel()
        ioScope.cancel()
    }

    private fun paragraphCount(text: String): Int =
        text.split("\n\n")
            .count { it.isNotBlank() }
            .coerceAtLeast(1)

    private companion object {
        const val SEEK_STEP_MS = 10_000L
        const val HIGHLIGHT_INTERVAL = 300L
        const val GENERATION_NOTIFICATION_ID = 2002
        const val GENERATION_CHANNEL = "audio_generation"
    }
}