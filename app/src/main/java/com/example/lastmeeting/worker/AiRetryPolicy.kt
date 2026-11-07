package com.example.lastmeeting.worker

import com.example.lastmeeting.data.api.ApiHttpException
import java.io.IOException

/**
 * Decides whether a failed AI job is worth another attempt.
 *
 * WorkManager owns the retries (exponential backoff, see [AiProcessingWorker]); this only
 * classifies the error, so a wrong API key or an unreadable recording does not burn the retry
 * budget.
 */
internal object AiRetryPolicy {

    /** Total runs of one job: the initial attempt plus two retries. */
    const val MAX_RUNS = 3

    fun isRetryable(error: Throwable, attemptCount: Int): Boolean =
        attemptCount < MAX_RUNS - 1 && isTransient(error)

    private fun isTransient(error: Throwable): Boolean = when {
        // A 4xx means the request itself is wrong: bad key, unsupported model, oversized payload.
        error is ApiHttpException -> error.code == 429 || error.code in 500..599
        // Timeouts, dropped connections, and empty completions are worth one more try.
        error is IOException -> true
        else -> false
    }
}
