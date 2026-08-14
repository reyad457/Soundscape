package com.soundscape.analysis

object LoudnessBridge {

    init {
        System.loadLibrary("soundscape_analysis")
    }

    external fun init(sampleRate: Int, channels: Int): Boolean

    /** [samples] is interleaved float32, [frameCount] is frames (not total samples). */
    external fun addFrames(samples: FloatArray, frameCount: Int)

    /** Integrated (program) loudness in LUFS. */
    external fun getIntegratedLoudness(): Double

    /** Loudness range (LRA) in LU. */
    external fun getLoudnessRange(): Double

    /** Highest true peak across all channels, in dBTP (0 dBTP = full scale). */
    external fun getTruePeakDbtp(channelCount: Int): Double

    external fun release()
}
