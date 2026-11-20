package com.example.lastmeeting.ui.details

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface PlaybackMediaPlayer {
    val currentPosition: Int
    val duration: Int
    fun setOnPreparedListener(listener: () -> Unit)
    fun setOnCompletionListener(listener: () -> Unit)
    fun setOnErrorListener(listener: (what: Int, extra: Int) -> Boolean)
    fun setAudioAttributes(attributes: AudioAttributes)
    fun setDataSource(path: String)
    fun prepareAsync()
    fun start()
    fun pause()
    fun seekTo(msec: Int)
    fun stop()
    fun release()
}

internal interface AudioFocusGateway {
    fun requestFocus(onFocusChange: (Int) -> Unit): Boolean
    fun abandonFocus()
}

internal interface NoisyAudioMonitor {
    fun register(onBecomingNoisy: () -> Unit)
    fun unregister()
}

internal class AndroidPlaybackMediaPlayer(
    private val player: MediaPlayer = MediaPlayer()
) : PlaybackMediaPlayer {
    override val currentPosition: Int get() = player.currentPosition
    override val duration: Int get() = player.duration

    override fun setOnPreparedListener(listener: () -> Unit) {
        player.setOnPreparedListener { listener() }
    }

    override fun setOnCompletionListener(listener: () -> Unit) {
        player.setOnCompletionListener { listener() }
    }

    override fun setOnErrorListener(listener: (what: Int, extra: Int) -> Boolean) {
        player.setOnErrorListener { _, what, extra -> listener(what, extra) }
    }

    override fun setAudioAttributes(attributes: AudioAttributes) {
        player.setAudioAttributes(attributes)
    }

    override fun setDataSource(path: String) {
        player.setDataSource(path)
    }

    override fun prepareAsync() {
        player.prepareAsync()
    }

    override fun start() {
        player.start()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(msec: Int) {
        player.seekTo(msec)
    }

    override fun stop() {
        player.stop()
    }

    override fun release() {
        player.release()
    }
}

internal class AndroidAudioFocusGateway(
    context: Context,
    private val audioAttributes: AudioAttributes
) : AudioFocusGateway {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var currentRequest: AudioFocusRequest? = null

    override fun requestFocus(onFocusChange: (Int) -> Unit): Boolean {
        val manager = audioManager ?: return false
        currentRequest?.let { manager.abandonAudioFocusRequest(it) }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(onFocusChange)
            .build()
        val result = manager.requestAudioFocus(request)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            currentRequest = request
            true
        } else {
            currentRequest = null
            false
        }
    }

    override fun abandonFocus() {
        val request = currentRequest ?: return
        currentRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }
}

internal class AndroidNoisyAudioMonitor(
    context: Context
) : NoisyAudioMonitor {

    private val appContext = context.applicationContext
    private var receiver: BroadcastReceiver? = null

    override fun register(onBecomingNoisy: () -> Unit) {
        unregister()
        val noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    onBecomingNoisy()
                }
            }
        }
        receiver = noisyReceiver
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun unregister() {
        val activeReceiver = receiver ?: return
        receiver = null
        runCatching {
            appContext.unregisterReceiver(activeReceiver)
        }
    }
}

