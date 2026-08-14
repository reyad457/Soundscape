#include "oboe_engine.h"
#include <android/log.h>

#define LOG_TAG "SoundscapeOboe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace soundscape {

bool OboeEngine::open(int32_t sampleRate, int32_t channelCount, int32_t bitsPerSample, int32_t deviceId) {
    oboe::AudioFormat format = (bitsPerSample > 16)
        ? oboe::AudioFormat::Float   // >16-bit sources are staged as float by the Kotlin decode layer
        : oboe::AudioFormat::I16;
    return openInternal(sampleRate, channelCount, deviceId, format);
}

bool OboeEngine::openDop(int32_t sampleRate, int32_t channelCount, int32_t deviceId) {
    // I32, never Float — see the header kdoc on why DoP can't take the
    // Float branch open() uses for other >16-bit sources.
    return openInternal(sampleRate, channelCount, deviceId, oboe::AudioFormat::I32);
}

bool OboeEngine::openInternal(int32_t sampleRate, int32_t channelCount, int32_t deviceId, oboe::AudioFormat format) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(format)
        ->setChannelCount(channelCount)
        ->setSampleRate(sampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None); // never silently resample

    if (deviceId != 0) {
        builder.setDeviceId(deviceId); // route straight to the attached USB DAC
    }

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGW("Exclusive stream open failed (%s) — caller should fall back to Shared mode / ExoPlayer",
             oboe::convertToText(result));
        return false;
    }

    // Oboe may silently downgrade Exclusive -> Shared if the device/driver doesn't
    // support it. Check what we actually got rather than trusting the request.
    exclusiveGranted_ = (stream_->getSharingMode() == oboe::SharingMode::Exclusive);
    if (!exclusiveGranted_) {
        LOGW("Requested Exclusive sharing mode but got Shared — not bit-perfect on this device");
    }

    stream_->requestStart();
    return true;
}

int32_t OboeEngine::write(const uint8_t* pcmData, int32_t numFrames) {
    if (!stream_) return -1;
    oboe::ResultWithValue<int32_t> result = stream_->write(
        pcmData, numFrames, /*timeoutNanos=*/ static_cast<int64_t>(1e8)
    );
    if (!result) {
        LOGW("write() failed: %s", oboe::convertToText(result.error()));
        return -1;
    }
    return result.value();
}

void OboeEngine::close() {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    exclusiveGranted_ = false;
}

bool OboeEngine::pause() {
    // Deliberately NOT close(): keeps the exclusive-mode stream (and its
    // hardware/driver claim on the DAC) alive across pause. Blocking
    // write() calls against a paused stream will simply block once its
    // buffer fills, which naturally throttles the Kotlin decode loop
    // without it needing to know pause happened — see
    // AAudioExclusiveEngine.pause()'s kdoc for the Kotlin side of this.
    if (!stream_) return false;
    oboe::Result result = stream_->requestPause();
    if (result != oboe::Result::OK) {
        LOGW("pause() failed: %s", oboe::convertToText(result));
        return false;
    }
    return true;
}

bool OboeEngine::resumeStream() {
    if (!stream_) return false;
    oboe::Result result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGW("resume() failed: %s", oboe::convertToText(result));
        return false;
    }
    return true;
}

int32_t OboeEngine::getActualSampleRate() const {
    return stream_ ? stream_->getSampleRate() : 0;
}

bool OboeEngine::isExclusiveModeActive() const {
    return exclusiveGranted_;
}

} // namespace soundscape
