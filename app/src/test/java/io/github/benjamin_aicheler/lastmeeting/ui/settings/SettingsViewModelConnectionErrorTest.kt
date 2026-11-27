package io.github.benjamin_aicheler.lastmeeting.ui.settings

import android.app.Application
import android.os.Looper
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.ApiHttpException
import io.github.benjamin_aicheler.lastmeeting.data.api.InvalidApiKeyException
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.api.UnexpectedModelsResponseException
import io.github.benjamin_aicheler.lastmeeting.data.api.UnsupportedModelsEndpointException
import io.github.benjamin_aicheler.lastmeeting.data.preferences.ApiSettings
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsStore
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsViewModelConnectionErrorTest {

    @Test
    fun formatConnectionTestFailureDistinguishesErrorCategories() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals(
            context.getString(R.string.msg_connection_test_invalid_api_key),
            formatConnectionTestFailure(context, InvalidApiKeyException())
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_models_unsupported, 404),
            formatConnectionTestFailure(context, UnsupportedModelsEndpointException(404, "Not found"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_unexpected_response),
            formatConnectionTestFailure(context, UnexpectedModelsResponseException("HTML landing page"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_auth_failed, 401),
            formatConnectionTestFailure(context, ApiHttpException(401, "Invalid key"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_auth_failed, 403),
            formatConnectionTestFailure(context, ApiHttpException(403, "Forbidden"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_failed, "Bad gateway"),
            formatConnectionTestFailure(context, ApiHttpException(502, "Bad gateway"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_network_failed, "DNS lookup failed"),
            formatConnectionTestFailure(context, IOException("DNS lookup failed"))
        )
    }

    @Test
    fun testConnectionEmitsInvalidApiKeyMessageAndRestoresButtonWithoutNetworkCall() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val networkCalls = AtomicInteger()
        val apiClient = OpenAiApiClient(
            httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                networkCalls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"data":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }).build()
        )
        val settingsFlow = MutableStateFlow(
            ApiSettings(
                baseUrl = "https://api.example.com/v1/",
                apiKey = "sk-bad\u200Bkey",
                transcriptionMode = TranscriptionMode.AUTO,
                transcriptionModel = "gpt-4o-mini-transcribe",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize"
            )
        )
        val store = object : SettingsStore {
            override val settingsFlow: Flow<ApiSettings> = settingsFlow
            override suspend fun saveSettings(
                baseUrl: String,
                apiKey: String,
                transcriptionMode: TranscriptionMode,
                transcriptionModel: String,
                protocolModel: String,
                transcriptionPrompt: String,
                protocolPrompt: String
            ) = Unit
        }
        val viewModel = SettingsViewModel(
            app = context,
            repository = store,
            apiClient = apiClient
        )
        shadowOf(Looper.getMainLooper()).idle()

        val message = CompletableDeferred<String>()
        val collector = launch(Dispatchers.Main.immediate) {
            viewModel.messageFlow.collect {
                if (!message.isCompleted) message.complete(it)
            }
        }

        viewModel.testConnection()
        val emitted = withTimeout(2_000) {
            while (!message.isCompleted || viewModel.uiState.value.isTestingConnection) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
            message.await()
        }
        collector.cancel()

        assertEquals(context.getString(R.string.msg_connection_test_invalid_api_key), emitted)
        assertEquals(0, networkCalls.get())
        assertFalse(viewModel.uiState.value.isTestingConnection)
    }
}
