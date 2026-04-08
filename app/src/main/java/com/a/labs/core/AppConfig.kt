package com.a.labs.core

object GeminiModels {
    const val FLASH_3_1_LITE = "gemini-3.1-flash-lite-preview"
    const val FLASH_3_0 = "gemini-3-flash-preview"
    const val FLASH_2_5 = "gemini-2.5-flash"

    // نموذج توليد الصوت
    const val TTS_MODEL = "gemini-2.5-flash-preview-tts"

    val availableModels = listOf(
        FLASH_3_1_LITE,
        FLASH_3_0,
        FLASH_2_5
    )
}