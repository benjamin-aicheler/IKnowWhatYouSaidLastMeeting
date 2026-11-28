package io.github.benjamin_aicheler.lastmeeting.worker

import android.content.Context
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.ApiHttpException
import io.github.benjamin_aicheler.lastmeeting.data.api.AudioFileTooLargeException
import io.github.benjamin_aicheler.lastmeeting.data.api.EmptyAiResponseException
import io.github.benjamin_aicheler.lastmeeting.data.api.IncompleteCompletionException
import io.github.benjamin_aicheler.lastmeeting.data.api.MalformedAiResponseException
import io.github.benjamin_aicheler.lastmeeting.repository.MeetingRepository
import io.github.benjamin_aicheler.lastmeeting.repository.ProviderConfigurationChangedException
import kotlinx.serialization.SerializationException
import java.io.IOException

internal class AiTaskException(
    val errorCode: AiTaskErrorCode,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

enum class AiTaskErrorCode(val code: String) {
    MISSING_API_KEY("missing_api_key"),
    AUDIO_NOT_FOUND("audio_not_found"),
    AUDIO_TOO_LARGE("audio_too_large"),
    AUDIO_SPLIT_FAILED("audio_split_failed"),
    TRANSCRIPT_REQUIRED("transcript_required"),
    PROVIDER_CONFIG_CHANGED("provider_config_changed"),
    AUTH_FAILED("auth_failed"),
    RATE_LIMITED("rate_limited"),
    REQUEST_REJECTED("request_rejected"),
    SERVER_ERROR("server_error"),
    NETWORK_ERROR("network_error"),
    RESPONSE_PARSE_ERROR("response_parse_error"),
    INCOMPLETE_RESPONSE("incomplete_response"),
    EMPTY_TRANSCRIPT("empty_transcript"),
    EMPTY_RESPONSE("empty_response"),
    INVALID_WORK_REQUEST("invalid_work_request"),
    UNKNOWN("unknown");

    fun localizedMessage(context: Context): String = context.getString(
        when (this) {
            MISSING_API_KEY -> R.string.msg_missing_api_key
            AUDIO_NOT_FOUND -> R.string.msg_audio_not_found
            AUDIO_TOO_LARGE -> R.string.msg_audio_too_large
            AUDIO_SPLIT_FAILED -> R.string.msg_audio_too_large_split_failed
            TRANSCRIPT_REQUIRED -> R.string.msg_transcribe_first
            PROVIDER_CONFIG_CHANGED -> R.string.msg_provider_config_changed
            AUTH_FAILED -> R.string.msg_ai_error_auth
            RATE_LIMITED -> R.string.msg_ai_error_rate_limited
            REQUEST_REJECTED -> R.string.msg_ai_error_request_rejected
            SERVER_ERROR -> R.string.msg_ai_error_server
            NETWORK_ERROR -> R.string.msg_ai_error_network
            RESPONSE_PARSE_ERROR -> R.string.msg_ai_error_invalid_response
            INCOMPLETE_RESPONSE -> R.string.msg_ai_error_incomplete_response
            EMPTY_TRANSCRIPT -> R.string.msg_ai_error_empty_transcript
            EMPTY_RESPONSE -> R.string.msg_ai_error_empty_response
            INVALID_WORK_REQUEST, UNKNOWN -> R.string.msg_ai_error_unknown
        }
    )

    companion object {
        fun fromCode(code: String?): AiTaskErrorCode =
            entries.firstOrNull { it.code == code } ?: UNKNOWN

        fun fromThrowable(error: Throwable): AiTaskErrorCode = when (error) {
            is AiTaskException -> error.errorCode
            is ProviderConfigurationChangedException -> PROVIDER_CONFIG_CHANGED
            is AudioFileTooLargeException -> AUDIO_TOO_LARGE
            is IncompleteCompletionException -> INCOMPLETE_RESPONSE
            is EmptyAiResponseException -> EMPTY_RESPONSE
            is MalformedAiResponseException -> RESPONSE_PARSE_ERROR
            is ApiHttpException -> when (error.code) {
                401, 403 -> AUTH_FAILED
                429 -> RATE_LIMITED
                in 400..499 -> REQUEST_REJECTED
                in 500..599 -> SERVER_ERROR
                else -> UNKNOWN
            }
            is SerializationException -> RESPONSE_PARSE_ERROR
            is IOException -> NETWORK_ERROR
            else -> UNKNOWN
        }

        fun persistedMessage(context: Context, error: Throwable): String {
            val raw = when {
                error is ApiHttpException &&
                    error.code in 400..499 &&
                    error.code != 401 &&
                    error.code != 403 &&
                    error.code != 429 -> context.getString(R.string.msg_ai_error_request_rejected_http, error.code)
                error is IncompleteCompletionException ->
                    context.getString(R.string.msg_ai_incomplete_response, error.finishReason)
                else -> fromThrowable(error).localizedMessage(context)
            }
            return MeetingRepository.boundErrorMessage(context, raw)
        }
    }
}
