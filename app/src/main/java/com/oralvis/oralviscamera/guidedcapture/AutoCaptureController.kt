package com.oralvis.oralviscamera.guidedcapture

import android.graphics.Color

/**
 * Kotlin analogue of the Python GuidanceSystem "_state_worker" loop.
 *
 * This class:
 * - Consumes MotionState updates from MotionAnalyzer
 * - Applies the "three-gate" auto-capture logic
 * - Produces high-level guidance (prompt + color) for the UI
 * - Emits capture events when all gates are satisfied
 *
 * Updated to match Windows app: uses single motionScore instead of mu/sigma.
 *
 * NOTE: This class is intentionally UI-framework agnostic and does not know about Android Views.
 */
class AutoCaptureController(
    private val captureStabilityThresh: Double = 2.0, // Single threshold for motionScore
    private val captureDelaySeconds: Double = 0.5,
    private val captureCooldownSeconds: Double = 1.5
    // In the Windows app implementation, these are CAPTURE_STABILITY_THRESH (2.0),
    // CAPTURE_DELAY_S, CAPTURE_COOLDOWN_S respectively.
) {

    /**
     * Until real motion metrics are implemented (optical flow in MotionAnalyzer),
     * we explicitly disable automatic capture to prevent unexpected shots.
     *
     * When MotionAnalyzer starts producing meaningful mu/sigma values that mirror
     * the Python implementation, this flag can be set to true so the three-gate
     * logic below will actually trigger captures.
     */
    var enableAutoCapture: Boolean = false

    // Whether we are in an active scanning phase (SCANNING_LOWER / SCANNING_UPPER).
    // When false, guidance is cleared and capture is disabled.
    var isProcessingActive: Boolean = false
        set(value) {
            field = value
            if (!value) {
                // When processing is disabled, clear guidance for consumers.
                onGuidanceUpdated?.invoke(null)
            }
        }

    // Callbacks for consumers (MainActivity / overlay controller)
    var onGuidanceUpdated: ((GuidanceResult?) -> Unit)? = null
    var onCaptureTriggered: (() -> Unit)? = null

    private var stableSince: Long? = null
    private var lastCaptureTime: Long = 0L

    /**
     * Update controller with a new MotionState.
     * This should be called whenever MotionAnalyzer has a fresh motion estimate.
     */
    fun onMotionStateUpdated(motionState: MotionState) {
        val guidanceCallback = onGuidanceUpdated

        if (!isProcessingActive) {
            // When not actively scanning, we do not attempt capture, but we still
            // allow callers to clear any stale guidance display.
            // Log occasionally to help debug why auto-capture isn't working
            val now = System.currentTimeMillis()
            if (now % 2000 < 100) {
                android.util.Log.d("AutoCaptureController", "onMotionStateUpdated: isProcessingActive=false, skipping (this is normal when not scanning)")
            }
            guidanceCallback?.invoke(null)
            return
        }

        val nowMs = System.currentTimeMillis()

        // --- Gate 1: Stability threshold (simplified to single motionScore check) ---
        val isStable = motionState.motionScore < captureStabilityThresh
        
        // Enhanced logging for debugging - especially for upper scan
        android.util.Log.d("AutoCaptureController", "onMotionStateUpdated: motionScore=${motionState.motionScore}, threshold=$captureStabilityThresh, isStable=$isStable, enableAutoCapture=$enableAutoCapture, isProcessingActive=$isProcessingActive, stableSince=$stableSince, lastCaptureTime=$lastCaptureTime")

        var isArming = false

        // --- Gate 2: Hold steady timer ---
        if (isStable) {
            if (stableSince == null) {
                stableSince = nowMs
                android.util.Log.d("AutoCaptureController", "Motion is stable, starting hold-steady timer")
            }
            val elapsedMs = nowMs - (stableSince ?: nowMs)
            if (elapsedMs >= (captureDelaySeconds * 1000.0)) {
                isArming = true
                android.util.Log.d("AutoCaptureController", "Hold-steady timer complete (${elapsedMs}ms >= ${captureDelaySeconds * 1000.0}ms), arming capture")

                // --- Gate 3: Cooldown timer ---
                val sinceLastCaptureMs = nowMs - lastCaptureTime
                if (enableAutoCapture && sinceLastCaptureMs >= (captureCooldownSeconds * 1000.0)) {
                    android.util.Log.d("AutoCaptureController", "Auto-capture triggered! motionScore=${motionState.motionScore}, calling onCaptureTriggered callback")
                    onCaptureTriggered?.invoke()
                    lastCaptureTime = nowMs
                    stableSince = null
                    isArming = false
                } else {
                    if (!enableAutoCapture) {
                        android.util.Log.d("AutoCaptureController", "Auto-capture disabled, not triggering (enableAutoCapture=false)")
                    } else {
                        android.util.Log.d("AutoCaptureController", "Cooldown active: ${sinceLastCaptureMs}ms < ${captureCooldownSeconds * 1000.0}ms")
                    }
                }
            } else {
                android.util.Log.d("AutoCaptureController", "Hold-steady timer: ${elapsedMs}ms / ${captureDelaySeconds * 1000.0}ms")
            }
        } else {
            if (stableSince != null) {
                android.util.Log.d("AutoCaptureController", "Motion no longer stable (motionScore=${motionState.motionScore} >= $captureStabilityThresh), resetting timer")
            }
            stableSince = null
            isArming = false
        }

        // Map MotionState + arming to guidance prompt + color (mirrors Python logic)
        val (prompt, colorInt) = when {
            motionState.speedWarning && motionState.stabilityWarning -> {
                "Slow down & keep steady" to Color.rgb(60, 60, 255) // red-ish
            }

            motionState.speedWarning -> {
                "Slow down" to Color.rgb(0, 180, 255) // amber-ish
            }

            motionState.stabilityWarning -> {
                "Keep steady" to Color.rgb(0, 180, 255) // amber-ish
            }

            isArming -> {
                "Hold steady to capture..." to Color.rgb(255, 200, 80) // cyan-ish (matching Python's intent)
            }

            else -> {
                "Ready to capture" to Color.rgb(60, 200, 60) // green
            }
        }

        guidanceCallback?.invoke(GuidanceResult(prompt, colorInt, motionState))
    }
}


