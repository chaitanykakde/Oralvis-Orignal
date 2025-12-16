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
 * NOTE: This class is intentionally UI-framework agnostic and does not know about Android Views.
 */
class AutoCaptureController(
    private val captureSpeedThresh: Double = 4.0,
    private val captureStabilityThresh: Double = 3.0,
    private val captureDelaySeconds: Double = 0.5,
    private val captureCooldownSeconds: Double = 1.5
    // In the Python implementation, these are CAPTURE_SPEED_THRESH, CAPTURE_STAB_THRESH,
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
            guidanceCallback?.invoke(null)
            return
        }

        val nowMs = System.currentTimeMillis()

        // --- Gate 1: Stability thresholds ---
        val isStable =
            motionState.mu < captureSpeedThresh &&
                motionState.sigma < captureStabilityThresh

        var isArming = false

        // --- Gate 2: Hold steady timer ---
        if (isStable) {
            if (stableSince == null) {
                stableSince = nowMs
            }
            val elapsedMs = nowMs - (stableSince ?: nowMs)
            if (elapsedMs >= (captureDelaySeconds * 1000.0)) {
                isArming = true

                // --- Gate 3: Cooldown timer ---
                val sinceLastCaptureMs = nowMs - lastCaptureTime
                if (enableAutoCapture && sinceLastCaptureMs >= (captureCooldownSeconds * 1000.0)) {
                    onCaptureTriggered?.invoke()
                    lastCaptureTime = nowMs
                    stableSince = null
                    isArming = false
                }
            }
        } else {
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


