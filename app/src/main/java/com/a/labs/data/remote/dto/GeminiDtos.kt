package com.a.labs.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GeminiRequest(
    val systemInstruction: SystemInstruction? = null,
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null
)

@Serializable
data class SystemInstruction(
    val parts: List<Part>
)

@Serializable
data class Content(
    val role: String,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val fileData: FileData? = null
)

@Serializable
data class FileData(
    val mimeType: String,
    val fileUri: String
)

@Serializable
data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseSchema: JsonElement? = null
)

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate>? = null
)

@Serializable
data class Candidate(
    val content: Content? = null
)

@Serializable
data class OcrResultDto(
    val pages: List<OcrPageDto>
)

@Serializable
data class OcrPageDto(
    val pageNumber: Int,
    val markdownContent: String
)