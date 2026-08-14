package com.soundscape.app

import android.app.Application
import com.soundscape.usb.UsbAudioManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SoundscapeApplication : Application() {

    @Inject lateinit var usbAudioManager: UsbAudioManager

    override fun onCreate() {
        super.onCreate()
        // Start watching for USB DAC attach/detach as early as possible —
        // PlaybackEngineRouter checks usbAudioManager.devices.value on every play().
        usbAudioManager.start()
    }
}
