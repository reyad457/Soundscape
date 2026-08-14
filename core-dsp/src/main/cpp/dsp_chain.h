#ifndef SOUNDSCAPE_DSP_CHAIN_H
#define SOUNDSCAPE_DSP_CHAIN_H

#include "biquad.h"
#include <array>
#include <atomic>
#include <cstdint>

namespace soundscape {

constexpr int kMaxBands = 20;
constexpr int kMaxChannels = 8;

struct BandConfig {
    bool enabled = false;
    FilterType type = FilterType::Peaking;
    double freqHz = 1000.0;
    double gainDb = 0.0;
    double q = 0.707;
};

/**
 * Processes an interleaved float32 buffer in place: up to [kMaxBands]
 * parametric EQ bands (each a per-channel [Biquad] cascade — separate
 * filter state per channel, since sharing state across channels would
 * smear stereo separation), then an optional basic crossfeed stage.
 *
 * HONESTY NOTE on crossfeed: this is a simple attenuated-lowpass mix of
 * the opposite channel into each channel — NOT a full binaural/HRTF
 * crossfeed algorithm like bs2b or a proper Chu Moy-style circuit
 * (which also apply an inter-channel delay to model the time-of-arrival
 * difference at each ear). No delay line here yet. Good enough to
 * reduce hard stereo separation on headphones; not the "HRTF binaural
 * downmix" the master plan describes for later phases.
 *
 * Not thread-safe for concurrent parameter changes DURING processing —
 * [configureBand]/[setCrossfeedAmount] should be called from the same
 * thread driving [process], or with the caller's own synchronization.
 * Given this only ever runs on the single decode/write thread in
 * AAudioExclusiveEngine, that's the actual usage pattern today.
 */
class DspChain {
public:
    void configureBand(int index, const BandConfig& config) {
        if (index < 0 || index >= kMaxBands) return;
        bands_[index] = config;
        for (int ch = 0; ch < kMaxChannels; ch++) {
            filters_[index][ch].reset();
        }
        dirty_ = true;
    }

    void setCrossfeedAmount(float amount) { // 0.0 = off, 1.0 = fully mixed (mono-ish)
        crossfeedAmount_ = amount < 0.0f ? 0.0f : (amount > 1.0f ? 1.0f : amount);
        crossfeedDirty_ = true;
    }

    bool isActive() const {
        if (crossfeedAmount_ > 0.0f) return true;
        for (const auto& b : bands_) {
            if (b.enabled && b.gainDb != 0.0) return true;
        }
        return false;
    }

    void prepare(double sampleRate, int channelCount) {
        sampleRate_ = sampleRate;
        channelCount_ = channelCount > kMaxChannels ? kMaxChannels : channelCount;
        recalculateBandCoefficients();
        recalculateCrossfeedCoefficients();
    }

    // Processes [frameCount] interleaved frames of [channelCount_] floats each, in place.
    void process(float* interleaved, int frameCount) {
        if (dirty_) { recalculateBandCoefficients(); dirty_ = false; }
        if (crossfeedDirty_) { recalculateCrossfeedCoefficients(); crossfeedDirty_ = false; }

        for (int i = 0; i < frameCount; i++) {
            float* frame = interleaved + static_cast<size_t>(i) * channelCount_;

            for (int b = 0; b < kMaxBands; b++) {
                if (!bands_[b].enabled || bands_[b].gainDb == 0.0) continue;
                for (int ch = 0; ch < channelCount_; ch++) {
                    frame[ch] = filters_[b][ch].processSample(frame[ch]);
                }
            }

            if (crossfeedAmount_ > 0.0f && channelCount_ == 2) {
                const float left = frame[0];
                const float right = frame[1];
                const float leftBleed = crossfeedFilters_[0].processSample(right) * crossfeedAmount_;
                const float rightBleed = crossfeedFilters_[1].processSample(left) * crossfeedAmount_;
                frame[0] = left * (1.0f - crossfeedAmount_ * 0.5f) + leftBleed * 0.5f;
                frame[1] = right * (1.0f - crossfeedAmount_ * 0.5f) + rightBleed * 0.5f;
            }
        }
    }

private:
    void recalculateBandCoefficients() {
        for (int b = 0; b < kMaxBands; b++) {
            if (!bands_[b].enabled) continue;
            for (int ch = 0; ch < channelCount_; ch++) {
                filters_[b][ch].setParams(
                    bands_[b].type, sampleRate_, bands_[b].freqHz, bands_[b].gainDb, bands_[b].q
                );
            }
        }
    }

    void recalculateCrossfeedCoefficients() {
        // Fixed ~700Hz lowpass on the bled signal — crossfeed should only
        // carry low/mid content, matching how interaural crosstalk actually
        // behaves acoustically (high frequencies are far more directional).
        for (int ch = 0; ch < kMaxChannels; ch++) {
            crossfeedFilters_[ch].setParams(FilterType::LowPass, sampleRate_, 700.0, 0.0, 0.707);
        }
    }

    std::array<BandConfig, kMaxBands> bands_{};
    std::array<std::array<Biquad, kMaxChannels>, kMaxBands> filters_{};

    std::array<Biquad, kMaxChannels> crossfeedFilters_{};
    std::atomic<float> crossfeedAmount_{0.0f};
    bool crossfeedDirty_ = false;

    double sampleRate_ = 44100.0;
    int channelCount_ = 2;
    bool dirty_ = false;
};

} // namespace soundscape

#endif // SOUNDSCAPE_DSP_CHAIN_H
