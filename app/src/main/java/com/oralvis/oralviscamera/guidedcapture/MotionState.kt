package com.oralvis.oralviscamera.guidedcapture

/**
 * Kotlin equivalent of the Python MotionState dataclass.
 * Holds smoothed motion metrics and warning flags produced by MotionAnalyzer.
 * 
 * Updated to match Windows app: single motionScore instead of mu/sigma.
 */
data class MotionState(
    val motionScore: Double = 0.0,
    val speedWarning: Boolean = false,
    val stabilityWarning: Boolean = false
)


