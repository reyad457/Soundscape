package com.soundscape.audio.playback

import android.content.Context
import android.net.Uri
import com.soundscape.audio.nativebridge.AAudioBridge
import com.soundscape.library.model.AudioFormat
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
 * Phase 1/2 engine: decodes via the per-format decoder ([PcmDecoder] for
 * MediaCodec-handled formats, native FLAC/ALAC/WavPack/APE decoders, or
 * [DsdDopDecoder] for DSD-via-DoP) and writes into an AAudio stream
 * opened in Exclusive sharing mode via Oboe, routed to an attached USB
 * DAC (by actual device id — see [UsbDeviceResolver]) when
 * [UsbAudioManager] reports one with permission.
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
    private val mediaCodecDecoder = PcmDecoder(context)
    private val flacDecoder = FlacNativeDecoder(context)
    private val alacDecoder = AlacNativeDecoder(context)
    private val wavpackDecoder = WavpackNativeDecoder(context)
    private val apeDecoder = ApeNativeDecoder(context)
    private val dsdDopDecoder = DsdDopDecoder(context)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state

    /** True once a stream has been successfully opened for the current track. */
    private var streamOpen = false

    /**
     * True while paused with the exclusive stream still open (not
     * closed) — distinguishes "pause() was called, resume() should just
     * un-pause the native stream" from "stop()/decode ended, resume()
     * needs a full play() restart." See [pause]/[resume].
     */
    private var isPaused = false

    override fun play(track: PlayableTrack) = playInternal(track, startPositionMs = 0)

    private fun playInternal(track: PlayableTrack, startPositionMs: Long) {
        playbackJob?.cancel()
        streamOpen = false
        isPaused = false

        _state.update {
            it.copy(currentTrack = track, isPlaying = false, isBitPerfectConfirmed = false)
        }

        playbackJob = engineScope.launch {
            val usbDevice = usbAudioManager.devices.value.firstOrNull { it.hasPermission }
            val usbDeviceId = usbDevice?.let { UsbDeviceResolver.resolveDeviceId(context, it) } ?: 0

            val chunks = produce(capacity = 8) {
                val onFormat: (PcmDecoder.DecodedFormat) -> Unit = { format ->
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
                            // Native FLAC/ALAC/WavPack/APE decoders are a stronger bit-perfect
                            // claim than MediaCodec's best-effort float request — but the badge
                            // still requires exclusive mode AND no resampling regardless of decoder.
                            isBitPerfectConfirmed = exclusive && noResample && usbDevice != null
                        )
                    }
                }

                when (track.format) {
                    AudioFormat.FLAC ->
                        flacDecoder.decode(uri = Uri.parse(track.uri), scope = this, onFormatKnown = onFormat)
                    AudioFormat.ALAC ->
                        alacDecoder.decode(
                            uri = Uri.parse(track.uri), scope = this,
                            startPositionMs = startPositionMs, onFormatKnown = onFormat
                        )
                    AudioFormat.WAVPACK ->
                        try {
                            wavpackDecoder.decode(uri = Uri.parse(track.uri), scope = this, onFormatKnown = onFormat)
                        } catch (e: IllegalStateException) {
                            // Hybrid/lossy .wv files are rejected by design at the native layer
                            // (see wavpack_jni_decoder.cpp) — fall back immediately rather than
                            // waiting on PlaybackEngineRouter's 2s exclusive-mode timeout.
                            mediaCodecDecoder.decode(
                                uri = Uri.parse(track.uri), scope = this,
                                startPositionMs = startPositionMs, onFormatKnown = onFormat
                            )
                        }
                    AudioFormat.APE ->
                        apeDecoder.decode(uri = Uri.parse(track.uri), scope = this, onFormatKnown = onFormat)
                    AudioFormat.DSF, AudioFormat.DFF ->
                        dsdDopDecoder.decode(
                            uri = Uri.parse(track.uri), format = track.format!!, scope = this
                        ) { outSampleRate, channels ->
                            val opened = AAudioBridge.openDopStream(outSampleRate, channels, usbDeviceId)
                            streamOpen = opened
                            val exclusive = opened && AAudioBridge.isExclusiveMode()
                            val actualRate = if (opened) AAudioBridge.getActualSampleRate() else 0
                            _state.update {
                                it.copy(
                                    isPlaying = opened,
                                    activeSampleRateHz = actualRate.takeIf { r -> r > 0 },
                                    activeBitDepth = 24,
                                    isBitPerfectConfirmed = exclusive && actualRate == outSampleRate && usbDevice != null
                                )
                            }
                        }
                    else ->
                        mediaCodecDecoder.decode(
                            uri = Uri.parse(track.uri), scope = this,
                            startPositionMs = startPositionMs, onFormatKnown = onFormat
                        )
                }
            }

            for (chunk in chunks) {
                if (!streamOpen) continue
                AAudioBridge.writeFrames(chunk.bytes, chunk.frameCount)
            }

            AAudioBridge.closeStream()
            streamOpen = false
            _state.update { it.copy(isPlaying = false, isBitPerfectConfirmed = false) }
        }
    }

    override fun pause() {
        if (streamOpen && AAudioBridge.pauseStream()) {
            // Stream stays open (see OboeEngine::pause's kdoc) — the decode
            // loop keeps running but its writeFrames() calls block once the
            // paused stream's internal buffer fills, so it self-throttles
            // without needing an explicit "don't decode further" signal here.
            isPaused = true
            _state.update { it.copy(isPlaying = false) }
        } else {
            // No open stream to pause against (e.g. between tracks) — fall
            // back to the old hard-stop behavior rather than doing nothing.
            playbackJob?.cancel()
            AAudioBridge.closeStream()
            streamOpen = false
            _state.update { it.copy(isPlaying = false) }
        }
    }

    override fun resume() {
        if (isPaused && streamOpen && AAudioBridge.resumeStream()) {
            isPaused = false
            _state.update { it.copy(isPlaying = true) }
        } else {
            // Not in a paused-with-open-stream state — full restart from the
            // beginning is the only option (matches pre-fix Phase 1 behavior
            // for this case, e.g. resume() called after stop() or an error).
            _state.value.currentTrack?.let { play(it) }
        }
    }

    override fun seekTo(positionMs: Long) {
        // Real seek for the MediaCodec-backed formats (WAV/MP3/AAC/OGG/Opus)
        // and ALAC (also MediaExtractor-based) — both accept a start position
        // and call extractor.seekTo() before decoding begins. FLAC/WavPack/APE
        // and DSD do NOT seek yet: each needs its own decoder-specific seek API
        // wired through (FLAC__stream_decoder_seek_absolute, WavpackSeekSample64,
        // CAPEDecompress::Seek, and a data-chunk byte-offset computation for DSD)
        // — four more integration passes, not done in this one. Calling seekTo()
        // on those formats currently just restarts the track from position 0;
        // that's a real known limitation, not silent data corruption, so it's
        // left as-is rather than blocked, but worth fixing before this is "done."
        val track = _state.value.currentTrack ?: return
        playInternal(track, startPositionMs = positionMs)
    }

    override fun stop() {
        playbackJob?.cancel()
        AAudioBridge.closeStream()
        streamOpen = false
        isPaused = false
        _state.update { PlaybackState() }
    }

    override fun release() {
        playbackJob?.cancel()
        engineScope.coroutineContext[Job]?.cancel()
    }
}
