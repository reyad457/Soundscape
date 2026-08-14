// Bit-perfect WavPack decode using the actual upstream libwavpack decode
// path (vendored, BSD-3-Clause — see wavpack/LICENSE-BSD3.txt), same
// pattern as flac_jni_decoder.cpp: WavPack files are self-contained
// streams (no MP4 container to demux, unlike ALAC), so this drives the
// bitstream directly against a fd, no MediaExtractor involved.
//
// WavPack's public API takes a reader-callback struct rather than a
// FILE* directly, so this file's real content is implementing that
// struct (WavpackStreamReader64) over stdio — everything else is a
// thin loop calling WavpackUnpackSamples().

#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <vector>
#include <android/log.h>
#include "wavpack/include/wavpack.h"

#define LOG_TAG "SoundscapeWavpack"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

int32_t readBytes(void* id, void* data, int32_t bcount) {
    return static_cast<int32_t>(fread(data, 1, bcount, static_cast<FILE*>(id)));
}

int64_t getPos(void* id) {
    return ftell(static_cast<FILE*>(id));
}

int setPosAbs(void* id, int64_t pos) {
    return fseek(static_cast<FILE*>(id), static_cast<long>(pos), SEEK_SET);
}

int setPosRel(void* id, int64_t delta, int mode) {
    return fseek(static_cast<FILE*>(id), static_cast<long>(delta), mode);
}

int pushBackByte(void* id, int c) {
    return ungetc(c, static_cast<FILE*>(id));
}

int64_t getLength(void* id) {
    FILE* f = static_cast<FILE*>(id);
    long current = ftell(f);
    fseek(f, 0, SEEK_END);
    long end = ftell(f);
    fseek(f, current, SEEK_SET);
    return end;
}

int canSeek(void* /* id */) {
    return 1; // fds from ContentResolver.openFileDescriptor on local files are seekable
}

int32_t writeBytes(void*, void*, int32_t) {
    return 0; // decode-only — never called on this path
}

WavpackStreamReader64 makeReader() {
    WavpackStreamReader64 reader{};
    reader.read_bytes = readBytes;
    reader.write_bytes = writeBytes;
    reader.get_pos = getPos;
    reader.set_pos_abs = setPosAbs;
    reader.set_pos_rel = setPosRel;
    reader.push_back_byte = pushBackByte;
    reader.get_length = getLength;
    reader.can_seek = canSeek;
    reader.truncate_here = nullptr;
    reader.close = nullptr; // caller (Kotlin, via ParcelFileDescriptor) owns fd lifetime
    return reader;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_soundscape_audio_nativebridge_WavpackBridge_decodeFd(
    JNIEnv* env, jobject callbackObj, jint fd, jlong startPositionMs) {

    FILE* file = fdopen(dup(fd), "rb");
    if (!file) {
        LOGE("fdopen failed for fd %d", fd);
        return JNI_FALSE;
    }

    char error[128] = {0};
    WavpackStreamReader64 reader = makeReader();
    WavpackContext* wpc = WavpackOpenFileInputEx64(&reader, file, nullptr, error, OPEN_NORMALIZE, 0);

    if (!wpc) {
        LOGE("WavpackOpenFileInputEx64 failed: %s", error);
        fclose(file);
        return JNI_FALSE;
    }

    const int sampleRate = WavpackGetSampleRate(wpc);
    const int channels = WavpackGetNumChannels(wpc);
    const int bitsPerSample = WavpackGetBitsPerSample(wpc);
    const int mode = WavpackGetMode(wpc);
    const bool isLossless = (mode & MODE_LOSSLESS) != 0;

    if (!isLossless) {
        // Soundscape only claims bit-perfect for lossless sources — a hybrid/lossy
        // .wv is still playable via the MediaCodec fallback path, just not through here.
        LOGE("WavPack file is not lossless (hybrid/lossy mode) — refusing native decode");
        WavpackCloseFile(wpc);
        fclose(file);
        return JNI_FALSE;
    }

    if (startPositionMs > 0 && sampleRate > 0) {
        auto targetSample = static_cast<int64_t>(
            (static_cast<double>(startPositionMs) / 1000.0) * sampleRate
        );
        if (!WavpackSeekSample64(wpc, targetSample)) {
            LOGE("WavpackSeekSample64 to %lld failed — continuing from wherever the decoder landed",
                 static_cast<long long>(targetSample));
        }
    }

    jclass cls = env->GetObjectClass(callbackObj);
    jmethodID onFormatKnown = env->GetMethodID(cls, "onFormatKnown", "(III)V");
    jmethodID onPcmFrame = env->GetMethodID(cls, "onPcmFrame", "([II)V");

    env->CallVoidMethod(callbackObj, onFormatKnown, sampleRate, channels, bitsPerSample);

    const uint32_t samplesPerCall = 4096;
    std::vector<int32_t> buffer(static_cast<size_t>(samplesPerCall) * channels);

    uint32_t samplesUnpacked;
    bool ok = true;
    while ((samplesUnpacked = WavpackUnpackSamples(wpc, buffer.data(), samplesPerCall)) > 0) {
        jintArray jArray = env->NewIntArray(static_cast<jsize>(samplesUnpacked * channels));
        env->SetIntArrayRegion(jArray, 0, static_cast<jsize>(samplesUnpacked * channels), buffer.data());
        env->CallVoidMethod(callbackObj, onPcmFrame, jArray, static_cast<jint>(samplesUnpacked));
        env->DeleteLocalRef(jArray);
    }

    if (WavpackGetNumErrors(wpc) > 0) {
        LOGE("WavPack decode finished with errors");
        ok = false;
    }

    WavpackCloseFile(wpc);
    fclose(file);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
