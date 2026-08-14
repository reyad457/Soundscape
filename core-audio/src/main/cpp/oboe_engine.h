#ifndef SOUNDSCAPE_OBOE_ENGINE_H
#define SOUNDSCAPE_OBOE_ENGINE_H

#include <oboe/Oboe.h>
#include <cstdint>

namespace soundscape {

/**
 * Thin wrapper around a single Oboe output stream, opened in EXCLUSIVE
 * sharing mode wherever the device/DAC allows it. This is the Phase 1
 * "prove bit-perfect is at least possible" path — no DSP, no mixing,
 * just: open the stream at the source's native rate/format, and report
 * honestly whether exclusive mode was actually granted.
 *
 * Blocking writes are used deliberately (not a callback) so the Kotlin
 * decode pipeline stays in control of pacing — matches the Phase 2 plan
 * where format-specific decoders feed this same write() path.
 */
class OboeEngine {
public:
    bool open(int32_t sampleRate, int32_t channelCount, int32_t bitsPerSample, int32_t deviceId);

    /**
     * Opens specifically for DoP (DSD-over-PCM): Oboe's I32 format, no
     * float/Int16 branching like open() does — DoP's marker bytes must
     * survive completely bit-exact, and float conversion is lossy by
     * construction, so this path can never go through open()'s Float
     * branch even though DoP nominally carries "24-bit" data.
     */
    bool openDop(int32_t sampleRate, int32_t channelCount, int32_t deviceId);

    int32_t write(const uint8_t* pcmData, int32_t numFrames);
    void close();

    bool pause();
    bool resumeStream();

    int32_t getActualSampleRate() const;
    bool isExclusiveModeActive() const;

private:
    bool openInternal(int32_t sampleRate, int32_t channelCount, int32_t deviceId, oboe::AudioFormat format);

    std::shared_ptr<oboe::AudioStream> stream_;
    bool exclusiveGranted_ = false;
};

} // namespace soundscape

#endif // SOUNDSCAPE_OBOE_ENGINE_H