internal class AudioPlaybackController(
    private val scope: CoroutineScope,
    private val createPlayer: () -> PlaybackMediaPlayer,
    private val audioFocusGateway: AudioFocusGateway,
    private val noisyAudioMonitor: NoisyAudioMonitor,
    private val onPlaybackError: (String) -> Unit,
    private val onAudioFocusDenied: () -> Unit,
    private val audioAttributes: AudioAttributes = defaultSpeechAttributes()
) {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing.asStateFlow()

    private val _positionMs = MutableStateFlow(0)
    val positionMs: StateFlow<Int> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private var currentPlayer: PlaybackMediaPlayer? = null
    private var isPrepared = false
    private var isPaused = false
    private var activeSessionToken = 0L
    private var progressJob: Job? = null

    fun togglePlayback(audioFilePath: String) {
        if (_isPreparing.value) return
        if (_isPlaying.value) {
            pause()
            return
        }

        val existingPlayer = currentPlayer
        if (existingPlayer != null && isPrepared && isPaused) {
            resumePreparedPlayer(existingPlayer)
        } else {
            startAsyncPreparation(audioFilePath)
        }
    }

    private fun resumePreparedPlayer(player: PlaybackMediaPlayer) {
        if (!audioFocusGateway.requestFocus(::handleAudioFocusChange)) {
            onAudioFocusDenied()
            return
        }
        noisyAudioMonitor.register(::pause)
        runCatching {
            player.start()
            isPaused = false
            _isPlaying.value = true
            _durationMs.value = player.duration.coerceAtLeast(0)
            _positionMs.value = player.currentPosition.coerceAtLeast(0)
            startProgressUpdates()
        }.onFailure { error ->
            Log.e(TAG, "Audio resume error", error)
            stop()
            onPlaybackError(error.localizedMessage ?: "")
        }
    }

    private fun startAsyncPreparation(audioFilePath: String) {
        stop()
        val sessionToken = ++activeSessionToken
        val player = createPlayer()
        currentPlayer = player
        isPrepared = false
        isPaused = false
        _isPreparing.value = true

        player.setOnPreparedListener {
            if (currentPlayer !== player || activeSessionToken != sessionToken) return@setOnPreparedListener
            _isPreparing.value = false
            isPrepared = true
            if (!audioFocusGateway.requestFocus(::handleAudioFocusChange)) {
                stop()
                onAudioFocusDenied()
                return@setOnPreparedListener
            }
            noisyAudioMonitor.register(::pause)
            runCatching {
                player.start()
                isPaused = false
                _isPlaying.value = true
                _durationMs.value = player.duration.coerceAtLeast(0)
                _positionMs.value = player.currentPosition.coerceAtLeast(0)
                startProgressUpdates()
            }.onFailure { error ->
                Log.e(TAG, "Audio start error", error)
                stop()
                onPlaybackError(error.localizedMessage ?: "")
            }
        }

        player.setOnCompletionListener {
            if (currentPlayer !== player || activeSessionToken != sessionToken) return@setOnCompletionListener
            stop()
        }

        player.setOnErrorListener { what, extra ->
            if (currentPlayer !== player || activeSessionToken != sessionToken) return@setOnErrorListener true
            Log.e(TAG, "MediaPlayer async error what=$what extra=$extra")
            stop()
            onPlaybackError("MediaPlayer error ($what, $extra)")
            true
        }

        try {
            player.setAudioAttributes(audioAttributes)
            player.setDataSource(audioFilePath)
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Audio prepareAsync error", e)
            stop()
            onPlaybackError(e.localizedMessage ?: "")
        }
    }

    fun seekTo(positionMs: Int) {
        val player = currentPlayer ?: return
        if (!isPrepared) return
        val duration = _durationMs.value
        val clamped = if (duration > 0) positionMs.coerceIn(0, duration) else 0
        runCatching {
            player.seekTo(clamped)
            _positionMs.value = clamped
        }.onFailure { error ->
            Log.w(TAG, "Audio seek error", error)
        }
    }

    fun pause() {
        progressJob?.cancel()
        progressJob = null
        val player = currentPlayer
        if (player != null && isPrepared && _isPlaying.value) {
            runCatching {
                player.pause()
                isPaused = true
                _positionMs.value = player.currentPosition.coerceAtLeast(0)
            }.onFailure { error ->
                Log.e(TAG, "Audio pause error", error)
            }
        }
        _isPlaying.value = false
        noisyAudioMonitor.unregister()
        audioFocusGateway.abandonFocus()
    }

    fun stop() {
        activeSessionToken++
        progressJob?.cancel()
        progressJob = null
        val player = currentPlayer
        currentPlayer = null
        val wasPrepared = isPrepared
        isPrepared = false
        isPaused = false
        _isPreparing.value = false
        _isPlaying.value = false
        _positionMs.value = 0
        _durationMs.value = 0
        noisyAudioMonitor.unregister()
        audioFocusGateway.abandonFocus()
        if (player != null) {
            if (wasPrepared) {
                runCatching { player.stop() }
            }
            runCatching { player.release() }
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                val player = currentPlayer ?: break
                runCatching {
                    _positionMs.value = player.currentPosition.coerceAtLeast(0)
                }
                delay(200L)
            }
        }
    }

    companion object {
        private const val TAG = "AudioPlaybackController"

        fun create(
            context: Context,
            scope: CoroutineScope,
            onPlaybackError: (String) -> Unit,
            onAudioFocusDenied: () -> Unit
        ): AudioPlaybackController {
            val attributes = defaultSpeechAttributes()
            return AudioPlaybackController(
                scope = scope,
                createPlayer = { AndroidPlaybackMediaPlayer() },
                audioFocusGateway = AndroidAudioFocusGateway(context, attributes),
                noisyAudioMonitor = AndroidNoisyAudioMonitor(context),
                onPlaybackError = onPlaybackError,
                onAudioFocusDenied = onAudioFocusDenied,
                audioAttributes = attributes
            )
        }

        private fun defaultSpeechAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
