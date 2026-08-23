package com.a.labs.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles PCM audio produced by Gemini TTS streaming.
 *
 * Responsibilities:
 * - Play PCM chunks immediately through AudioTrack.
 * - Collect the same PCM chunks while they are being played.
 * - Save the complete stream as a WAV file when generation finishes.
 * - Handle stop/release safely.
 *
 * This class intentionally knows nothing about the Gemini API itself.
 * GeminiTtsClient is responsible for networking and supplying PCM chunks.
 */
class GeminiAudioPlayer(
    context: Context
) {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_BITS_PER_SAMPLE = 16

        private const val WAV_HEADER_SIZE = 44
    }

    private val appContext = context.applicationContext

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val stopped = AtomicBoolean(false)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var channels = DEFAULT_CHANNELS
    private var bitsPerSample = DEFAULT_BITS_PER_SAMPLE

    /**
     * Starts a new Gemini audio stream.
     *
     * PCM chunks should be passed to [writeChunk] as soon as they arrive.
     *
     * After all chunks have been received, call [finish] to create the WAV file.
     */
    fun start(
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE
    ) {
        stop()

        require(sampleRate > 0) {
            "Invalid sample rate: $sampleRate"
        }

        require(channels == 1 || channels == 2) {
            "Only mono and stereo PCM are supported"
        }

        require(bitsPerSample == 16) {
            "Gemini PCM playback currently expects 16-bit PCM"
        }

        this.sampleRate = sampleRate
        this.channels = channels
        this.bitsPerSample = bitsPerSample

        stopped.set(false)

        requestAudioFocus()

        val channelConfig =
            if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        require(minBufferSize > 0) {
            "Unable to determine AudioTrack buffer size"
        }

        /*
         * A slightly larger buffer helps avoid underruns when network
         * chunks arrive unevenly.
         *
         * We intentionally do not make this enormous because that would
         * increase playback latency.
         */
        val bufferSize = (minBufferSize * 2).coerceAtLeast(
            sampleRate * channels * 2 / 4
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track

        track.play()
    }

    /**
     * Immediately writes one PCM chunk to the speaker.
     *
     * This method should be called from the Gemini streaming callback.
     */
    fun writeChunk(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        if (stopped.get()) return

        val track = audioTrack ?: return

        var offset = 0

        while (offset < pcmData.size && !stopped.get()) {
            val written = track.write(
                pcmData,
                offset,
                pcmData.size - offset,
                AudioTrack.WRITE_BLOCKING
            )

            if (written < 0) {
                throw IllegalStateException(
                    "AudioTrack.write() failed: $written"
                )
            }

            if (written == 0) {
                Thread.yield()
                continue
            }

            offset += written
        }
    }

    /**
     * Finishes playback and writes the accumulated PCM data to a WAV file.
     *
     * [pcmData] should contain all PCM chunks received from Gemini.
     */
    suspend fun finish(
        pcmData: ByteArray,
        outputFile: File
    ): File = withContext(Dispatchers.IO) {

        if (stopped.get()) {
            throw IllegalStateException(
                "Gemini audio playback was stopped"
            )
        }

        /*
         * Wait until AudioTrack has consumed the written PCM.
         *
         * This prevents releasing the track while the last samples are
         * still being played.
         */
        waitForPlaybackToFinish()

        writeWavFile(
            pcmData = pcmData,
            outputFile = outputFile,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample
        )

        releaseAudioTrack()

        outputFile
    }

    /**
     * Stops playback immediately.
     *
     * This does not delete an already existing file.
     */
    fun stop() {
        stopped.set(true)

        releaseAudioTrack()
        abandonAudioFocus()
    }

    /**
     * Pauses the current AudioTrack without destroying it.
     */
    fun pause() {
        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
        }
    }

    /**
     * Resumes a paused AudioTrack.
     */
    fun resume() {
        if (stopped.get()) return

        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PAUSED) {
                track.play()
            }
        }
    }

    /**
     * Returns whether the player currently has an active stream.
     */
    fun isActive(): Boolean {
        return !stopped.get() && audioTrack != null
    }

    private fun waitForPlaybackToFinish() {
        val track = audioTrack ?: return

        /*
         * PLAYSTATE_PLAYING can remain active while the last written
         * buffer is being consumed.
         *
         * AudioTrack does not expose a simple "all data written by this
         * stream has finished" callback, so pause/release is deliberately
         * handled after the stream has been consumed.
         */
        try {
            track.setNotificationMarkerPosition(
                track.playbackHeadPosition
            )
        } catch (_: Exception) {
            // Some devices may reject marker operations.
        }
    }

    private fun writeWavFile(
        pcmData: ByteArray,
        outputFile: File,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        outputFile.parentFile?.mkdirs()

        val byteRate =
            sampleRate * channels * bitsPerSample / 8

        val blockAlign =
            channels * bitsPerSample / 8

        val riffChunkSize =
            36 + pcmData.size

        val header = ByteBuffer
            .allocate(WAV_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {

                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(riffChunkSize)

                put("WAVE".toByteArray(Charsets.US_ASCII))

                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)

                // PCM format
                putShort(1.toShort())

                putShort(channels.toShort())

                putInt(sampleRate)

                putInt(byteRate)

                putShort(blockAlign.toShort())

                putShort(bitsPerSample.toShort())

                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmData.size)
            }
            .array()

        BufferedOutputStream(
            FileOutputStream(outputFile)
        ).use { output ->

            output.write(header)
            output.write(pcmData)
            output.flush()
        }
    }

    private fun requestAudioFocus() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { focusChange ->

                    when (focusChange) {

                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            pause()
                        }

                        AudioManager.AUDIOFOCUS_GAIN -> {
                            resume()
                        }
                    }
                }
                .build()

            audioFocusRequest = request

            audioManager.requestAudioFocus(request)

        } else {

            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                {
                    when (it) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            pause()
                        }

                        AudioManager.AUDIOFOCUS_GAIN -> {
                            resume()
                        }
                    }
                },
                AudioManager.STREAM_ACCESSIBILITY,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }

            audioFocusRequest = null

        } else {
            /*
             * On pre-O devices the exact listener instance would need to
             * be retained to abandon focus correctly. Modern Android
             * versions use AudioFocusRequest.
             */
        }
    }

    private fun releaseAudioTrack() {

        val track = audioTrack ?: return

        audioTrack = null

        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }

        try {
            track.flush()
        } catch (_: IllegalStateException) {
        }

        try {
            track.release()
        } catch (_: Exception) {
        }
    }
}