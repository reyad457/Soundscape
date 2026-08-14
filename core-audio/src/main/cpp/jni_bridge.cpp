#include <jni.h>
#include <memory>
#include "oboe_engine.h"

namespace {
    // One engine instance per process — Soundscape only ever plays one
    // exclusive-mode stream at a time by design (that's what exclusive means).
    std::unique_ptr<soundscape::OboeEngine> g_engine;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_soundscape_audio_nativebridge_AAudioBridge_openStream(
    JNIEnv* env, jobject /* this */,
    jint sampleRate, jint channelCount, jint bitsPerSample, jint usbDeviceId) {

    g_engine = std::make_unique<soundscape::OboeEngine>();
    bool opened = g_engine->open(sampleRate, channelCount, bitsPerSample, usbDeviceId);
    return opened ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_soundscape_audio_nativebridge_AAudioBridge_writeFrames(
    JNIEnv* env, jobject /* this */, jbyteArray pcm, jint frameCount) {

    if (!g_engine) return -1;

    jbyte* bytes = env->GetByteArrayElements(pcm, nullptr);
    int32_t written = g_engine->write(reinterpret_cast<const uint8_t*>(bytes), frameCount);
    env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL
Java_com_soundscape_audio_nativebridge_AAudioBridge_getActualSampleRate(
    JNIEnv* env, jobject /* this */) {
    return g_engine ? g_engine->getActualSampleRate() : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_soundscape_audio_nativebridge_AAudioBridge_isExclusiveMode(
    JNIEnv* env, jobject /* this */) {
    return (g_engine && g_engine->isExclusiveModeActive()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_soundscape_audio_nativebridge_AAudioBridge_closeStream(
    JNIEnv* env, jobject /* this */) {
    if (g_engine) {
        g_engine->close();
        g_engine.reset();
    }
}

} // extern "C"
