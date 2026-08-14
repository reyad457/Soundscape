package com.soundscape.audio.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.channels.ProducerScope
import java.nio.ByteBuffer

/**
 * Decodes a track to raw PCM using the platform's MediaCodec, requesting
 * float output so 24-bit-and-above sources aren't truncated to 16-bit
 * before they ever reach the DAC.
 *
 * Honesty note (see Soundscape-Master-Plan.md Phase 2): not every codec/
 * device actually honors KEY_PCM_ENCODING = ENCODING_PCM_FLOAT — some
 * silently hand back 16-bit anyway. [DecodedFormat.actualEncodingIsFloat]
 * reports what we actually got, and [AAudioExclusiveEngine] uses that
 * (not the request) to decide the real bit depth for the bit-perfect
 * badge. True guaranteed bit-perfect for every format is what the
 * format-specific decoders (libFLAC, libwavpack, etc.) in Phase 2 are for —
 * this MediaCodec path is the Phase 1 stopgap that already covers
 * FLAC/WAV/ALAC/AAC/MP3/Opus reasonably well on modern Android.
 */
class PcmDecoder(private val context: Context) {

    data class DecodedFormat(
        val sampleRateHz: Int,
        val channelCount: Int,
        val actualEncodingIsFloat: Boolean
    )

    class DecodedChunk(val bytes: ByteArray, val frameCount: Int)

    suspend fun decode(
        uri: Uri,
        scope: ProducerScope<DecodedChunk>,
        onFormatKnown: (DecodedFormat) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track found in $uri")

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

        // Ask for float output. Not every decoder honors this — we verify
        // via the actual output format once the codec is running, below.
        inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var formatReported = false
        var bytesPerSample = 2  // updated once the real output format is known
        var channelCount = 1

        try {
            while (scope.isActive) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer: ByteBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!formatReported) {
                            val outFormat = codec.outputFormat
                            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val isFloat = runCatching {
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                            bytesPerSample = if (isFloat) 4 else 2

                            onFormatKnown(
                                DecodedFormat(
                                    sampleRateHz = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    channelCount = channelCount,
                                    actualEncodingIsFloat = isFloat
                                )
                            )
                            formatReported = true
                        }
                    }
                    outIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(chunk)
                            // frameCount is real audio frames (samples per channel), matching
                            // FlacNativeDecoder's DecodedChunk contract — not a byte count.
                            val frameCount = chunk.size / (bytesPerSample * channelCount)
                            scope.send(DecodedChunk(chunk, frameCount))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }
}
