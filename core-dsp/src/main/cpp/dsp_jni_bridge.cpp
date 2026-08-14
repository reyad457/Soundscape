#include <jni.h>
#include <memory>
#include "dsp_chain.h"

namespace {
    // One chain instance per process — matches oboe_engine.cpp's g_engine
    // pattern (Soundscape plays one track through one output at a time).
    std::unique_ptr<soundscape::DspChain> g_chain;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_soundscape_dsp_DspBridge_prepare(
    JNIEnv*, jobject, jdouble sampleRate, jint channelCount) {
    if (!g_chain) g_chain = std::make_unique<soundscape::DspChain>();
    g_chain->prepare(sampleRate, channelCount);
}

JNIEXPORT void JNICALL
Java_com_soundscape_dsp_DspBridge_configureBand(
    JNIEnv*, jobject, jint index, jboolean enabled, jint filterType,
    jdouble freqHz, jdouble gainDb, jdouble q) {
    if (!g_chain) return;

    soundscape::BandConfig config;
    config.enabled = enabled == JNI_TRUE;
    config.type = static_cast<soundscape::FilterType>(filterType);
    config.freqHz = freqHz;
    config.gainDb = gainDb;
    config.q = q;
    g_chain->configureBand(index, config);
}

JNIEXPORT void JNICALL
Java_com_soundscape_dsp_DspBridge_setCrossfeedAmount(
    JNIEnv*, jobject, jfloat amount) {
    if (g_chain) g_chain->setCrossfeedAmount(amount);
}

JNIEXPORT jboolean JNICALL
Java_com_soundscape_dsp_DspBridge_isActive(JNIEnv*, jobject) {
    return (g_chain && g_chain->isActive()) ? JNI_TRUE : JNI_FALSE;
}

// Processes a little-endian float32 interleaved buffer in place. Takes
// and returns the same byte array (mutated) rather than allocating a
// new one per call — this runs once per decode chunk on the hot path.
JNIEXPORT void JNICALL
Java_com_soundscape_dsp_DspBridge_processFloatBuffer(
    JNIEnv* env, jobject, jbyteArray buffer, jint frameCount) {
    if (!g_chain) return;

    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    g_chain->process(reinterpret_cast<float*>(bytes), frameCount);
    env->ReleaseByteArrayElements(buffer, bytes, 0); // 0, not JNI_ABORT — we modified the buffer
}

JNIEXPORT void JNICALL
Java_com_soundscape_dsp_DspBridge_release(JNIEnv*, jobject) {
    g_chain.reset();
}

} // extern "C"
