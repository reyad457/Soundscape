package com.soundscape.audio.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Both [FlacNativeDecoder] and [AlacNativeDecoder] hand back interleaved
 * samples as sign-extended Int32 slots (whatever the JNI layer's
 * convention is — see flac_jni_decoder.cpp / alac_jni_decoder.cpp), and
 * both need to pack that into what oboe_engine.cpp actually expects:
 * raw Int16 bytes at <=16-bit, normalized Float32 above that.
 */
internal object SamplePacking {
    fun pack(interleaved: IntArray, sampleCount: Int, bitsPerSample: Int): ByteArray {
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

    /**
     * Used only when the DSP chain is active (see [AAudioExclusiveEngine]):
     * the chain processes float32 regardless of the source's real bit
     * depth, so Int16-packed chunks need converting up before [com.soundscape.dsp.ParametricEq.process]
     * touches them. Chunks that are already float32 pass through
     * [pack]'s own Float32 branch untouched — this is only for the
     * Int16 case.
     */
    fun int16BytesToFloatBytes(int16Bytes: ByteArray, sampleCount: Int): ByteArray {
        val input = ByteBuffer.wrap(int16Bytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) output.putFloat(input.getShort(i * 2) / 32768f)
        return output.array()
    }
}
