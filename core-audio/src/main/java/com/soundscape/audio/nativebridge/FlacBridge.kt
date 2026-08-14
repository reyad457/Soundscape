package com.soundscape.audio.nativebridge

/**
 * JNI surface for flac_jni_decoder.cpp. This class's methods are called
 * FROM native code (onFormatKnown/onPcmFrame are JNI callback targets,
 * not just Kotlin-side helpers) — do not rename without updating the
 * GetMethodID signature strings in flac_jni_decoder.cpp too.
 */
class FlacBridge(
    private val onFormat: (sampleRateHz: Int, channels: Int, bitsPerSample: Int) -> Unit,
    private val onFrame: (interleavedSamples: IntArray, frameCount: Int) -> Unit
) {
    companion object {
        init {
            System.loadLibrary("soundscape_audio")
        }
    }

    /** Blocks the calling thread until decode finishes or the stream errors out. */
    external fun decodeFd(fd: Int, startPositionMs: Long): Boolean

    // Called from native code — see flac_jni_decoder.cpp's writeCallback/metadataCallback.
    @Suppress("unused")
    private fun onFormatKnown(sampleRateHz: Int, channels: Int, bitsPerSample: Int) {
        onFormat(sampleRateHz, channels, bitsPerSample)
    }

    @Suppress("unused")
    private fun onPcmFrame(interleavedSamples: IntArray, frameCount: Int) {
        onFrame(interleavedSamples, frameCount)
    }
}
