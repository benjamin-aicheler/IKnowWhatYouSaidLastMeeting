package com.example.lastmeeting.ui.details

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AudioPlaybackControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun asyncPrepareStartsOnlyAfterPreparedCallbackAndIgnoresDuplicateTaps() {
        val players = mutableListOf<FakePlaybackMediaPlayer>()
        val focusGateway = FakeAudioFocusGateway(grantFocus = true)
        val noisyMonitor = FakeNoisyAudioMonitor()
        val controller = AudioPlaybackController(
            scope = scope,
            createPlayer = { FakePlaybackMediaPlayer().also { players.add(it) } },
            audioFocusGateway = focusGateway,
            noisyAudioMonitor = noisyMonitor,
            onPlaybackError = { error -> throw AssertionError("Unexpected error: $error") },
            onAudioFocusDenied = { throw AssertionError("Unexpected focus denial") }
        )

        controller.togglePlayback("/tmp/meeting.m4a")
        controller.togglePlayback("/tmp/meeting.m4a") // Ignored while preparing

        assertEquals(1, players.size)
        val player = players.single()
        assertTrue(player.prepareAsyncCalled)
        assertFalse(player.startCalled)
        assertTrue(controller.isPreparing.value)
        assertFalse(controller.isPlaying.value)

        player.duration = 12_000
        player.triggerPrepared()

        assertFalse(controller.isPreparing.value)
        assertTrue(controller.isPlaying.value)
        assertTrue(player.startCalled)
        assertEquals(12_000, controller.durationMs.value)
        assertTrue(noisyMonitor.registered)
        assertEquals(1, focusGateway.requestCount)
    }

    @Test
    fun focusDenialPreventsPlaybackAndReleasesPlayer() {
        val players = mutableListOf<FakePlaybackMediaPlayer>()
        val focusGateway = FakeAudioFocusGateway(grantFocus = false)
        val noisyMonitor = FakeNoisyAudioMonitor()
        var deniedCount = 0
        val controller = AudioPlaybackController(
            scope = scope,
            createPlayer = { FakePlaybackMediaPlayer().also { players.add(it) } },
            audioFocusGateway = focusGateway,
            noisyAudioMonitor = noisyMonitor,
            onPlaybackError = { error -> throw AssertionError("Unexpected error: $error") },
            onAudioFocusDenied = { deniedCount++ }
        )

        controller.togglePlayback("/tmp/meeting.m4a")
        val player = players.single()
        player.triggerPrepared()

        assertEquals(1, deniedCount)
        assertFalse(player.startCalled)
        assertTrue(player.released)
        assertFalse(controller.isPlaying.value)
        assertFalse(controller.isPreparing.value)
        assertFalse(noisyMonitor.registered)
    }

    @Test
    fun focusLossAndBecomingNoisyPausePlaybackAndAllowExplicitResume() {
        val player = FakePlaybackMediaPlayer(duration = 10_000, currentPosition = 2_500)
        val focusGateway = FakeAudioFocusGateway(grantFocus = true)
        val noisyMonitor = FakeNoisyAudioMonitor()
        val controller = AudioPlaybackController(
            scope = scope,
            createPlayer = { player },
            audioFocusGateway = focusGateway,
            noisyAudioMonitor = noisyMonitor,
            onPlaybackError = { error -> throw AssertionError("Unexpected error: $error") },
            onAudioFocusDenied = { throw AssertionError("Unexpected focus denial") }
        )

        controller.togglePlayback("/tmp/meeting.m4a")
        player.triggerPrepared()
        assertTrue(controller.isPlaying.value)

        focusGateway.emitFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(controller.isPlaying.value)
        assertEquals(1, player.pauseCount)
        assertFalse(noisyMonitor.registered)
        assertEquals(1, focusGateway.abandonCount)

        // Explicit user resume requests focus again.
        controller.togglePlayback("/tmp/meeting.m4a")
        assertTrue(controller.isPlaying.value)
        assertEquals(2, focusGateway.requestCount)
        assertTrue(noisyMonitor.registered)

        // Unplugging headphones pauses playback.
        noisyMonitor.triggerBecomingNoisy()
        assertFalse(controller.isPlaying.value)
        assertEquals(2, player.pauseCount)
        assertFalse(noisyMonitor.registered)
    }

    @Test
    fun seekClampsBoundsAndStopClearsStateAndIgnoresStalePreparedCallbacks() {
        val players = mutableListOf<FakePlaybackMediaPlayer>()
        val focusGateway = FakeAudioFocusGateway(grantFocus = true)
        val noisyMonitor = FakeNoisyAudioMonitor()
        val controller = AudioPlaybackController(
            scope = scope,
            createPlayer = { FakePlaybackMediaPlayer(duration = 5_000).also { players.add(it) } },
            audioFocusGateway = focusGateway,
            noisyAudioMonitor = noisyMonitor,
            onPlaybackError = { error -> throw AssertionError("Unexpected error: $error") },
            onAudioFocusDenied = { throw AssertionError("Unexpected focus denial") }
        )

        controller.togglePlayback("/tmp/meeting.m4a")
        val firstPlayer = players[0]
        // Stopping before prepare completes must prevent late onPrepared from starting audio.
        controller.stop()
        firstPlayer.triggerPrepared()
        assertFalse(firstPlayer.startCalled)
        assertTrue(firstPlayer.released)
        assertFalse(controller.isPlaying.value)

        controller.togglePlayback("/tmp/meeting.m4a")
        val secondPlayer = players[1]
        secondPlayer.triggerPrepared()
        controller.seekTo(99_000)
        assertEquals(5_000, secondPlayer.lastSeekTo)
        assertEquals(5_000, controller.positionMs.value)

        secondPlayer.triggerCompletion()
        assertFalse(controller.isPlaying.value)
        assertEquals(0, controller.durationMs.value)
        assertEquals(0, controller.positionMs.value)
        assertTrue(secondPlayer.stopped)
        assertTrue(secondPlayer.released)
    }

    private class FakePlaybackMediaPlayer(
        override var currentPosition: Int = 0,
        override var duration: Int = 0
    ) : PlaybackMediaPlayer {
        private var onPrepared: (() -> Unit)? = null
        private var onCompletion: (() -> Unit)? = null
        private var onError: ((Int, Int) -> Boolean)? = null

        var prepareAsyncCalled = false
        var startCalled = false
        var pauseCount = 0
        var lastSeekTo: Int? = null
        var stopped = false
        var released = false

        override fun setOnPreparedListener(listener: () -> Unit) {
            onPrepared = listener
        }

        override fun setOnCompletionListener(listener: () -> Unit) {
            onCompletion = listener
        }

        override fun setOnErrorListener(listener: (what: Int, extra: Int) -> Boolean) {
            onError = listener
        }

        override fun setAudioAttributes(attributes: AudioAttributes) = Unit
        override fun setDataSource(path: String) = Unit

        override fun prepareAsync() {
            prepareAsyncCalled = true
        }

        override fun start() {
            startCalled = true
        }

        override fun pause() {
            pauseCount++
        }

        override fun seekTo(msec: Int) {
            lastSeekTo = msec
            currentPosition = msec
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
        }

        fun triggerPrepared() {
            onPrepared?.invoke()
        }

        fun triggerCompletion() {
            onCompletion?.invoke()
        }
    }

    private class FakeAudioFocusGateway(var grantFocus: Boolean) : AudioFocusGateway {
        private var listener: ((Int) -> Unit)? = null
        var requestCount = 0
        var abandonCount = 0

        override fun requestFocus(onFocusChange: (Int) -> Unit): Boolean {
            requestCount++
            if (grantFocus) {
                listener = onFocusChange
                return true
            }
            return false
        }

        override fun abandonFocus() {
            if (listener != null) {
                abandonCount++
                listener = null
            }
        }

        fun emitFocusChange(change: Int) {
            listener?.invoke(change)
        }
    }

    private class FakeNoisyAudioMonitor : NoisyAudioMonitor {
        private var callback: (() -> Unit)? = null
        var registered = false

        override fun register(onBecomingNoisy: () -> Unit) {
            callback = onBecomingNoisy
            registered = true
        }

        override fun unregister() {
            callback = null
            registered = false
        }

        fun triggerBecomingNoisy() {
            callback?.invoke()
        }
    }
}
