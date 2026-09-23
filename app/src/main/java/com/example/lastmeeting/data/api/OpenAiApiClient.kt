package com.example.lastmeeting.data.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiApiClient {

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

            // Read audio file bytes and encode to base64
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            // Construct JSON request body for multimodal chat completions
            val requestJson = JSONObject().apply {
                put("model", model.ifBlank { "gpt-4o-audio-preview" })

                val messagesArray = JSONArray()
                val userMessage = JSONObject().apply {
                    put("role", "user")

                    val contentArray = JSONArray()

                    // Text prompt
                    val textContent = JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    }
                    contentArray.put(textContent)

                    // Audio input payload
                    val audioContent = JSONObject().apply {
                        put("type", "input_audio")
                        val inputAudioObj = JSONObject().apply {
                            put("data", base64Audio)
                            put("format", "m4a")
                        }
                        put("input_audio", inputAudioObj)
                    }
                    contentArray.put(audioContent)

                    put("content", contentArray)
                }
                messagesArray.put(userMessage)

                put("messages", messagesArray)
            }

            // Clean endpoint URL
            val formattedBaseUrl = baseUrl.trim().let {
                if (it.endsWith("/")) it else "$it/"
            }
            val endpointUrl = "${formattedBaseUrl}chat/completions"

            val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMessage = try {
                    val errorJson = JSONObject(responseBody)
                    errorJson.optJSONObject("error")?.optString("message") ?: responseBody
                } catch (e: Exception) {
                    "HTTP ${response.code}: ${response.message}"
                }
                return@withContext Result.failure(IOException(errorMessage))
            }

            val responseJson = JSONObject(responseBody)
            val choices = responseJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                val textResult = message?.optString("content") ?: ""
                Result.success(textResult.trim())
            } else {
                Result.failure(IOException("No response content generated from API."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
