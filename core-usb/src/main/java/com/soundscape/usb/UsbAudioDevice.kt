package com.soundscape.usb

/**
 * A USB device that identifies as an Audio Class device (interface class 0x01),
 * with whatever capability info we could parse from its descriptors.
 *
 * [hasPermission] tracks whether the user has granted USB permission for this
 * session — Android requires re-requesting this per attach, it isn't durable
 * like a normal runtime permission.
 */
data class UsbAudioDevice(
    val deviceName: String,          // e.g. "/dev/bus/usb/001/003", used to re-find the device
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
    val hasPermission: Boolean,
    val supportedSampleRates: List<Int>,
    val supportedBitDepths: List<Int>,
    val maxChannelCount: Int,
    /** True if descriptor parsing found a proper UAC Audio Streaming interface. */
    val isRecognizedAudioInterface: Boolean
)
