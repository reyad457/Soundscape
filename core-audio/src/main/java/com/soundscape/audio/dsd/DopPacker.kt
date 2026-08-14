package com.soundscape.audio.dsd

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs raw DSD bytes into DoP (DSD-over-PCM) frames per the publicly
 * published "DoP Open Standard" (v1.1, Playback Designs / dCS) — a
 * public framing spec, not vendored code: groups DSD data into 16-bit
 * words, each placed in the low 16 bits of a 24-bit container, with an
 * 8-bit marker in the next byte up alternating 0x05/0xFA per word
 * (starting 0x05), output at DSD-bit-rate / 16 as the PCM sample rate.
 *
 * HONESTY NOTE — needs real-hardware verification: this packs each DoP
 * word into the low 24 bits of a 32-bit slot (marker in bits 16-23,
 * data in bits 0-15, top byte zero) for Oboe's I32 format. That's the
 * common "right-justified 24-in-32" convention, but some DAC/driver
 * stacks expect left-justified instead. This code has NOT been
 * validated against a real DoP-capable DAC — flip [rightJustified] and
 * re-test if a DAC treats the packed audio as noise instead of DSD.
 */
class DopPacker(
    private val channelCount: Int,
    private val rightJustified: Boolean = true
) {
    private var markerToggle = true // true = 0x05 next, false = 0xFA next

    /**
     * [channelBlocks] is one de-interleaved DSD byte array per channel,
     * all the same length and an even number of bytes (each pair of
     * bytes becomes one DoP word). Returns interleaved Int32 samples,
     * channel-major within each frame, ready for [toBytes] below.
     */
    fun packToInt32(channelBlocks: Array<ByteArray>): IntArray {
        val bytesPerChannel = channelBlocks[0].size
        val wordsPerChannel = bytesPerChannel / 2
        val out = IntArray(wordsPerChannel * channelCount)

        for (w in 0 until wordsPerChannel) {
            val marker = if (markerToggle) 0x05 else 0xFA
            for (c in 0 until channelCount) {
                val hi = channelBlocks[c][w * 2].toInt() and 0xFF
                val lo = channelBlocks[c][w * 2 + 1].toInt() and 0xFF
                val dsdWord = (hi shl 8) or lo
                out[w * channelCount + c] = if (rightJustified) {
                    (marker shl 16) or dsdWord
                } else {
                    ((marker shl 16) or dsdWord) shl 8
                }
            }
            markerToggle = !markerToggle
        }
        return out
    }

    /** Little-endian Int32-per-sample byte packing, matching oboe_engine.cpp's I32 stream format. */
    fun toBytes(samples: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putInt(s)
        return buffer.array()
    }
}
