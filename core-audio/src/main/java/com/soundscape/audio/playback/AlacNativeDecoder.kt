package com.soundscape.audio.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.soundscape.audio.nativebridge.AlacBridge
import kotlinx.coroutines.channels.ProducerScope
import java.nio.ByteBuffer

/**
 * Bit-perfect ALAC decode: MediaExtractor demuxes the MP4/M4A container
 * (mature, well-tested — no reason to reimplement that), the vendored
 * Apple reference decoder (see core-audio/src/main/cpp/alac/) does the
 * actual sample decoding, bypassing whatever ALAC support the platform's
 * own MediaCodec may or may not have.
 *
 * Matches [PcmDecoder]/[FlacNativeDecoder]'s producer-channel contract.
 */
class AlacNativeDecoder(private val context: Context) {

    suspend fun decode(
        uri: Uri,
        scope: ProducerScope<PcmDecoder.DecodedChunk>,
        onFormatKnown: (PcmDecoder.DecodedFormat) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) == "audio/alac"
        } ?: throw IllegalArgumentException("No ALAC track found in $uri")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)

        val magicCookie = format.getByteBuffer("csd-0")
            ?: throw IllegalStateException("ALAC track missing csd-0 (magic cookie) for $uri")
        val cookieBytes = ByteArray(magicCookie.remaining())
        magicCookie.get(cookieBytes)

        if (!AlacBridge.init(cookieBytes)) {
            throw IllegalStateException("ALACDecoder::Init failed for $uri — see Logcat SoundscapeAlac tag")
        }

        val sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val bitsPerSample = AlacBridge.getBitDepth()

        onFormatKnown(
            PcmDecoder.DecodedFormat(
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                actualEncodingIsFloat = bitsPerSample > 16
            )
        )

        val packetBuffer = ByteBuffer.allocate(1 shl 20) // 1MB — generous for one ALAC access unit

        try {
            while (scope.isActive) {
                packetBuffer.clear()
                val sampleSize = extractor.readSampleData(packetBuffer, 0)
                if (sampleSize < 0) break // end of stream

                val packetBytes = ByteArray(sampleSize)
                packetBuffer.get(packetBytes, 0, sampleSize)

                val interleaved = AlacBridge.decodePacket(packetBytes, sampleSize)
                if (interleaved != null) {
                    val frameCount = interleaved.size / channelCount
                    val packed = SamplePacking.pack(interleaved, interleaved.size, bitsPerSample)
                    scope.send(PcmDecoder.DecodedChunk(packed, frameCount))
                }

                extractor.advance()
            }
        } finally {
            AlacBridge.release()
            extractor.release()
        }
    }
}
