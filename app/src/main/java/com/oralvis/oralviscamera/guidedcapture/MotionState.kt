package com.oralvis.oralviscamera.guidedcapture

/**
 * Kotlin equivalent of the Python MotionState dataclass.
 * Holds smoothed motion metrics and warning flags produced by MotionAnalyzer.
 */
data class MotionState(
    val mu: Double = 0.0,
    val sigma: Double = 0.0,
    val speedWarning: Boolean = false,
    val stabilityWarning: Boolean = false
)


