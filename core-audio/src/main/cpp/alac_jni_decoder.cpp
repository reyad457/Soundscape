// Bit-perfect ALAC decode using Apple's own reference decoder (vendored,
// Apache 2.0 — see alac/LICENSE-Apache2.txt), the same pattern
// flac_jni_decoder.cpp established for FLAC.
//
// Unlike the FLAC path, this does NOT do its own container parsing.
// ALAC in the wild lives inside an MP4/M4A container, and writing a
// correct MP4 demuxer from scratch is a real project on its own — one
// Android already ships a mature, battle-tested implementation of via
// MediaExtractor. So the split here is: Kotlin uses MediaExtractor
// purely as a demuxer (never as a decoder — ALAC decode support varies
// by OEM and isn't guaranteed bit-perfect even where present) to pull
// out the ALACSpecificConfig "magic cookie" and each access unit's raw
// bytes, and hands both down to this native layer, which does the
// actual sample decoding with Apple's own algorithm.

#include <jni.h>
#include <cstdint>
#include <vector>
#include <android/log.h>
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"

#define LOG_TAG "SoundscapeAlac"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    // One decoder per playback session, same lifetime rationale as
    // OboeEngine's single g_engine — Soundscape decodes one track at a time.
    ALACDecoder* g_decoder = nullptr;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_soundscape_audio_nativebridge_AlacBridge_init(
    JNIEnv* env, jobject /* this */, jbyteArray magicCookie) {

    delete g_decoder;
    g_decoder = new ALACDecoder();

    jsize cookieLen = env->GetArrayLength(magicCookie);
    jbyte* cookieBytes = env->GetByteArrayElements(magicCookie, nullptr);

    int32_t result = g_decoder->Init(reinterpret_cast<void*>(cookieBytes), static_cast<uint32_t>(cookieLen));

    env->ReleaseByteArrayElements(magicCookie, cookieBytes, JNI_ABORT);

    if (result != 0) {
        LOGE("ALACDecoder::Init failed, code %d", result);
        delete g_decoder;
        g_decoder = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Decodes exactly one access unit (one MediaExtractor sample). Returns the
// interleaved PCM as an int array — same "always int32 slots, caller packs
// to the real bit depth" convention flac_jni_decoder.cpp uses, so
// AlacNativeDecoder.kt can reuse FlacNativeDecoder's packing logic.
JNIEXPORT jintArray JNICALL
Java_com_soundscape_audio_nativebridge_AlacBridge_decodePacket(
    JNIEnv* env, jobject /* this */, jbyteArray packet, jint packetSize) {

    if (!g_decoder) return nullptr;

    jbyte* packetBytes = env->GetByteArrayElements(packet, nullptr);

    BitBuffer bits;
    BitBufferInit(&bits, reinterpret_cast<uint8_t*>(packetBytes), static_cast<uint32_t>(packetSize));

    const uint32_t numChannels = g_decoder->mConfig.numChannels;
    const uint32_t frameLength = g_decoder->mConfig.frameLength;
    const uint32_t bitDepth = g_decoder->mConfig.bitDepth;

    // ALACDecoder::Decode writes native-width samples packed per bitDepth
    // (16/20/24/32-bit) into a byte buffer; size generously for the worst case.
    std::vector<uint8_t> sampleBuffer(static_cast<size_t>(frameLength) * numChannels * 4);
    uint32_t outNumSamples = 0;

    int32_t result = g_decoder->Decode(&bits, sampleBuffer.data(), frameLength, numChannels, &outNumSamples);
    env->ReleaseByteArrayElements(packet, packetBytes, JNI_ABORT);

    if (result != 0) {
        LOGE("ALACDecoder::Decode failed, code %d", result);
        return nullptr;
    }

    // Unpack to int32-per-sample-slot, matching FlacBridge's onPcmFrame
    // convention, regardless of the source's actual bit depth.
    const uint32_t totalSamples = outNumSamples * numChannels;
    std::vector<int32_t> unpacked(totalSamples);
    const uint8_t bytesPerSample = (bitDepth + 7) / 8;

    for (uint32_t i = 0; i < totalSamples; i++) {
        int32_t sample = 0;
        const uint8_t* src = sampleBuffer.data() + i * bytesPerSample;
        for (uint8_t b = 0; b < bytesPerSample; b++) {
            sample |= static_cast<int32_t>(src[b]) << (8 * b);
        }
        // Sign-extend from bitDepth up to 32 bits.
        int32_t shift = 32 - bitDepth;
        sample = (sample << shift) >> shift;
        unpacked[i] = sample;
    }

    jintArray result_array = env->NewIntArray(static_cast<jsize>(totalSamples));
    env->SetIntArrayRegion(result_array, 0, static_cast<jsize>(totalSamples), unpacked.data());
    return result_array;
}

JNIEXPORT jint JNICALL
Java_com_soundscape_audio_nativebridge_AlacBridge_getBitDepth(JNIEnv*, jobject) {
    return g_decoder ? static_cast<jint>(g_decoder->mConfig.bitDepth) : 0;
}

JNIEXPORT void JNICALL
Java_com_soundscape_audio_nativebridge_AlacBridge_release(JNIEnv*, jobject) {
    delete g_decoder;
    g_decoder = nullptr;
}

} // extern "C"
