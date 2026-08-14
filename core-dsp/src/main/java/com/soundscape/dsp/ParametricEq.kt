package com.soundscape.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-facing DSP control surface — [AAudioExclusiveEngine] (core-audio)
 * calls [prepare] once per track (needs the real sample rate/channel
 * count from the decoder) and [process] per decoded chunk; UI screens
 * call [setBand]/[setCrossfeed]/[applyPreset] to change what's playing.
 *
 * [bands] is the Kotlin-side source of truth — mirrored into the native
 * chain on every change rather than read back from it, since the native
 * side has no reason to hand full state back across JNI on every query.
 */
@Singleton
class ParametricEq @Inject constructor() {

    companion object {
        const val BAND_COUNT = 10 // starter count; native chain supports up to kMaxBands (20)
    }

    private val _bands = MutableStateFlow(List(BAND_COUNT) { EqBand(enabled = false) })
    val bands: StateFlow<List<EqBand>> = _bands

    private val _crossfeedAmount = MutableStateFlow(0f)
    val crossfeedAmount: StateFlow<Float> = _crossfeedAmount

    private var prepared = false

    /** Called once the real sample rate/channel count are known for the current track. */
    fun prepare(sampleRateHz: Int, channelCount: Int) {
        DspBridge.prepare(sampleRateHz.toDouble(), channelCount)
        _bands.value.forEachIndexed { i, band -> pushBand(i, band) }
        DspBridge.setCrossfeedAmount(_crossfeedAmount.value)
        prepared = true
    }

    fun setBand(index: Int, band: EqBand) {
        if (index !in _bands.value.indices) return
        _bands.value = _bands.value.toMutableList().also { it[index] = band }
        if (prepared) pushBand(index, band)
    }

    fun setCrossfeed(amount: Float) {
        _crossfeedAmount.value = amount.coerceIn(0f, 1f)
        if (prepared) DspBridge.setCrossfeedAmount(_crossfeedAmount.value)
    }

    /** Whether the chain would actually alter the signal right now — see its use in AAudioExclusiveEngine. */
    fun isActive(): Boolean = prepared && DspBridge.isActive()

    /**
     * Same check as [isActive], but computable BEFORE [prepare] has run
     * for the current track — from Kotlin-side state alone, no native
     * call. [AAudioExclusiveEngine] needs this to decide whether to open
     * the AAudio stream in float format before the native chain object
     * exists yet for this track; [isActive] alone can't answer that
     * chicken-and-egg question since it requires [prepare] to have
     * already run.
     */
    fun wouldBeActive(): Boolean {
        if (_crossfeedAmount.value > 0f) return true
        return _bands.value.any { it.enabled && it.gainDb != 0.0 }
    }

    /** Processes one decoded chunk's worth of interleaved float32 PCM in place. No-ops if nothing is enabled. */
    fun process(floatBytes: ByteArray, frameCount: Int) {
        if (!prepared) return
        DspBridge.processFloatBuffer(floatBytes, frameCount)
    }

    fun applyPreset(preset: Preset) {
        val newBands = preset.bands + List(BAND_COUNT - preset.bands.size) { EqBand(enabled = false) }
        _bands.value = newBands
        if (prepared) newBands.forEachIndexed { i, band -> pushBand(i, band) }
    }

    private fun pushBand(index: Int, band: EqBand) {
        DspBridge.configureBand(index, band.enabled, band.type.ordinal, band.freqHz, band.gainDb, band.q)
    }

    enum class Preset(val bands: List<EqBand>) {
        FLAT(emptyList()),
        WARM(
            listOf(
                EqBand(enabled = true, type = FilterType.LOW_SHELF, freqHz = 200.0, gainDb = 2.5, q = 0.707),
                EqBand(enabled = true, type = FilterType.PEAKING, freqHz = 3000.0, gainDb = -1.5, q = 1.0)
            )
        ),
        BRIGHT(
            listOf(
                EqBand(enabled = true, type = FilterType.HIGH_SHELF, freqHz = 6000.0, gainDb = 3.0, q = 0.707),
                EqBand(enabled = true, type = FilterType.PEAKING, freqHz = 250.0, gainDb = -1.0, q = 1.0)
            )
        )
    }
}
