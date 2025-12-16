package com.oralvis.oralviscamera.guidedcapture

/**
 * Kotlin equivalent of the Python GuidanceResult dataclass.
 *
 * prompt:      Human-readable guidance string ("Slow down", "Hold steady to capture...", etc.)
 * color:       Packed ARGB color int used for the guidance bar background.
 * motionState: Latest motion metrics driving this guidance.
 */
data class GuidanceResult(
    val prompt: String,
    val color: Int,
    val motionState: MotionState
)


