package com.soundscape.audio.playback

import android.content.Context
import android.net.Uri
import com.soundscape.audio.nativebridge.WavpackBridge
import kotlinx.coroutines.channels.ProducerScope

/**
 * Decodes WavPack via the vendored libwavpack decode path (BSD-3-Clause,
 * see core-audio/src/main/cpp/wavpack/) — self-contained stream, same
 * fd-driven shape as [FlacNativeDecoder], unlike ALAC's container-demux
 * split. Only lossless `.wv` files take this path; hybrid/lossy WavPack
 * is rejected at the native layer (see wavpack_jni_decoder.cpp) and
 * falls back to MediaCodec via [AAudioExclusiveEngine]'s normal fallback
 * — "bit-perfect where the format can honestly claim it" applies here
 * exactly like everywhere else in this app.
 *
 * [startPositionMs] gives real seek via `WavpackSeekSample64` — unlike
 * FLAC, WavPack's sample rate is available immediately after opening
 * (no metadata-read step needed first), so the native side seeks before
 * ever reporting the format back to Kotlin.
 */
class WavpackNativeDecoder(private val context: Context) {

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
            val bridge = WavpackBridge(
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
                    "WavPack native decode failed or file isn't lossless — see Logcat SoundscapeWavpack tag; " +
                        "caller should fall back to MediaCodec for this track"
                )
            }
        }
    }
}
