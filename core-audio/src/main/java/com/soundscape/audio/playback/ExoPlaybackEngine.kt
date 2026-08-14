package com.soundscape.audio.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExoPlaybackEngine @Inject constructor(
    context: Context
) : PlaybackEngine {

    private val player = ExoPlayer.Builder(context).build()

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.update {
                        it.copy(durationMs = player.duration.coerceAtLeast(0))
                    }
                }
            }
        })
    }

    override fun play(track: PlayableTrack) {
        player.setMediaItem(MediaItem.fromUri(track.uri))
        player.prepare()
        player.play()
        _state.update {
            it.copy(
                currentTrack = track,
                // Phase 0 note: ExoPlayer may resample via the platform mixer —
                // this engine never claims bit-perfect. See PlaybackEngine kdoc.
                isBitPerfectConfirmed = false,
                activeSampleRateHz = null,
                activeBitDepth = null
            )
        }
    }

    override fun pause() = player.pause()
    override fun resume() = player.play()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun stop() {
        player.stop()
        _state.update { PlaybackState() }
    }

    override fun release() = player.release()
}
