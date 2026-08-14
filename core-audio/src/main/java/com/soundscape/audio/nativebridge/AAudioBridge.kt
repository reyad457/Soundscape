package com.soundscape.audio.nativebridge

/**
 * Thin JNI surface over the C++ OboeEngine (see core-audio/src/main/cpp).
 * Every function here maps 1:1 to a `Java_com_soundscape_audio_nativebridge_AAudioBridge_*`
 * symbol — keep the package/object name in sync with jni_bridge.cpp if either moves.
 */
object AAudioBridge {

    init {
        System.loadLibrary("soundscape_audio")
    }

    /** Returns true only if the stream actually opened — check [isExclusiveMode] separately. */
    external fun openStream(sampleRate: Int, channelCount: Int, bitsPerSample: Int, usbDeviceId: Int): Boolean

    /**
     * Opens for DoP (DSD-over-PCM) specifically — Int32 format, no
     * float conversion. See [com.soundscape.audio.dsd.DopPacker]'s kdoc
     * for why DoP can never go through the float path [openStream] uses.
     */
    external fun openDopStream(sampleRate: Int, channelCount: Int, usbDeviceId: Int): Boolean

    /**
     * Pauses the stream WITHOUT closing it — the exclusive-mode claim on
     * the DAC survives. See [com.soundscape.audio.playback.AAudioExclusiveEngine.pause]
     * for why this makes true pause/resume possible instead of Phase 1's
     * original "cancel the coroutine and restart the track" behavior.
     */
    external fun pauseStream(): Boolean

    external fun resumeStream(): Boolean

    /** Blocking write. Returns frames actually written, or -1 on error. */
    external fun writeFrames(pcm: ByteArray, frameCount: Int): Int

    external fun getActualSampleRate(): Int

    /** The one field that matters for the "bit-perfect" badge — never assume, always ask. */
    external fun isExclusiveMode(): Boolean

    external fun closeStream()
}
