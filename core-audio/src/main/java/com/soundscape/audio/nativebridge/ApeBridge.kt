package com.soundscape.audio.nativebridge

/**
 * JNI surface for ape_jni_decoder.cpp. Same shape as [FlacBridge] and
 * [WavpackBridge]: onFormatKnown/onPcmFrame are called FROM native
 * code — keep signatures in sync with the GetMethodID calls in
 * ape_jni_decoder.cpp if either side changes.
 */
class ApeBridge(
    private val onFormat: (sampleRateHz: Int, channels: Int, bitsPerSample: Int) -> Unit,
    private val onFrame: (interleavedSamples: IntArray, frameCount: Int) -> Unit
) {
    companion object {
        init {
            System.loadLibrary("soundscape_audio")
        }
    }

    /** Blocks until decode finishes or the stream errors out. */
    external fun decodeFd(fd: Int): Boolean

    @Suppress("unused")
    private fun onFormatKnown(sampleRateHz: Int, channels: Int, bitsPerSample: Int) {
        onFormat(sampleRateHz, channels, bitsPerSample)
    }

    @Suppress("unused")
    private fun onPcmFrame(interleavedSamples: IntArray, frameCount: Int) {
        onFrame(interleavedSamples, frameCount)
    }
}
