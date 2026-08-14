package com.soundscape.audio.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract the rest of the app talks to, deliberately kept engine-agnostic.
 *
 * Phase 0: [ExoPlaybackEngine] backs this with standard Media3 ExoPlayer —
 * gets a working app fast, uses the normal Android audio pipeline.
 *
 * Phase 1: a USB-exclusive engine (AAudio/Oboe MMAP mode, or a raw
 * usbdevfs isochronous driver for DACs that need it) will implement this
 * same interface. Nothing above this layer should need to change when
 * that lands — that's the point of the seam.
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>

    fun play(track: PlayableTrack)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
    fun release()
}

data class PlayableTrack(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String
)

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentTrack: PlayableTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Reported once the engine has actually opened the stream — null until then. */
    val activeSampleRateHz: Int? = null,
    val activeBitDepth: Int? = null,
    /**
     * True only when the engine can positively confirm an unbroken bit-perfect
     * path to the DAC (Phase 1+). Always false on the Phase 0 ExoPlayer engine,
     * since the standard Android audio pipeline may resample.
     */
    val isBitPerfectConfirmed: Boolean = false
)
