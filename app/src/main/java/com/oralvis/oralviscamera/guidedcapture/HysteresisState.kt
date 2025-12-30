package com.oralvis.oralviscamera.guidedcapture

/**
 * Pure Kotlin version of the Python HysteresisState helper.
 *
 * Converts a noisy scalar metric into a stable boolean warning signal using:
 * - enterThreshold: value above which we *may* enter warning
 * - clearThreshold: value below which we *may* clear warning
 * - kConfirm: number of consecutive frames required to confirm a change
 */
class HysteresisState(
    private val enterThreshold: Double,
    private val clearThreshold: Double,
    private val kConfirm: Int = 5
) {
    private var enterCounter: Int = 0
    private var clearCounter: Int = 0

    var isWarning: Boolean = false
        private set

    /**
     * Update hysteresis state with a new metric value.
     * Returns the current isWarning value after the update.
     */
    fun update(value: Double): Boolean {
        if (!isWarning) {
            if (value >= enterThreshold) {
                enterCounter++
            } else {
                enterCounter = 0
            }
            if (enterCounter >= kConfirm) {
                isWarning = true
                enterCounter = 0
            }
        } else {
            if (value <= clearThreshold) {
                clearCounter++
            } else {
                clearCounter = 0
            }
            if (clearCounter >= kConfirm) {
                isWarning = false
                clearCounter = 0
            }
        }
        return isWarning
    }
    
    /**
     * Immediately reset the hysteresis state to non-warning.
     * Useful when clearing state (e.g., when scanning stops).
     */
    fun reset() {
        isWarning = false
        enterCounter = 0
        clearCounter = 0
    }
}


