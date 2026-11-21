package io.github.benjamin_aicheler.lastmeeting.worker

import io.github.benjamin_aicheler.lastmeeting.data.api.ApiHttpException
import io.github.benjamin_aicheler.lastmeeting.data.api.AudioFileTooLargeException
import io.github.benjamin_aicheler.lastmeeting.repository.ProviderConfigurationChangedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiRetryPolicyTest {

    @Test
    fun retriesNetworkFailures() {
        assertTrue(AiRetryPolicy.isRetryable(IOException("timeout"), attemptCount = 0))
        assertTrue(AiRetryPolicy.isRetryable(IOException("connection reset"), attemptCount = 1))
    }

    @Test
    fun retriesRateLimitsAndServerErrors() {
        assertTrue(AiRetryPolicy.isRetryable(ApiHttpException(429, "slow down"), attemptCount = 0))
        assertTrue(AiRetryPolicy.isRetryable(ApiHttpException(500, "boom"), attemptCount = 1))
        assertTrue(AiRetryPolicy.isRetryable(ApiHttpException(503, "unavailable"), attemptCount = 0))
    }

    @Test
    fun doesNotRetryRejectedRequests() {
        assertFalse(AiRetryPolicy.isRetryable(ApiHttpException(401, "invalid key"), attemptCount = 0))
        assertFalse(AiRetryPolicy.isRetryable(ApiHttpException(400, "bad request"), attemptCount = 0))
        assertFalse(AiRetryPolicy.isRetryable(ApiHttpException(404, "no model"), attemptCount = 0))
    }

    @Test
    fun doesNotRetryConfigurationAndDataErrors() {
        assertFalse(AiRetryPolicy.isRetryable(IllegalArgumentException("API key is missing."), 0))
        assertFalse(AiRetryPolicy.isRetryable(Exception("Meeting not found."), 0))
        assertFalse(
            AiRetryPolicy.isRetryable(
                AudioFileTooLargeException(30_000_000L, 25L * 1024L * 1024L, "too large"),
                0
            )
        )
        assertFalse(
            AiRetryPolicy.isRetryable(
                ProviderConfigurationChangedException("provider config changed"),
                0
            )
        )
    }

    @Test
    fun stopsWhenTheRetryBudgetIsSpent() {
        assertTrue(AiRetryPolicy.isRetryable(IOException("timeout"), AiRetryPolicy.MAX_RUNS - 2))
        assertFalse(AiRetryPolicy.isRetryable(IOException("timeout"), AiRetryPolicy.MAX_RUNS - 1))
    }
}
