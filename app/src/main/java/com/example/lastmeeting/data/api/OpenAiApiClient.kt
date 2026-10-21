package com.example.lastmeeting.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

@Serializable
internal data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
    val error: ApiErrorDetail? = null
)

@Serializable
internal data class ChatChoice(
    val message: ChatMessageContent? = null
)

@Serializable
internal data class ChatMessageContent(
    val content: String? = null
)

@Serializable
internal data class ApiErrorResponse(
    val error: ApiErrorDetail? = null
)

@Serializable
internal data class ApiErrorDetail(
    val message: String? = null
)

class OpenAiApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun generateFromAudio(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        audioFile: File
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API key is missing."))
            }

            if (!audioFile.exists() || audioFile.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("Audio file does not exist or is empty."))
            }

            val resolvedModel = model.ifBlank { "gpt-4o-audio-preview" }
            val body = createStreamingAudioRequestBody(
                model = resolvedModel,
                prompt = prompt,
                audioFile = audioFile
            )

            val formattedBaseUrl = baseUrl.trim().let {
                if (it.endsWith("/")) it else "$it/"
            }
            val endpointUrl = "${formattedBaseUrl}chat/completions"

            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMessage = try {
                        val errorObj = json.decodeFromString<ApiErrorResponse>(responseBody)
                        errorObj.error?.message?.takeIf { it.isNotBlank() } ?: responseBody
                    } catch (_: Exception) {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    return@withContext Result.failure(IOException(errorMessage))
                }

                val completionResponse = json.decodeFromString<ChatCompletionResponse>(responseBody)
                val textResult = completionResponse.choices
                    .firstOrNull()
                    ?.message
                    ?.content
                    ?.trim()

                if (!textResult.isNullOrEmpty()) {
                    Result.success(textResult)
                } else {
                    Result.failure(IOException("No response content generated from API."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun createStreamingAudioRequestBody(
        model: String,
        prompt: String,
        audioFile: File
    ): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType = "application/json; charset=utf-8".toMediaType()

        override fun writeTo(sink: BufferedSink) {
            val encodedModel = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(model))
            val encodedPrompt = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(prompt))

            sink.writeUtf8("""{"model":$encodedModel,"messages":[{"role":"user","content":[{"type":"text","text":$encodedPrompt},{"type":"input_audio","input_audio":{"data":"""")

            val encoder = Base64.getEncoder()
            // Read in multiples of 3 bytes (12 KB) so intermediate Base64 chunks never have '=' padding
            val buffer = ByteArray(3 * 4096)
            audioFile.inputStream().use { input ->
                while (true) {
                    var offset = 0
                    while (offset < buffer.size) {
                        val read = input.read(buffer, offset, buffer.size - offset)
                        if (read == -1) break
                        offset += read
                    }
                    if (offset == 0) break

                    val chunk = if (offset == buffer.size) buffer else buffer.copyOf(offset)
                    sink.writeUtf8(encoder.encodeToString(chunk))

                    if (offset < buffer.size) break
                }
            }

            sink.writeUtf8("""","format":"m4a"}}]}]}""")
        }
    }
}
