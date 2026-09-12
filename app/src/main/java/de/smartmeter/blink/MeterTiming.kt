package de.smartmeter.blink

/**
 * Blink timing in milliseconds.
 *
 * Defaults follow the proven values from the "Meter Blinker" E320 webapp
 * (pulse 180 ms, gap 320 ms, digit wait 3.4 s, long pulse 6.0 s) which are
 * consistent with the official Landis+Gyr/EYKON E320 operation sheet (3 s digit
 * timeout, ~5 s hold for toggles).
 */
data class MeterTiming(
    val pulseLengthMs: Int = 180,
    val pulseGapMs: Int = 320,
    val digitWaitMs: Int = 3400,
    val longPulseMs: Int = 6000,
    val manualShortMs: Int = 250,
    val wakeWaitMs: Int = 4000,
)