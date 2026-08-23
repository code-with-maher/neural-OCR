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
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles PCM audio produced by Gemini TTS streaming.
 *
 * Responsibilities:
 * - Plays incoming PCM chunks immediately through AudioTrack.
 * - Writes the same PCM stream directly to a WAV file.
 * - Does not keep the complete audio in RAM.
 * - Finalizes the WAV header when generation finishes.
 * - Supports pause, resume and stop.
 *
 * This class does not know anything about:
 * - Gemini API
 * - HTTP
 * - SSE
 * - Base64
 * - API keys
 *
 * GeminiTtsClient is responsible for receiving and decoding the
 * Gemini stream and passing PCM chunks to this class.
 */
class GeminiAudioPlayer(
    context: Context
) {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_BITS_PER_SAMPLE = 16

        private const val WAV_HEADER_SIZE = 44
        private const val PCM_FORMAT_CODE = 1
    }

    private val appContext = context.applicationContext

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val stopped = AtomicBoolean(true)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var channels = DEFAULT_CHANNELS
    private var bitsPerSample = DEFAULT_BITS_PER_SAMPLE

    private var outputFile: File? = null
    private var outputStream: BufferedOutputStream? = null
    private var totalPcmBytes: Long = 0L

    fun start(
        outputFile: File,
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
            "Only 16-bit PCM is supported"
        }

        this.sampleRate = sampleRate
        this.channels = channels
        this.bitsPerSample = bitsPerSample
        this.outputFile = outputFile
        this.totalPcmBytes = 0L

        outputFile.parentFile?.mkdirs()

        val stream = BufferedOutputStream(
            FileOutputStream(outputFile)
        )

        outputStream = stream

        writeWavHeader(
            output = stream,
            pcmDataSize = 0L
        )

        stream.flush()

        stopped.set(false)

        requestAudioFocus()
        createAudioTrack()
    }

    /**
     * Writes one PCM chunk to disk and immediately plays it.
     *
     * This method should be called as soon as each Gemini audio
     * chunk arrives.
     */
    @Synchronized
    fun writeChunk(pcmData: ByteArray) {
        if (pcmData.isEmpty() || stopped.get()) return

        val stream = outputStream
            ?: throw IllegalStateException(
                "GeminiAudioPlayer has not been started"
            )

        stream.write(pcmData)
        totalPcmBytes += pcmData.size.toLong()

        writeToAudioTrack(pcmData)
    }

    /**
     * Completes the WAV file and releases playback resources.
     */
    suspend fun finish(): File = withContext(Dispatchers.IO) {

        if (stopped.get()) {
            throw IllegalStateException(
                "Gemini audio playback is not active"
            )
        }

        val file = outputFile
            ?: throw IllegalStateException(
                "No output file configured"
            )

        val stream = outputStream
            ?: throw IllegalStateException(
                "Output stream is not open"
            )

        stream.flush()
        stream.close()
        outputStream = null

        updateWavHeader(
            file = file,
            pcmDataSize = totalPcmBytes
        )

        waitForPlaybackToFinish()

        releaseAudioTrack()
        abandonAudioFocus()

        stopped.set(true)

        file
    }

    /**
     * Stops playback immediately and closes the current file stream.
     *
     * The partially generated WAV file is not deleted.
     */
    @Synchronized
    fun stop() {
        stopped.set(true)

        try {
            outputStream?.flush()
        } catch (_: Exception) {
        }

        try {
            outputStream?.close()
        } catch (_: Exception) {
        }

        outputStream = null

        releaseAudioTrack()
        abandonAudioFocus()

        outputFile = null
        totalPcmBytes = 0L
    }

    fun pause() {
        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    track.pause()
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    fun resume() {
        if (stopped.get()) return

        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PAUSED) {
                try {
                    track.play()
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    fun isActive(): Boolean {
        return !stopped.get() && audioTrack != null
    }

    fun pcmBytesWritten(): Long {
        return totalPcmBytes
    }

    private fun createAudioTrack() {
        val channelMask =
            if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )

        require(minBufferSize > 0) {
            "Unable to determine AudioTrack buffer size"
        }

        val minimumStreamingBuffer =
            sampleRate * channels * 2 / 4

        val bufferSize = maxOf(
            minBufferSize * 2,
            minimumStreamingBuffer
        )

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .build()
            )
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()
    }

    private fun writeToAudioTrack(pcmData: ByteArray) {
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

    private fun waitForPlaybackToFinish() {
        val track = audioTrack ?: return

        try {
            val bytesPerFrame =
                channels * bitsPerSample / 8

            val writtenFrames =
                totalPcmBytes / bytesPerFrame

            val startPosition =
                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

            val targetPosition =
                startPosition + writtenFrames

            val timeoutMs = 5_000L
            val startTime = System.currentTimeMillis()

            while (!stopped.get()) {
                val currentPosition =
                    track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

                if (currentPosition >= targetPosition) {
                    break
                }

                if (
                    System.currentTimeMillis() - startTime >=
                    timeoutMs
                ) {
                    break
                }

                Thread.sleep(10L)
            }
        } catch (_: Exception) {
        }
    }

    private fun writeWavHeader(
        output: BufferedOutputStream,
        pcmDataSize: Long
    ) {
        val byteRate =
            sampleRate * channels * bitsPerSample / 8

        val blockAlign =
            channels * bitsPerSample / 8

        val riffChunkSize =
            36L + pcmDataSize

        val header = java.nio.ByteBuffer
            .allocate(WAV_HEADER_SIZE)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(riffChunkSize.toInt())

                put("WAVE".toByteArray(Charsets.US_ASCII))

                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)

                putShort(PCM_FORMAT_CODE.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())

                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmDataSize.toInt())
            }
            .array()

        output.write(header)
    }

    private fun updateWavHeader(
        file: File,
        pcmDataSize: Long
    ) {
        RandomAccessFile(file, "rw").use { randomAccessFile ->

            val byteRate =
                sampleRate * channels * bitsPerSample / 8

            val blockAlign =
                channels * bitsPerSample / 8

            val riffChunkSize =
                36L + pcmDataSize

            val header = java.nio.ByteBuffer
                .allocate(WAV_HEADER_SIZE)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt(riffChunkSize.toInt())

                    put("WAVE".toByteArray(Charsets.US_ASCII))

                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)

                    putShort(PCM_FORMAT_CODE.toShort())
                    putShort(channels.toShort())
                    putInt(sampleRate)
                    putInt(byteRate)
                    putShort(blockAlign.toShort())
                    putShort(bitsPerSample.toShort())

                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(pcmDataSize.toInt())
                }
                .array()

            randomAccessFile.seek(0)
            randomAccessFile.write(header)
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val attributes = AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                )
                .setContentType(
                    AudioAttributes.CONTENT_TYPE_SPEECH
                )
                .build()

            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()

                        AudioManager.AUDIOFOCUS_GAIN -> resume()
                    }
                }
                .build()

            audioFocusRequest = request
            audioManager.requestAudioFocus(request)

        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                legacyFocusChangeListener,
                AudioManager.STREAM_ACCESSIBILITY,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private val legacyFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()

                AudioManager.AUDIOFOCUS_GAIN -> resume()
            }
        }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }

            audioFocusRequest = null

        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(
                legacyFocusChangeListener
            )
        }
    }

    private fun releaseAudioTrack() {
        val track = audioTrack ?: return

        audioTrack = null

        try {
            track.pause()
        } catch (_: Exception) {
        }

        try {
            track.flush()
        } catch (_: Exception) {
        }

        try {
            track.stop()
        } catch (_: Exception) {
        }

        try {
            track.release()
        } catch (_: Exception) {
        }
    }
}