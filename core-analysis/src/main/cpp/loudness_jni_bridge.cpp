// Loudness/True Peak measurement via libebur128 (vendored, MIT — see
// ebur128/LICENSE-MIT.txt), the actual reference implementation of the
// ITU-R BS.1770 / EBU R128 loudness standard, not a reimplementation —
// same "vendor the real thing" approach as the FLAC/ALAC/WavPack/APE
// decoders, just for an analysis library instead of a codec this time.
//
// This runs OFFLINE (a "scan this track" action), never in the
// real-time playback path — see FidelityScanner.kt on the Kotlin side.

#include <jni.h>
#include <cmath>
#include "ebur128/ebur128.h"

namespace {
    // One scan at a time — matches every other g_-singleton pattern in
    // this codebase (oboe_engine.cpp, dsp_jni_bridge.cpp).
    ebur128_state* g_state = nullptr;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_soundscape_analysis_LoudnessBridge_init(
    JNIEnv*, jobject, jint sampleRate, jint channels) {

    if (g_state) {
        ebur128_destroy(&g_state);
    }
    g_state = ebur128_init(
        static_cast<unsigned int>(channels),
        static_cast<unsigned long>(sampleRate),
        EBUR128_MODE_I | EBUR128_MODE_LRA | EBUR128_MODE_TRUE_PEAK | EBUR128_MODE_SAMPLE_PEAK
    );
    return g_state ? JNI_TRUE : JNI_FALSE;
}

// [samples] is interleaved float32, [frameCount] is frames (not total samples).
JNIEXPORT void JNICALL
Java_com_soundscape_analysis_LoudnessBridge_addFrames(
    JNIEnv* env, jobject, jfloatArray samples, jint frameCount) {
    if (!g_state) return;

    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    ebur128_add_frames_float(g_state, data, static_cast<size_t>(frameCount));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT jdouble JNICALL
Java_com_soundscape_analysis_LoudnessBridge_getIntegratedLoudness(JNIEnv*, jobject) {
    double out = -HUGE_VAL;
    if (g_state) ebur128_loudness_global(g_state, &out);
    return out;
}

JNIEXPORT jdouble JNICALL
Java_com_soundscape_analysis_LoudnessBridge_getLoudnessRange(JNIEnv*, jobject) {
    double out = 0.0;
    if (g_state) ebur128_loudness_range(g_state, &out);
    return out;
}

// Returns the highest true peak across all channels, in dBTP (0 dBTP = full scale).
JNIEXPORT jdouble JNICALL
Java_com_soundscape_analysis_LoudnessBridge_getTruePeakDbtp(JNIEnv*, jobject, jint channelCount) {
    if (!g_state) return -HUGE_VAL;

    double maxPeak = 0.0;
    for (unsigned int ch = 0; ch < static_cast<unsigned int>(channelCount); ch++) {
        double peak = 0.0;
        if (ebur128_true_peak(g_state, ch, &peak) == EBUR128_SUCCESS && peak > maxPeak) {
            maxPeak = peak;
        }
    }
    return maxPeak > 0.0 ? 20.0 * log10(maxPeak) : -HUGE_VAL;
}

JNIEXPORT void JNICALL
Java_com_soundscape_analysis_LoudnessBridge_release(JNIEnv*, jobject) {
    if (g_state) ebur128_destroy(&g_state);
}

} // extern "C"
