// Bit-perfect Monkey's Audio (APE) decode using the actual upstream
// decode classes (vendored, 3-Clause BSD — see ape/LICENSE-BSD3.txt;
// Monkey's Audio relicensed to BSD in recent years, see that file for
// the source), same self-contained-stream shape as flac_jni_decoder.cpp
// and wavpack_jni_decoder.cpp.
//
// Unlike WavPack's C reader-callback struct, Monkey's Audio's IO
// abstraction (CIO) is a small C++ interface meant to be subclassed —
// so this file's real content is a minimal CFdIO implementation over a
// FILE*, then a thin loop calling CAPEDecompress::GetData().

#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <vector>
#include <android/log.h>
#include "ape/MACLib.h"
#include "ape/APEInfo.h"
#include "ape/APEDecompress.h"
#include "ape/IO.h"

#define LOG_TAG "SoundscapeApe"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Minimal CIO over a plain FILE* — CStdLibFileIO (upstream's own
// implementation) insists on opening its own file by name, which
// doesn't fit local tracks arriving as content:// URIs with no
// filesystem path. This is the same "own the fd, not the filename"
// choice flac_jni_decoder.cpp and wavpack_jni_decoder.cpp both make.
class CFdIO : public CIO {
public:
    explicit CFdIO(FILE* file) : file_(file) {}

    int Open(const wchar_t*) override { return ERROR_SUCCESS; } // already open
    int Close() override { return ERROR_SUCCESS; } // caller owns the fd lifetime

    int Read(void* buffer, unsigned int bytesToRead, unsigned int* bytesRead) override {
        *bytesRead = static_cast<unsigned int>(fread(buffer, 1, bytesToRead, file_));
        return ERROR_SUCCESS;
    }

    int Write(const void*, unsigned int, unsigned int*) override {
        return -1; // decode-only — never called on this path
    }

    int Seek(int distance, unsigned int moveMode) override {
        int whence = (moveMode == FILE_BEGIN) ? SEEK_SET
                   : (moveMode == FILE_END) ? SEEK_END
                   : SEEK_CUR;
        return fseek(file_, distance, whence);
    }

    int Create(const wchar_t*) override { return -1; }
    int Delete() override { return -1; }
    int SetEOF() override { return -1; }

    int GetPosition() override { return static_cast<int>(ftell(file_)); }

    int GetSize() override {
        long current = ftell(file_);
        fseek(file_, 0, SEEK_END);
        long end = ftell(file_);
        fseek(file_, current, SEEK_SET);
        return static_cast<int>(end);
    }

    int GetName(wchar_t* buffer) override {
        if (buffer) buffer[0] = 0;
        return ERROR_SUCCESS;
    }

private:
    FILE* file_;
};

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_soundscape_audio_nativebridge_ApeBridge_decodeFd(
    JNIEnv* env, jobject callbackObj, jint fd, jlong startPositionMs) {

    FILE* file = fdopen(dup(fd), "rb");
    if (!file) {
        LOGE("fdopen failed for fd %d", fd);
        return JNI_FALSE;
    }

    CFdIO io(file);
    int errorCode = 0;

    CAPEInfo apeInfo(&errorCode, &io, nullptr);
    if (errorCode != ERROR_SUCCESS) {
        LOGE("CAPEInfo construction failed, code %d", errorCode);
        fclose(file);
        return JNI_FALSE;
    }

    CAPEDecompress decompress(&errorCode, &apeInfo);
    if (errorCode != ERROR_SUCCESS) {
        LOGE("CAPEDecompress construction failed, code %d", errorCode);
        fclose(file);
        return JNI_FALSE;
    }

    const int sampleRate = static_cast<int>(apeInfo.GetInfo(APE_INFO_SAMPLE_RATE));
    const int channels = static_cast<int>(apeInfo.GetInfo(APE_INFO_CHANNELS));
    const int bitsPerSample = static_cast<int>(apeInfo.GetInfo(APE_INFO_BITS_PER_SAMPLE));

    if (startPositionMs > 0 && sampleRate > 0) {
        // Monkey's Audio's "block" == one sample-frame (one sample across all
        // channels), so block offset per second == sample rate directly.
        int targetBlock = static_cast<int>((static_cast<double>(startPositionMs) / 1000.0) * sampleRate);
        int seekResult = decompress.Seek(targetBlock);
        if (seekResult != ERROR_SUCCESS) {
            LOGE("CAPEDecompress::Seek(%d) failed, code %d — continuing from wherever the decoder landed",
                 targetBlock, seekResult);
        }
    }

    jclass cls = env->GetObjectClass(callbackObj);
    jmethodID onFormatKnown = env->GetMethodID(cls, "onFormatKnown", "(III)V");
    jmethodID onPcmFrame = env->GetMethodID(cls, "onPcmFrame", "([II)V");

    env->CallVoidMethod(callbackObj, onFormatKnown, sampleRate, channels, bitsPerSample);

    const int blocksPerCall = 4096;
    const int bytesPerBlock = channels * (bitsPerSample / 8);
    std::vector<uint8_t> rawBuffer(static_cast<size_t>(blocksPerCall) * bytesPerBlock);
    std::vector<int32_t> unpacked(static_cast<size_t>(blocksPerCall) * channels);

    bool ok = true;
    while (true) {
        int blocksRetrieved = 0;
        int result = decompress.GetData(reinterpret_cast<char*>(rawBuffer.data()), blocksPerCall, &blocksRetrieved);
        if (result != ERROR_SUCCESS) {
            LOGE("CAPEDecompress::GetData failed, code %d", result);
            ok = false;
            break;
        }
        if (blocksRetrieved <= 0) break; // end of stream

        const int totalSamples = blocksRetrieved * channels;
        const int bytesPerSample = bitsPerSample / 8;

        // Sign-extend whatever bit depth GetData handed back (8/16/24/32-bit,
        // little-endian per the APE format) into int32 slots — same
        // convention flac_jni_decoder.cpp and alac_jni_decoder.cpp use.
        for (int i = 0; i < totalSamples; i++) {
            int32_t sample = 0;
            const uint8_t* src = rawBuffer.data() + static_cast<size_t>(i) * bytesPerSample;
            for (int b = 0; b < bytesPerSample; b++) {
                sample |= static_cast<int32_t>(src[b]) << (8 * b);
            }
            int shift = 32 - bitsPerSample;
            unpacked[i] = (sample << shift) >> shift;
        }

        jintArray jArray = env->NewIntArray(totalSamples);
        env->SetIntArrayRegion(jArray, 0, totalSamples, unpacked.data());
        env->CallVoidMethod(callbackObj, onPcmFrame, jArray, blocksRetrieved);
        env->DeleteLocalRef(jArray);
    }

    fclose(file);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
