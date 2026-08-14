// True bit-perfect FLAC decode: libFLAC's own reference decoder, not a
// reimplementation and not MediaCodec's platform-dependent path. Every
// frame's bits-per-sample/sample rate/channel count come straight from
// the STREAMINFO block libFLAC parses itself.
//
// This intentionally does its own file I/O via an fd (see openFile below)
// rather than routing through Java/Kotlin stream callbacks — keeps the
// hot decode loop entirely native, matching the "Kotlin drives lifecycle,
// C++ drives bits" split used by oboe_engine.cpp.

#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <vector>
#include <unistd.h>
#include <android/log.h>
#include "flac/include/FLAC/stream_decoder.h"

#define LOG_TAG "SoundscapeFlac"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct DecoderContext {
    FILE* file = nullptr;
    FLAC__StreamDecoder* decoder = nullptr;

    // Format info, populated by the METADATA callback once STREAMINFO arrives.
    uint32_t sampleRate = 0;
    uint32_t channels = 0;
    uint32_t bitsPerSample = 0;
    bool formatKnown = false;

    // Java callback targets, set once per decode() call.
    JavaVM* jvm = nullptr;
    jobject callbackObj = nullptr; // global ref to the Kotlin FlacNativeDecoder
    jmethodID onFormatKnownMethod = nullptr;
    jmethodID onPcmFrameMethod = nullptr;
    bool cancelled = false;
};

FLAC__StreamDecoderWriteStatus writeCallback(
    const FLAC__StreamDecoder*, const FLAC__Frame* frame,
    const FLAC__int32* const buffer[], void* clientData) {

    auto* ctx = static_cast<DecoderContext*>(clientData);
    if (ctx->cancelled) return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;

    JNIEnv* env;
    if (ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    const uint32_t frames = frame->header.blocksize;
    const uint32_t channels = frame->header.channels;

    // Interleave into 32-bit-per-sample-slot ints (caller/Kotlin knows the
    // real bit depth from onFormatKnown and packs to the native engine's
    // expected width — see FlacNativeDecoder.kt).
    std::vector<int32_t> interleaved(static_cast<size_t>(frames) * channels);
    for (uint32_t i = 0; i < frames; i++) {
        for (uint32_t c = 0; c < channels; c++) {
            interleaved[i * channels + c] = buffer[c][i];
        }
    }

    jintArray jArray = env->NewIntArray(static_cast<jsize>(interleaved.size()));
    env->SetIntArrayRegion(jArray, 0, static_cast<jsize>(interleaved.size()), interleaved.data());
    env->CallVoidMethod(ctx->callbackObj, ctx->onPcmFrameMethod, jArray, static_cast<jint>(frames));
    env->DeleteLocalRef(jArray);

    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

void metadataCallback(const FLAC__StreamDecoder*, const FLAC__StreamMetadata* metadata, void* clientData) {
    auto* ctx = static_cast<DecoderContext*>(clientData);
    if (metadata->type != FLAC__METADATA_TYPE_STREAMINFO) return;

    ctx->sampleRate = metadata->data.stream_info.sample_rate;
    ctx->channels = metadata->data.stream_info.channels;
    ctx->bitsPerSample = metadata->data.stream_info.bits_per_sample;
    ctx->formatKnown = true;

    JNIEnv* env;
    if (ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        env->CallVoidMethod(
            ctx->callbackObj, ctx->onFormatKnownMethod,
            static_cast<jint>(ctx->sampleRate),
            static_cast<jint>(ctx->channels),
            static_cast<jint>(ctx->bitsPerSample)
        );
    }
}

void errorCallback(const FLAC__StreamDecoder*, FLAC__StreamDecoderErrorStatus status, void*) {
    LOGE("libFLAC stream error: %s", FLAC__StreamDecoderErrorStatusString[status]);
}

} // namespace

extern "C" {

// Decodes the whole file synchronously on the calling thread — callers
// (FlacNativeDecoder.kt) run this on Dispatchers.IO themselves, matching
// PcmDecoder's contract of "one call, streamed via callbacks".
//
// Takes an already-open file descriptor rather than a path: local tracks
// come from Kotlin as content:// URIs (MediaStore), which only resolve
// to a real fd via ContentResolver.openFileDescriptor — there's often no
// POSIX path to hand libFLAC directly. Using a fd here also means this
// same entry point will work unchanged for NAS/network sources later
// (core-network §3 of the master plan) as long as they can hand back a
// readable fd, which WebDAV/SMB local caching can.
JNIEXPORT jboolean JNICALL
Java_com_soundscape_audio_nativebridge_FlacBridge_decodeFd(
    JNIEnv* env, jobject callbackObj, jint fd) {

    FILE* file = fdopen(dup(fd), "rb"); // dup: caller still owns/closes the original fd
    if (!file) {
        LOGE("fdopen failed for fd %d", fd);
        return JNI_FALSE;
    }

    DecoderContext ctx;
    ctx.file = file;
    env->GetJavaVM(&ctx.jvm);
    ctx.callbackObj = env->NewGlobalRef(callbackObj);

    jclass cls = env->GetObjectClass(callbackObj);
    ctx.onFormatKnownMethod = env->GetMethodID(cls, "onFormatKnown", "(III)V");
    ctx.onPcmFrameMethod = env->GetMethodID(cls, "onPcmFrame", "([II)V");

    ctx.decoder = FLAC__stream_decoder_new();
    if (!ctx.decoder) {
        fclose(file);
        env->DeleteGlobalRef(ctx.callbackObj);
        return JNI_FALSE;
    }

    FLAC__stream_decoder_set_md5_checking(ctx.decoder, true); // verify integrity, not just decode

    FLAC__StreamDecoderInitStatus initStatus = FLAC__stream_decoder_init_FILE(
        ctx.decoder, file, writeCallback, metadataCallback, errorCallback, &ctx
    );

    if (initStatus != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
        LOGE("FLAC init failed: %s", FLAC__StreamDecoderInitStatusString[initStatus]);
        FLAC__stream_decoder_delete(ctx.decoder);
        fclose(file);
        env->DeleteGlobalRef(ctx.callbackObj);
        return JNI_FALSE;
    }

    bool ok = FLAC__stream_decoder_process_until_end_of_stream(ctx.decoder);
    if (!ok) {
        LOGW("FLAC decode ended early (state: %s)",
             FLAC__StreamDecoderStateString[FLAC__stream_decoder_get_state(ctx.decoder)]);
    }

    // FLAC__stream_decoder_finish() closes the FILE* it was given (that's
    // libFLAC's documented behavior for init_FILE), so we don't fclose() again.
    FLAC__stream_decoder_finish(ctx.decoder);
    FLAC__stream_decoder_delete(ctx.decoder);
    env->DeleteGlobalRef(ctx.callbackObj);

    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
