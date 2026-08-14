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
}
