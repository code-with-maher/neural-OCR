package com.a.labs.data.audio

enum class AudioState {
    IDLE,
    PROCESSING,
    PLAYING,
    PAUSED,
    ERROR
}

object TtsEngineId {
    const val SYSTEM = "SYSTEM"
    const val ELEVENLABS = "ELEVENLABS"
    const val GEMINI_TTS = "GEMINI_TTS"
}