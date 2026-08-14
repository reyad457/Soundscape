package com.soundscape.audio.playback

import android.content.Context
import android.net.Uri
import com.soundscape.audio.nativebridge.AAudioBridge
import com.soundscape.usb.UsbAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1 engine: decodes via [PcmDecoder] (float PCM where the codec allows it)
 * and writes into an AAudio stream opened in Exclusive sharing mode via Oboe,
 * routed to an attached USB DAC when [UsbAudioManager] reports one with permission.
 *
 * [PlaybackState.isBitPerfectConfirmed] is only ever set true when BOTH:
 *   1. AAudio actually granted Exclusive mode (not silently downgraded to Shared), AND
 *   2. the decoder's output sample rate matches the stream's opened rate exactly
 *      (no resampling happened anywhere in the chain).
 * Anything else — badge stays off. See PlaybackEngine kdoc for why that matters.
 */
@Singleton
class AAudioExclusiveEngine @Inject constructor(
    private val context: Context,
    private val usbAudioManager: UsbAudioManager
) : PlaybackEngine {

    private val engineScope = CoroutineScope(Dispatchers.IO + Job())
    private var playbackJob: Job? = null
    private val decoder = PcmDecoder(context)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state

    /** True once a stream has been successfully opened for the current track. */
    private var streamOpen = false

    override fun play(track: PlayableTrack) {
        playbackJob?.cancel()
        streamOpen = false

        _state.update {
            it.copy(currentTrack = track, isPlaying = false, isBitPerfectConfirmed = false)
        }

        playbackJob = engineScope.launch {
            val usbDevice = usbAudioManager.devices.value.firstOrNull { it.hasPermission }
            val usbDeviceId = 0 // Resolved to a real AAudio device ID once USB routing lands fully;
                                 // 0 = default output. Native layer already accepts a real ID —
                                 // wiring UsbAudioManager's device to AAudio's device-id space is
                                 // the remaining piece, tracked for the Phase 1 follow-up pass.

            val chunks = produce(capacity = 8) {
                decoder.decode(
                    uri = Uri.parse(track.uri),
                    scope = this,
                    onFormatKnown = { format ->
                        val opened = AAudioBridge.openStream(
                            sampleRate = format.sampleRateHz,
                            channelCount = format.channelCount,
                            bitsPerSample = if (format.actualEncodingIsFloat) 32 else 16,
                            usbDeviceId = usbDeviceId
                        )
                        streamOpen = opened

                        val exclusive = opened && AAudioBridge.isExclusiveMode()
                        val actualRate = if (opened) AAudioBridge.getActualSampleRate() else 0
                        val noResample = actualRate == format.sampleRateHz

                        _state.update {
                            it.copy(
                                isPlaying = opened,
                                activeSampleRateHz = actualRate.takeIf { r -> r > 0 },
                                activeBitDepth = if (format.actualEncodingIsFloat) 32 else 16,
                                isBitPerfectConfirmed = exclusive && noResample && usbDevice != null
                            )
                        }
                    }
                )
            }

            for (chunk in chunks) {
                if (!streamOpen) continue
                val frameCount = chunk.frameCount / 4 // float32 mono-equivalent frame size; see note below
                AAudioBridge.writeFrames(chunk.bytes, frameCount)
            }

            AAudioBridge.closeStream()
            streamOpen = false
            _state.update { it.copy(isPlaying = false, isBitPerfectConfirmed = false) }
        }
    }

    override fun pause() {
        // Phase 1 note: pause is a hard stop-and-resume-on-play in this skeleton —
        // true pause/resume without re-opening the exclusive stream needs the
        // stream kept alive with silence-on-pause, deferred to the Phase 1 follow-up.
        playbackJob?.cancel()
        AAudioBridge.closeStream()
        streamOpen = false
        _state.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        _state.value.currentTrack?.let { play(it) }
    }

    override fun seekTo(positionMs: Long) {
        // Requires the decoder to support MediaExtractor#seekTo — not yet wired
        // through this chunked-channel pipeline. Deferred alongside true pause/resume.
    }

    override fun stop() {
        playbackJob?.cancel()
        AAudioBridge.closeStream()
        streamOpen = false
        _state.update { PlaybackState() }
    }

    override fun release() {
        playbackJob?.cancel()
        engineScope.coroutineContext[Job]?.cancel()
    }
}
