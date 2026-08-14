package com.soundscape.audio.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.soundscape.usb.UsbAudioDevice

/**
 * Resolves a [UsbAudioDevice] (from `UsbManager`/[com.soundscape.usb.UsbAudioManager])
 * to the [AudioDeviceInfo.getId] AAudio's `setDeviceId()` actually wants
 * — closing the Phase 1 gap where `AAudioExclusiveEngine` hardcoded
 * `usbDeviceId = 0` (system default) instead of routing to the specific
 * attached DAC.
 *
 * HONEST LIMITATION: Android's `AudioDeviceInfo` API does not expose a
 * device's raw USB vendor/product ID — only [AudioDeviceInfo.getType]
 * and [AudioDeviceInfo.getProductName]. So this matches by product
 * name against the [UsbAudioDevice] the descriptor parser already
 * found, which is the best the public API allows here. That heuristic
 * can misfire with two identical-model DACs attached simultaneously
 * (picks whichever `AudioManager` lists first) — a real platform gap,
 * not a bug in this matching logic. Falls back to device id `0`
 * (system default output) when no match is found, same as before this
 * fix existed.
 */
object UsbDeviceResolver {

    private val usbTypes = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    fun resolveDeviceId(context: Context, usbAudioDevice: UsbAudioDevice): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val match = outputs.firstOrNull { info ->
            info.type in usbTypes && info.productName?.toString() == usbAudioDevice.productName
        } ?: outputs.firstOrNull { info -> info.type in usbTypes } // any USB output, better than none

        return match?.id ?: 0
    }
}
