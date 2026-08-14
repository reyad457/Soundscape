package com.soundscape.dsp

/**
 * A single parametric EQ band. [q] follows the standard convention
 * (higher = narrower bandwidth) — 0.707 is the "one octave"-ish default
 * most EQ UIs start users at.
 */
data class EqBand(
    val enabled: Boolean = false,
    val type: FilterType = FilterType.PEAKING,
    val freqHz: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 0.707
)
