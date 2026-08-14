package com.soundscape.dsp

/**
 * JNI surface for dsp_jni_bridge.cpp. [FilterType]'s ordinal order MUST
 * match `enum class FilterType` in dsp_chain.h exactly — the native side
 * casts a plain int to that enum, there's no name-based mapping.
 */
enum class FilterType { PEAKING, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS }

object DspBridge {

    init {
        System.loadLibrary("soundscape_dsp")
    }

    external fun prepare(sampleRate: Double, channelCount: Int)

    external fun configureBand(
        index: Int, enabled: Boolean, filterType: Int,
        freqHz: Double, gainDb: Double, q: Double
    )

    external fun setCrossfeedAmount(amount: Float)

    external fun isActive(): Boolean

    /** Mutates [buffer] in place — little-endian float32 interleaved samples. */
    external fun processFloatBuffer(buffer: ByteArray, frameCount: Int)

    external fun release()
}
