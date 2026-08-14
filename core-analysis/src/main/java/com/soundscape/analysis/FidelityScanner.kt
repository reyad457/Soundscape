package com.soundscape.analysis

import android.content.Context
import android.net.Uri
import com.soundscape.audio.playback.AlacNativeDecoder
import com.soundscape.audio.playback.ApeNativeDecoder
import com.soundscape.audio.playback.FlacNativeDecoder
import com.soundscape.audio.playback.PcmDecoder
import com.soundscape.audio.playback.WavpackNativeDecoder
import com.soundscape.library.model.AudioFormat
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * Offline "scan this track" analysis — runs a FULL decode (not the
 * playback path) through libebur128 for integrated loudness (LUFS),
 * loudness range (LRA), and true peak (dBTP). Reuses the exact same
 * native decoders [com.soundscape.audio.playback.AAudioExclusiveEngine]
 * uses for playback, since decoding a file correctly is decoding a
 * file correctly regardless of what happens to the samples afterward —
 * no reason to duplicate that logic here.
 *
 * NOT done in this pass (Phase 4's first slice): fake-lossless
 * detection (comparing actual frequency-content cutoff against the
 * claimed sample rate) and spectrogram rendering. Both need an FFT
 * engine, which nothing in this codebase has yet — a real dependency,
 * not a small addition, tracked separately rather than rushed in
 * alongside loudness scanning just because both are "Phase 4."
 *
 * DSD/DoP tracks are NOT scannable here — DoP-packed bytes aren't real
 * PCM, feeding them through a loudness algorithm would produce a
 * meaningless number, not just an inaccurate one. [scan] throws for
 * those rather than silently returning garbage.
 */
class FidelityScanner @Inject constructor(private val context: Context) {

    data class FidelityResult(
        val integratedLoudnessLufs: Double,
        val loudnessRangeLu: Double,
        val truePeakDbtp: Double
    )

    private val mediaCodecDecoder = PcmDecoder(context)
    private val flacDecoder = FlacNativeDecoder(context)
    private val alacDecoder = AlacNativeDecoder(context)
    private val wavpackDecoder = WavpackNativeDecoder(context)
    private val apeDecoder = ApeNativeDecoder(context)

    suspend fun scan(uri: Uri, format: AudioFormat?): FidelityResult {
        require(format != AudioFormat.DSF && format != AudioFormat.DFF) {
            "FidelityScanner doesn't support DSD/DoP tracks — DoP-packed bytes aren't real PCM"
        }

        var channelCount = 0
        var initialized = false
        // Captured from onFormat, same as AAudioExclusiveEngine's chunkIsFloat —
        // chunk.bytes' packing (Int16 vs Float32) isn't self-describing from size
        // alone (an Int16 buffer's byte length is very often also divisible by 4),
        // so this MUST come from the decoder's own reported format, not be guessed.
        var chunkIsFloat = false

        return coroutineScope {
            val onFormat: (PcmDecoder.DecodedFormat) -> Unit = { decoded ->
                channelCount = decoded.channelCount
                chunkIsFloat = decoded.actualEncodingIsFloat
                initialized = LoudnessBridge.init(decoded.sampleRateHz, decoded.channelCount)
            }

            val chunks = produce {
                when (format) {
                    AudioFormat.FLAC -> flacDecoder.decode(uri, this, onFormatKnown = onFormat)
                    AudioFormat.ALAC -> alacDecoder.decode(uri, this, onFormatKnown = onFormat)
                    AudioFormat.APE -> apeDecoder.decode(uri, this, onFormatKnown = onFormat)
                    AudioFormat.WAVPACK ->
                        try {
                            wavpackDecoder.decode(uri, this, onFormatKnown = onFormat)
                        } catch (e: IllegalStateException) {
                            // Same hybrid/lossy fallback AAudioExclusiveEngine uses for playback.
                            mediaCodecDecoder.decode(uri, this, onFormatKnown = onFormat)
                        }
                    else -> mediaCodecDecoder.decode(uri, this, onFormatKnown = onFormat)
                }
            }

            for (chunk in chunks) {
                if (!initialized) continue
                val floats = toFloatArray(chunk.bytes, chunkIsFloat)
                LoudnessBridge.addFrames(floats, chunk.frameCount)
            }

            val result = FidelityResult(
                integratedLoudnessLufs = LoudnessBridge.getIntegratedLoudness(),
                loudnessRangeLu = LoudnessBridge.getLoudnessRange(),
                truePeakDbtp = LoudnessBridge.getTruePeakDbtp(channelCount.coerceAtLeast(1))
            )
            LoudnessBridge.release()
            result
        }
    }

    /**
     * Chunks arrive Int16- or Float32-packed depending on what the
     * source decoder naturally produced (same packing convention as
     * SamplePacking in core-audio, which is `internal` to that module —
     * rather than widen its visibility for one external caller, this is
     * a small local duplicate of the same conversion logic).
     */
    private fun toFloatArray(bytes: ByteArray, isFloat: Boolean): FloatArray {
        return if (isFloat) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(buffer.remaining()).also { buffer.get(it) }
        } else {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / 2) { buffer.getShort(it * 2) / 32768f }
        }
    }
}
