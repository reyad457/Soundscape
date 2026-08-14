package com.soundscape.audio.playback

import android.content.Context
import android.net.Uri
import com.soundscape.audio.nativebridge.FlacBridge
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * Decodes FLAC via the vendored libFLAC reference decoder (see
 * core-audio/src/main/cpp/flac/ — the actual upstream Xiph source, BSD
 * licensed, not a reimplementation), rather than MediaCodec. This is the
 * real bit-perfect path [PcmDecoder]'s kdoc flags as not guaranteed:
 * every sample here comes from libFLAC's own STREAMINFO-verified decode.
 *
 * Packs samples to match what oboe_engine.cpp expects for the reported
 * bit depth: 16-bit sources go out as raw little-endian Int16 bytes;
 * anything above 16-bit is normalized to Float32 (matching the Float
 * format branch OboeEngine::open() takes for bitsPerSample > 16), since
 * that's the width AAudio's exclusive-mode path actually negotiates
 * cleanly across DAC drivers.
 */
class FlacNativeDecoder(private val context: Context) {

    suspend fun decode(
        uri: Uri,
        scope: ProducerScope<PcmDecoder.DecodedChunk>,
        onFormatKnown: (PcmDecoder.DecodedFormat) -> Unit
    ) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Could not open fd for $uri")

        var channelCount = 0
        var bitsPerSample = 0

        pfd.use { descriptor ->
            suspendCancellableCoroutine<Unit> { cont ->
                val bridge = FlacBridge(
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
                        val packed = pack(interleaved, frameCount * channelCount, bitsPerSample)
                        // FlacBridge calls back on the native decode thread;
                        // trySend is fine here since we're not on scope's own dispatcher.
                        scope.trySend(PcmDecoder.DecodedChunk(packed, frameCount))
                    }
                )

                bridge.decodeFd(descriptor.fd) // blocks the coroutine's IO-dispatcher thread until EOF/error
                cont.resume(Unit)
            }
        }
    }

    /**
     * libFLAC hands back sign-extended Int32 sample values regardless of the
     * source's actual bit depth. Pack to Int16 bytes directly for <=16-bit
     * sources; normalize to Float32 in [-1, 1] for everything above that.
     */
    private fun pack(interleaved: IntArray, sampleCount: Int, bitsPerSample: Int): ByteArray {
        return if (bitsPerSample <= 16) {
            val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) buffer.putShort(interleaved[i].toShort())
            buffer.array()
        } else {
            val fullScale = (1 shl (bitsPerSample - 1)).toFloat()
            val buffer = ByteBuffer.allocate(sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) buffer.putFloat(interleaved[i] / fullScale)
            buffer.array()
        }
    }
}
