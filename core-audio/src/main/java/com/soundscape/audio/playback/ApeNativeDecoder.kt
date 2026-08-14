package com.soundscape.audio.playback

import android.content.Context
import android.net.Uri
import com.soundscape.audio.nativebridge.ApeBridge
import kotlinx.coroutines.channels.ProducerScope

/**
 * Decodes Monkey's Audio (.ape) via the vendored decode-only classes
 * (3-Clause BSD, see core-audio/src/main/cpp/ape/) — self-contained
 * stream like FLAC and WavPack, not a container-demux split like ALAC.
 * Same fd-driven shape and packing contract as [FlacNativeDecoder] and
 * [WavpackNativeDecoder]. [startPositionMs] gives real seek via
 * `CAPEDecompress::Seek` (block offset — Monkey's Audio's "block" is
 * one sample-frame, so block offset per second equals the sample rate).
 */
class ApeNativeDecoder(private val context: Context) {

    suspend fun decode(
        uri: Uri,
        scope: ProducerScope<PcmDecoder.DecodedChunk>,
        startPositionMs: Long = 0,
        onFormatKnown: (PcmDecoder.DecodedFormat) -> Unit
    ) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Could not open fd for $uri")

        var channelCount = 0
        var bitsPerSample = 0

        pfd.use { descriptor ->
            val bridge = ApeBridge(
                onFormat = { sampleRateHz, channels, bits ->
                    channelCount = channels
                    bitsPerSample = bits
                    onFormatKnown(
                        PcmDecoder.DecodedFormat(
                            sampleRateHz = sampleRateHz,
                            channelCount = channels,
                            actualEncodingIsFloat = bits > 16
                        )
                    )
                },
                onFrame = { interleaved, frameCount ->
                    val packed = SamplePacking.pack(interleaved, frameCount * channelCount, bitsPerSample)
                    scope.trySend(PcmDecoder.DecodedChunk(packed, frameCount))
                }
            )

            val ok = bridge.decodeFd(descriptor.fd, startPositionMs)
            if (!ok) {
                throw IllegalStateException(
                    "APE native decode failed — see Logcat SoundscapeApe tag"
                )
            }
        }
    }
}
