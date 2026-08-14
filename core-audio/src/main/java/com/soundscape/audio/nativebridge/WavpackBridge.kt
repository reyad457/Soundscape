package com.soundscape.audio.nativebridge

/**
 * JNI surface for wavpack_jni_decoder.cpp. Same shape as [FlacBridge]:
 * onFormatKnown/onPcmFrame are called FROM native code, not just Kotlin
 * helpers — keep signatures in sync with the GetMethodID calls in
 * wavpack_jni_decoder.cpp if either side changes.
 */
class WavpackBridge(
    private val onFormat: (sampleRateHz: Int, channels: Int, bitsPerSample: Int) -> Unit,
    private val onFrame: (interleavedSamples: IntArray, frameCount: Int) -> Unit
) {
    companion object {
        init {
            System.loadLibrary("soundscape_audio")
        }
    }

    /**
     * Blocks until decode finishes. Returns false if the file couldn't be
     * opened, isn't lossless (see wavpack_jni_decoder.cpp — hybrid/lossy
     * .wv files deliberately fall back to MediaCodec instead), or errored.
     */
    external fun decodeFd(fd: Int, startPositionMs: Long): Boolean

    @Suppress("unused")
    private fun onFormatKnown(sampleRateHz: Int, channels: Int, bitsPerSample: Int) {
        onFormat(sampleRateHz, channels, bitsPerSample)
    }

    @Suppress("unused")
    private fun onPcmFrame(interleavedSamples: IntArray, frameCount: Int) {
        onFrame(interleavedSamples, frameCount)
    }
}
