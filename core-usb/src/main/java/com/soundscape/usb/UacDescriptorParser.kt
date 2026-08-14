package com.soundscape.usb

/**
 * Parses raw USB descriptors (as returned by [android.hardware.usb.UsbDeviceConnection.getRawDescriptors])
 * looking for UAC1/UAC2 Audio Streaming interfaces and their FORMAT_TYPE descriptors.
 *
 * This is a Type I PCM-focused parser — enough to answer "what sample
 * rates/bit depths/channel counts does this DAC's streaming interface
 * advertise". It does NOT attempt full UAC2 clock-source/entity-graph
 * parsing (that's a bigger spec surface); DACs that need that will fall
 * back to [UsbAudioDevice.isRecognizedAudioInterface] == false and get
 * routed through the non-exclusive fallback path instead of failing outright.
 *
 * Reference: USB Audio Class 1.0 spec, section 4.5.3 (Type I Format Type Descriptor).
 */
object UacDescriptorParser {

    private const val DESCRIPTOR_TYPE_INTERFACE = 0x04
    private const val DESCRIPTOR_TYPE_CS_INTERFACE = 0x24

    private const val USB_CLASS_AUDIO = 0x01
    private const val SUBCLASS_AUDIO_STREAMING = 0x02

    private const val SUBTYPE_FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01

    data class ParsedCapabilities(
        val sampleRates: List<Int>,
        val bitDepths: List<Int>,
        val maxChannelCount: Int,
        val recognized: Boolean
    )

    fun parse(rawDescriptors: ByteArray): ParsedCapabilities {
        val sampleRates = mutableSetOf<Int>()
        val bitDepths = mutableSetOf<Int>()
        var maxChannels = 0
        var recognized = false

        var inAudioStreamingInterface = false
        var offset = 0

        while (offset + 1 < rawDescriptors.size) {
            val length = rawDescriptors[offset].toInt() and 0xFF
            if (length < 2 || offset + length > rawDescriptors.size) break // malformed, bail safely
            val type = rawDescriptors[offset + 1].toInt() and 0xFF

            when (type) {
                DESCRIPTOR_TYPE_INTERFACE -> {
                    if (length >= 9) {
                        val interfaceClass = rawDescriptors[offset + 5].toInt() and 0xFF
                        val interfaceSubClass = rawDescriptors[offset + 6].toInt() and 0xFF
                        inAudioStreamingInterface =
                            interfaceClass == USB_CLASS_AUDIO && interfaceSubClass == SUBCLASS_AUDIO_STREAMING
                    }
                }

                DESCRIPTOR_TYPE_CS_INTERFACE -> {
                    if (inAudioStreamingInterface && length >= 3) {
                        val subtype = rawDescriptors[offset + 2].toInt() and 0xFF
                        if (subtype == SUBTYPE_FORMAT_TYPE && length >= 8) {
                            val formatType = rawDescriptors[offset + 3].toInt() and 0xFF
                            if (formatType == FORMAT_TYPE_I) {
                                val channels = rawDescriptors[offset + 4].toInt() and 0xFF
                                val bitResolution = rawDescriptors[offset + 6].toInt() and 0xFF
                                maxChannels = maxOf(maxChannels, channels)
                                bitDepths += bitResolution
                                recognized = true

                                val sampleFreqType = rawDescriptors[offset + 7].toInt() and 0xFF
                                if (sampleFreqType == 0) {
                                    // Continuous range: min (3 bytes) + max (3 bytes) follow.
                                    if (length >= 14) {
                                        sampleRates += readTripleByteRate(rawDescriptors, offset + 8)
                                        sampleRates += readTripleByteRate(rawDescriptors, offset + 11)
                                    }
                                } else {
                                    // Discrete list of sampleFreqType * 3-byte rates.
                                    var p = offset + 8
                                    repeat(sampleFreqType) {
                                        if (p + 2 < rawDescriptors.size) {
                                            sampleRates += readTripleByteRate(rawDescriptors, p)
                                        }
                                        p += 3
                                    }
                                }
                            }
                        }
                    }
                }
            }

            offset += length
        }

        return ParsedCapabilities(
            sampleRates = sampleRates.sorted(),
            bitDepths = bitDepths.sorted(),
            maxChannelCount = maxChannels,
            recognized = recognized
        )
    }

    private fun readTripleByteRate(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
    }
}
