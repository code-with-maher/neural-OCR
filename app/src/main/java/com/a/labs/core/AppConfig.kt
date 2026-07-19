package com.a.labs.core

object GeminiModels {
    const val FLASH_3_5 = "gemini-3.5-flash"
    const val FLASH_3_1_LITE = "gemini-3.1-flash-lite"
    const val TTS_MODEL = "gemini-3.1-flash-tts-preview"

    val availableModels = listOf(
        FLASH_3_5,
        FLASH_3_1_LITE
    )
}
