package com.soundscape.audio.nativebridge

/**
 * JNI surface for alac_jni_decoder.cpp. Unlike [FlacBridge], this decoder
 * is packet-at-a-time (Kotlin drives the loop via MediaExtractor) rather
 * than "call once, block until EOF" — ALAC has no self-contained
 * container the native layer can parse on its own, so demuxing stays on
 * the Kotlin/MediaExtractor side. See alac_jni_decoder.cpp's file
 * comment for why that split exists.
 */
object AlacBridge {

    init {
        System.loadLibrary("soundscape_audio")
    }

    /** [magicCookie] is the ALACSpecificConfig blob, e.g. from MediaFormat's "csd-0". */
    external fun init(magicCookie: ByteArray): Boolean

    /** Decodes exactly one access unit. Returns null on decode error. */
    external fun decodePacket(packet: ByteArray, packetSize: Int): IntArray?

    external fun getBitDepth(): Int

    external fun release()
}
