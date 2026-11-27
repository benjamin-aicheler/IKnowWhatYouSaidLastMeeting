package io.github.benjamin_aicheler.lastmeeting.ui.settings

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.BaseUrlValidationResult
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.preferences.ApiSettings
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsRepository
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsStore
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsViewModelDraftTest {

    private lateinit var app: Application
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun ignoresEditsSavesAndConnectionTestsBeforeDataStoreEmits() = runBlocking {
        val store = FakeSettingsStore()
        val networkCalls = AtomicInteger()
        val viewModel = SettingsViewModel(
            app = app,
            repository = store,
            apiClient = OpenAiApiClient(
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
        ).also { viewModelStore.put("pre-load", it) }

        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(viewModel.uiState.value.isLoaded)
        assertFalse(viewModel.uiState.value.canSave)
        assertFalse(viewModel.uiState.value.canTestConnection)

        viewModel.onBaseUrlChanged("https://evil.example/v1/")
        viewModel.onApiKeyChanged("sk-premature")
        viewModel.saveSettings()
        viewModel.testConnection()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, store.saveCount)
        assertEquals(0, networkCalls.get())
        assertEquals(SettingsRepository.DEFAULT_BASE_URL, viewModel.uiState.value.draft.baseUrl)
        assertEquals("", viewModel.uiState.value.draft.apiKey)

        store.emit(initialSettings())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.uiState.value.isLoaded)
        assertEquals("https://initial.example/v1/", viewModel.uiState.value.draft.baseUrl)
        assertEquals("sk-initial", viewModel.uiState.value.draft.apiKey)
        assertTrue(viewModel.uiState.value.canSave)
        assertTrue(viewModel.uiState.value.canTestConnection)
        assertFalse(viewModel.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun rejectsInvalidBaseUrlWithZeroSavesOrNetworkCalls() = runBlocking {
        val store = FakeSettingsStore()
        val networkCalls = AtomicInteger()
        val viewModel = SettingsViewModel(
            app = app,
            repository = store,
            apiClient = OpenAiApiClient(
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
        ).also { viewModelStore.put("invalid-url", it) }

        store.emit(initialSettings())
        shadowOf(Looper.getMainLooper()).idle()

        viewModel.onBaseUrlChanged("ftp://invalid.example/v1")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(BaseUrlValidationResult.InvalidSchemeOrHost, viewModel.uiState.value.urlValidation)
        assertFalse(viewModel.uiState.value.canSave)
        assertFalse(viewModel.uiState.value.canTestConnection)

        viewModel.saveSettings()
        viewModel.testConnection()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, store.saveCount)
        assertEquals(0, networkCalls.get())

        viewModel.onBaseUrlChanged("https://valid.example/v1")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.uiState.value.canSave)
        assertTrue(viewModel.uiState.value.canTestConnection)

        val testResult = CompletableDeferred<String>()
        val collector = launch(Dispatchers.Main.immediate) {
            viewModel.messageFlow.collect { testResult.complete(it) }
        }
        viewModel.testConnection()
        withTimeout(5_000) {
            while (!testResult.isCompleted || viewModel.uiState.value.isTestingConnection) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        collector.cancel()
        assertEquals(app.getString(R.string.msg_connection_test_success), testResult.await())
        assertEquals(1, networkCalls.get())
    }

    @Test
    fun delayedSavePreservesConcurrentEditsAndDiscardDialogState() = runBlocking {
        val saveStarted = CompletableDeferred<Unit>()
        val allowSaveFinish = CompletableDeferred<Unit>()
        val store = FakeSettingsStore(
            onSave = {
                saveStarted.complete(Unit)
                allowSaveFinish.await()
            }
        )
        val viewModel = SettingsViewModel(
            app = app,
            repository = store,
            apiClient = OpenAiApiClient()
        ).also { viewModelStore.put("delayed-save", it) }

        store.emit(initialSettings())
        shadowOf(Looper.getMainLooper()).idle()

        viewModel.onApiKeyChanged("sk-updated")
        viewModel.onProtocolModelChanged("gpt-4o")
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)

        // Discard dialog opens only when dirty and can be dismissed.
        viewModel.requestDiscardDialog()
        assertTrue(viewModel.uiState.value.showDiscardDialog)
        viewModel.dismissDiscardDialog()
        assertFalse(viewModel.uiState.value.showDiscardDialog)

        val messages = mutableListOf<String>()
        val collector = launch(Dispatchers.Main.immediate) {
            viewModel.messageFlow.collect { messages += it }
        }

        viewModel.saveSettings()
        shadowOf(Looper.getMainLooper()).idle()
        saveStarted.await()
        assertTrue(viewModel.uiState.value.isSaving)

        // User makes a newer edit while save is in flight.
        viewModel.onProtocolModelChanged("gpt-4.1-mini")
        allowSaveFinish.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        collector.cancel()

        assertEquals(1, store.saveCount)
        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals("gpt-4o", viewModel.uiState.value.baseline?.protocolModel)
        assertEquals("gpt-4.1-mini", viewModel.uiState.value.draft.protocolModel)
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
        assertEquals(listOf(app.getString(R.string.msg_settings_saved)), messages)

        // Saving again without concurrent edits clears hasUnsavedChanges.
        viewModel.saveSettings()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, store.saveCount)
        assertEquals("gpt-4.1-mini", viewModel.uiState.value.baseline?.protocolModel)
        assertFalse(viewModel.uiState.value.hasUnsavedChanges)
    }

    private fun initialSettings() = ApiSettings(
        baseUrl = "https://initial.example/v1/",
        apiKey = "sk-initial",
        transcriptionMode = TranscriptionMode.AUTO,
        transcriptionModel = "gpt-4o-mini-transcribe",
        protocolModel = "gpt-4o-mini",
        transcriptionPrompt = "Default transcribe",
        protocolPrompt = "Default protocol"
    )

    private class FakeSettingsStore(
        private val onSave: (suspend () -> Unit)? = null
    ) : SettingsStore {
        private val flow = MutableSharedFlow<ApiSettings>(replay = 1)
        override val settingsFlow: Flow<ApiSettings> = flow
        var saveCount = 0
            private set

        suspend fun emit(settings: ApiSettings) {
            flow.emit(settings)
        }

        override suspend fun saveSettings(
            baseUrl: String,
            apiKey: String,
            transcriptionMode: TranscriptionMode,
            transcriptionModel: String,
            protocolModel: String,
            transcriptionPrompt: String,
            protocolPrompt: String
        ) {
            onSave?.invoke()
            saveCount++
            flow.emit(
                ApiSettings(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    transcriptionMode = transcriptionMode,
                    transcriptionModel = transcriptionModel,
                    protocolModel = protocolModel,
                    transcriptionPrompt = transcriptionPrompt,
                    protocolPrompt = protocolPrompt
                )
            )
        }
    }
}
