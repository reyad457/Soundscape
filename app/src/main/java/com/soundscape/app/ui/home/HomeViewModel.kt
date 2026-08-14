package com.soundscape.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.audio.playback.PlayableTrack
import com.soundscape.audio.playback.PlaybackEngine
import com.soundscape.audio.playback.PlaybackState
import com.soundscape.dsp.ParametricEq
import com.soundscape.library.data.TrackDao
import com.soundscape.library.model.Track
import com.soundscape.library.scanner.MediaStoreScanner
import com.soundscape.usb.UsbAudioDevice
import com.soundscape.usb.UsbAudioManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val scanner: MediaStoreScanner,
    private val trackDao: TrackDao,
    private val playbackEngine: PlaybackEngine,
    private val usbAudioManager: UsbAudioManager,
    private val eq: ParametricEq
) : ViewModel() {

    val tracks: StateFlow<List<Track>> = trackDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playback: StateFlow<PlaybackState> = playbackEngine.state

    val usbDevices: StateFlow<List<UsbAudioDevice>> = usbAudioManager.devices

    private val eqPresetState = MutableStateFlow(ParametricEq.Preset.FLAT)
    val activeEqPreset: StateFlow<ParametricEq.Preset> = eqPresetState

    fun requestUsbPermission(deviceName: String) = usbAudioManager.requestPermission(deviceName)

    /**
     * Cycles Flat -> Warm -> Bright -> Flat. A full EQ editor screen
     * (per-band sliders) is future UI work — this is Phase 3's minimal
     * "prove the DSP chain actually does something audible" hook, same
     * scope as Phase 1's USB banner.
     */
    fun cycleEqPreset() {
        val next = when (eqPresetState.value) {
            ParametricEq.Preset.FLAT -> ParametricEq.Preset.WARM
            ParametricEq.Preset.WARM -> ParametricEq.Preset.BRIGHT
            ParametricEq.Preset.BRIGHT -> ParametricEq.Preset.FLAT
        }
        eq.applyPreset(next)
        eqPresetState.value = next
        // Changing presets mid-track needs a re-open of the AAudio stream
        // (bit depth/float decision was made at play() time), so this
        // restarts via seekTo() — but PlaybackState.positionMs isn't
        // actually kept updated during playback yet (a pre-existing gap,
        // not something this preset feature introduced), so in practice
        // this currently restarts the track from 0 rather than resuming
        // from where it was. Real position tracking is future work.
        playback.value.let { state ->
            if (state.currentTrack != null) playbackEngine.seekTo(state.positionMs)
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch {
            val found = scanner.scanLocal()
            trackDao.upsertAll(found)
        }
    }

    fun play(track: Track) {
        playbackEngine.play(
            PlayableTrack(
                id = track.id,
                uri = track.sourceUri,
                title = track.title,
                artist = track.artist,
                format = track.format
            )
        )
    }

    fun togglePlayPause() {
        val state = playback.value
        if (state.isPlaying) playbackEngine.pause() else playbackEngine.resume()
    }
}
