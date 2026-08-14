package com.soundscape.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.audio.playback.PlayableTrack
import com.soundscape.audio.playback.PlaybackEngine
import com.soundscape.audio.playback.PlaybackState
import com.soundscape.library.data.TrackDao
import com.soundscape.library.model.Track
import com.soundscape.library.scanner.MediaStoreScanner
import com.soundscape.usb.UsbAudioDevice
import com.soundscape.usb.UsbAudioManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val scanner: MediaStoreScanner,
    private val trackDao: TrackDao,
    private val playbackEngine: PlaybackEngine,
    private val usbAudioManager: UsbAudioManager
) : ViewModel() {

    val tracks: StateFlow<List<Track>> = trackDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playback: StateFlow<PlaybackState> = playbackEngine.state

    val usbDevices: StateFlow<List<UsbAudioDevice>> = usbAudioManager.devices

    fun requestUsbPermission(deviceName: String) = usbAudioManager.requestPermission(deviceName)

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
