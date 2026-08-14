package com.soundscape.audio.playback

import com.soundscape.usb.UsbAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [PlaybackEngine] the rest of the app actually talks to.
 *
 * Tries [AAudioExclusiveEngine] first whenever a permitted, recognized USB
 * audio device is attached — but AAudio exclusive-mode opens can fail for
 * reasons only discoverable at open time (driver quirks, another app
 * already holding the device, etc.), so every attempt is given a short
 * window to prove it actually started playing before falling back to
 * [ExoPlaybackEngine]. This keeps "bit-perfect when possible, always
 * playable otherwise" as a router-level guarantee rather than something
 * every call site has to know about.
 */
@Singleton
class PlaybackEngineRouter @Inject constructor(
    private val aaudioEngine: AAudioExclusiveEngine,
    private val exoEngine: ExoPlaybackEngine,
    private val usbAudioManager: UsbAudioManager
) : PlaybackEngine {

    private val routerScope = CoroutineScope(Dispatchers.Default + Job())

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state

    private var activeEngine: PlaybackEngine = exoEngine

    init {
        // Mirror whichever engine is currently active into our own state flow.
        routerScope.launch {
            while (true) {
                val current = activeEngine.state.value
                if (current != _state.value) _state.value = current
                kotlinx.coroutines.delay(50)
            }
        }
    }

    override fun play(track: PlayableTrack) {
        val hasUsableUsbDac = usbAudioManager.devices.value.any {
            it.hasPermission && it.isRecognizedAudioInterface
        }

        if (!hasUsableUsbDac) {
            activeEngine = exoEngine
            exoEngine.play(track)
            return
        }

        routerScope.launch {
            aaudioEngine.play(track)

            val startedExclusively = withTimeoutOrNull(2_000) {
                aaudioEngine.state.first { it.isPlaying || it.currentTrack != track }
            }

            activeEngine = if (startedExclusively != null && aaudioEngine.state.value.isPlaying) {
                aaudioEngine
            } else {
                aaudioEngine.stop()
                exoEngine.play(track)
                exoEngine
            }
        }
    }

    override fun pause() = activeEngine.pause()
    override fun resume() = activeEngine.resume()
    override fun seekTo(positionMs: Long) = activeEngine.seekTo(positionMs)
    override fun stop() = activeEngine.stop()

    override fun release() {
        aaudioEngine.release()
        exoEngine.release()
        routerScope.coroutineContext[Job]?.cancel()
    }
}
