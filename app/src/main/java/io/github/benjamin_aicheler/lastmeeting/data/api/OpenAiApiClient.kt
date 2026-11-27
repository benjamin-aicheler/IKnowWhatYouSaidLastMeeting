package io.github.benjamin_aicheler.lastmeeting.data.api

import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsRepository
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
internal data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
    val error: ApiErrorDetail? = null
)

@Serializable
internal data class ChatChoice(
    val message: ChatMessageContent? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
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
internal open class ApiHttpException(val code: Int, message: String) : IOException(message)

internal class UnsupportedModelsEndpointException(code: Int, message: String) : ApiHttpException(code, message)

internal class UnexpectedModelsResponseException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal class AudioFileTooLargeException(
    val fileSizeBytes: Long,
    val maxBytes: Long,
    message: String
) : IllegalArgumentException(message)

internal class IncompleteCompletionException(
    val finishReason: String,
    message: String
) : IllegalStateException(message)

internal class EmptyAiResponseException(message: String) : IllegalStateException(message)

internal class MalformedAiResponseException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

sealed class BaseUrlValidationResult {
    data class Valid(val normalizedUrl: String, val isPlainHttp: Boolean) : BaseUrlValidationResult()
    data object InvalidSchemeOrHost : BaseUrlValidationResult()
    data object ContainsCredentialsOrQuery : BaseUrlValidationResult()
}

class OpenAiApiClient(
    connectTimeoutSeconds: Long = 60,
    readTimeoutSeconds: Long = 300,
    writeTimeoutSeconds: Long = 120,
    internal val httpClient: OkHttpClient? = null
) {

    companion object {
        const val MAX_AUDIO_UPLOAD_BYTES: Long = 25L * 1024L * 1024L
        internal const val MAX_API_ERROR_MESSAGE_CHARS = 300

        private fun sanitizeErrorText(raw: String): String {
            val collapsed = raw.replace(Regex("[\\s\\p{Cntrl}]+"), " ").trim()
            return if (collapsed.length > MAX_API_ERROR_MESSAGE_CHARS) {
                collapsed.take(MAX_API_ERROR_MESSAGE_CHARS - 1).trimEnd() + "…"
            } else {
                collapsed
            }
        }

        internal fun formatBaseUrl(baseUrl: String): String =
            baseUrl.trim().ifBlank { SettingsRepository.DEFAULT_BASE_URL }.let {
                if (it.endsWith("/")) it else "$it/"
            }

        internal fun validateBaseUrl(rawUrl: String): BaseUrlValidationResult {
            val trimmed = rawUrl.trim()
            if ('?' in trimmed || '#' in trimmed) {
                return BaseUrlValidationResult.ContainsCredentialsOrQuery
            }
            val candidate = formatBaseUrl(trimmed)
            val parsed = candidate.toHttpUrlOrNull() ?: return BaseUrlValidationResult.InvalidSchemeOrHost
            if ((parsed.scheme != "https" && parsed.scheme != "http") || parsed.host.isBlank()) {
                return BaseUrlValidationResult.InvalidSchemeOrHost
            }
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) {
                return BaseUrlValidationResult.ContainsCredentialsOrQuery
            }
            return BaseUrlValidationResult.Valid(
                normalizedUrl = candidate,
                isPlainHttp = parsed.scheme == "http"
            )
        }
    }

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
        audioFile: File,
        transcriptionMode: TranscriptionMode = TranscriptionMode.AUTO
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API key is missing."))
            }

            if (!audioFile.exists() || audioFile.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("Audio file does not exist or is empty."))
            }

            if (audioFile.length() > MAX_AUDIO_UPLOAD_BYTES) {
                return@withContext Result.failure(
                    AudioFileTooLargeException(
                        fileSizeBytes = audioFile.length(),
                        maxBytes = MAX_AUDIO_UPLOAD_BYTES,
                        message = "Audio file (${audioFile.length()} bytes) exceeds the 25 MB upload limit ($MAX_AUDIO_UPLOAD_BYTES bytes)."
                    )
                )
            }

            val resolvedModel = SettingsRepository.resolveModel(
                stored = model,
                defaultModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL
            )
            val normalizedBaseUrl = formatBaseUrl(baseUrl)

            if (shouldUseMultipartTranscription(normalizedBaseUrl, transcriptionMode)) {
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

                executeChatCompletionRequest(request, allowBlankContent = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

            executeChatCompletionRequest(request, allowBlankContent = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API key is missing."))
            }

            val validated = when (val validation = validateBaseUrl(baseUrl)) {
                is BaseUrlValidationResult.Valid -> validation
                BaseUrlValidationResult.InvalidSchemeOrHost ->
                    return@withContext Result.failure(IllegalArgumentException("Invalid base URL."))
                BaseUrlValidationResult.ContainsCredentialsOrQuery ->
                    return@withContext Result.failure(
                        IllegalArgumentException("Base URL must not contain credentials or query parameters.")
                    )
            }

            val request = Request.Builder()
                .url("${validated.normalizedUrl}models")
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()

            executeCancellableCall(client, request) { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    val httpError = parseErrorException(response.code, response.message, responseBody)
                    if (response.code == 404 || response.code == 405 || response.code == 501) {
                        return@executeCancellableCall Result.failure(
                            UnsupportedModelsEndpointException(
                                response.code,
                                httpError.message ?: "HTTP ${response.code}"
                            )
                        )
                    }
                    return@executeCancellableCall Result.failure(httpError)
                }
                validateModelsListResponse(response.body.contentType(), responseBody)
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun validateModelsListResponse(contentType: MediaType?, responseBody: String) {
        if (contentType != null && (
                contentType.subtype.equals("html", ignoreCase = true) ||
                    contentType.subtype.equals("xhtml+xml", ignoreCase = true)
            )
        ) {
            throw UnexpectedModelsResponseException("Endpoint returned HTML instead of a JSON model list.")
        }
        if (responseBody.isBlank()) {
            throw UnexpectedModelsResponseException("Endpoint returned an empty response.")
        }
        val element = try {
            Json.parseToJsonElement(responseBody)
        } catch (e: Exception) {
            throw UnexpectedModelsResponseException("Endpoint did not return valid JSON.", e)
        }
        val root = element as? JsonObject
            ?: throw UnexpectedModelsResponseException("Endpoint JSON response is not an object.")
        val data = root["data"] as? JsonArray
            ?: throw UnexpectedModelsResponseException("Model list JSON is missing a 'data' array.")
        for (entry in data) {
            val modelObj = entry as? JsonObject
                ?: throw UnexpectedModelsResponseException("Model list entry is not a JSON object.")
            val id = modelObj["id"] as? JsonPrimitive
            if (id == null || !id.isString || id.content.isBlank()) {
                throw UnexpectedModelsResponseException("Model list entry is missing a non-blank string 'id'.")
            }
        }
    }

    private suspend fun <T> executeCancellableCall(
        callClient: OkHttpClient,
        request: Request,
        handleResponse: (Response) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val call = callClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { handleResponse(it) }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                } catch (e: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private suspend fun executeTranscriptionRequest(request: Request): Result<String> =
        executeCancellableCall(client, request) { response ->
            val responseBody = response.body.string()

            if (!response.isSuccessful) {
                return@executeCancellableCall Result.failure(
                    parseErrorException(response.code, response.message, responseBody)
                )
            }

            val transcriptionResponse = json.decodeFromString<AudioTranscriptionResponse>(responseBody)
            transcriptionResponse.error?.let { apiError ->
                val msg = apiError.message?.let(::sanitizeErrorText)?.takeIf { it.isNotEmpty() }
                    ?: "API returned an error payload in a 2xx transcription response."
                return@executeCancellableCall Result.failure(MalformedAiResponseException(msg))
            }
            val rawText = transcriptionResponse.text
                ?: return@executeCancellableCall Result.failure(
                    MalformedAiResponseException("Transcription response is missing 'text' field.")
                )
            Result.success(rawText.trim())
        }

    private suspend fun executeChatCompletionRequest(
        request: Request,
        allowBlankContent: Boolean
    ): Result<String> =
        executeCancellableCall(client, request) { response ->
            val responseBody = response.body.string()

            if (!response.isSuccessful) {
                return@executeCancellableCall Result.failure(
                    parseErrorException(response.code, response.message, responseBody)
                )
            }

            val completionResponse = json.decodeFromString<ChatCompletionResponse>(responseBody)
            completionResponse.error?.let { apiError ->
                val msg = apiError.message?.let(::sanitizeErrorText)?.takeIf { it.isNotEmpty() }
                    ?: "API returned an error payload in a 2xx completion response."
                return@executeCancellableCall Result.failure(MalformedAiResponseException(msg))
            }
            val firstChoice = completionResponse.choices.firstOrNull()
                ?: return@executeCancellableCall Result.failure(
                    MalformedAiResponseException("Chat completion response contained no choices.")
                )
            val finishReason = firstChoice.finishReason?.trim()?.takeIf { it.isNotEmpty() }
            if (finishReason != null && !isCompleteFinishReason(finishReason)) {
                return@executeCancellableCall Result.failure(
                    IncompleteCompletionException(
                        finishReason = finishReason,
                        message = "Incomplete response from API (finish_reason: $finishReason)."
                    )
                )
            }
            val rawContent = firstChoice.message?.content
                ?: return@executeCancellableCall Result.failure(
                    MalformedAiResponseException("Chat completion choice is missing message content.")
                )
            val textResult = rawContent.trim()

            when {
                textResult.isNotEmpty() -> Result.success(textResult)
                allowBlankContent -> Result.success("")
                else -> Result.failure(EmptyAiResponseException("No response content generated from API."))
            }
        }

    private fun isCompleteFinishReason(finishReason: String): Boolean =
        when (finishReason.trim().lowercase(Locale.ROOT)) {
            "stop", "end_turn" -> true
            else -> false
        }

    private fun parseErrorException(code: Int, httpMessage: String, responseBody: String): ApiHttpException {
        val structuredMessage = try {
            json.decodeFromString<ApiErrorResponse>(responseBody)
                .error
                ?.message
                ?.let(::sanitizeErrorText)
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        val fallback = sanitizeErrorText("HTTP $code: ${httpMessage.trim()}").trimEnd(':', ' ')
        return ApiHttpException(code, structuredMessage ?: fallback)
    }

    internal fun shouldUseMultipartTranscription(
        baseUrl: String,
        transcriptionMode: TranscriptionMode = TranscriptionMode.AUTO
    ): Boolean = when (transcriptionMode) {
        TranscriptionMode.AUTO -> isOpenAiHost(baseUrl)
        TranscriptionMode.AUDIO_TRANSCRIPTIONS -> true
        TranscriptionMode.CHAT_COMPLETIONS -> false
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
