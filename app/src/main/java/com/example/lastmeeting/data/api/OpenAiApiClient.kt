package com.example.lastmeeting.data.api

import com.example.lastmeeting.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
internal data class AudioTranscriptionResponse(
    val text: String? = null,
    val error: ApiErrorDetail? = null
)

@Serializable
internal data class ChatRequestPayload(
    val model: String,
    val messages: List<ChatRequestMessage>
)

@Serializable
internal data class ChatRequestMessage(
    val role: String,
    val content: String
)

@Serializable
internal data class ApiErrorResponse(
    val error: ApiErrorDetail? = null
)

@Serializable
internal data class ApiErrorDetail(
    val message: String? = null
)

/**
 * A non-2xx response. Carries the status code so callers can tell a retryable server problem
 * from a request that will never succeed.
 */
internal class ApiHttpException(val code: Int, message: String) : IOException(message)

class OpenAiApiClient(
    connectTimeoutSeconds: Long = 60,
    readTimeoutSeconds: Long = 300,
    writeTimeoutSeconds: Long = 120,
    internal val httpClient: OkHttpClient? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeAudio(
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

            val resolvedModel = SettingsRepository.resolveModel(
                stored = model,
                defaultModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL
            )
            val normalizedBaseUrl = formatBaseUrl(baseUrl)

            if (isOpenAiHost(normalizedBaseUrl)) {
                val body = createMultipartTranscriptionRequestBody(
                    model = resolvedModel,
                    prompt = prompt,
                    audioFile = audioFile
                )
                val request = Request.Builder()
                    .url("${normalizedBaseUrl}audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                executeTranscriptionRequest(request)
            } else {
                val body = createStreamingAudioRequestBody(
                    model = resolvedModel,
                    prompt = prompt,
                    audioFile = audioFile
                )
                val request = Request.Builder()
                    .url("${normalizedBaseUrl}chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                executeChatCompletionRequest(request)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateFromAudio(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        audioFile: File
    ): Result<String> = transcribeAudio(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        prompt = prompt,
        audioFile = audioFile
    )

    suspend fun generateFromText(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API key is missing."))
            }

            if (userText.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Transcript text is missing."))
            }

            val resolvedModel = SettingsRepository.resolveModel(
                stored = model,
                defaultModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL
            )
            val normalizedBaseUrl = formatBaseUrl(baseUrl)
            val body = createTextChatRequestBody(
                model = resolvedModel,
                systemPrompt = systemPrompt,
                userText = userText
            )

            val request = Request.Builder()
                .url("${normalizedBaseUrl}chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            executeChatCompletionRequest(request)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeTranscriptionRequest(request: Request): Result<String> {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()

            if (!response.isSuccessful) {
                return Result.failure(parseErrorException(response.code, response.message, responseBody))
            }

            val transcriptionResponse = json.decodeFromString<AudioTranscriptionResponse>(responseBody)
            val textResult = transcriptionResponse.text?.trim()

            return if (!textResult.isNullOrEmpty()) {
                Result.success(textResult)
            } else {
                Result.failure(IOException("No response content generated from API."))
            }
        }
    }

    private fun executeChatCompletionRequest(request: Request): Result<String> {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()

            if (!response.isSuccessful) {
                return Result.failure(parseErrorException(response.code, response.message, responseBody))
            }

            val completionResponse = json.decodeFromString<ChatCompletionResponse>(responseBody)
            val textResult = completionResponse.choices
                .firstOrNull()
                ?.message
                ?.content
                ?.trim()

            return if (!textResult.isNullOrEmpty()) {
                Result.success(textResult)
            } else {
                Result.failure(IOException("No response content generated from API."))
            }
        }
    }

    private fun parseErrorException(code: Int, httpMessage: String, responseBody: String): ApiHttpException {
        val errorMessage = try {
            val errorObj = json.decodeFromString<ApiErrorResponse>(responseBody)
            errorObj.error?.message?.takeIf { it.isNotBlank() } ?: responseBody
        } catch (_: Exception) {
            "HTTP $code: $httpMessage"
        }
        return ApiHttpException(code, errorMessage)
    }

    internal fun formatBaseUrl(baseUrl: String): String =
        baseUrl.trim().ifBlank { SettingsRepository.DEFAULT_BASE_URL }.let {
            if (it.endsWith("/")) it else "$it/"
        }

    internal fun isOpenAiHost(baseUrl: String): Boolean {
        val host = formatBaseUrl(baseUrl).toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == "api.openai.com" || host.endsWith(".api.openai.com")
    }

    internal fun createMultipartTranscriptionRequestBody(
        model: String,
        prompt: String,
        audioFile: File
    ): MultipartBody {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/mp4".toMediaType())
            )
            .addFormDataPart("model", model)

        if (prompt.isNotBlank()) {
            builder.addFormDataPart("prompt", prompt)
        }

        return builder.build()
    }

    internal fun createTextChatRequestBody(
        model: String,
        systemPrompt: String,
        userText: String
    ): RequestBody {
        val messages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(ChatRequestMessage(role = "system", content = systemPrompt))
            }
            add(ChatRequestMessage(role = "user", content = userText))
        }
        val payload = ChatRequestPayload(model = model, messages = messages)
        val encoded = json.encodeToString(ChatRequestPayload.serializer(), payload)
        return encoded.toRequestBody("application/json; charset=utf-8".toMediaType())
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
