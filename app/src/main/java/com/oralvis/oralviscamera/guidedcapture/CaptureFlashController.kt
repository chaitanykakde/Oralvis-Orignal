package com.oralvis.oralviscamera.guidedcapture

/**
 * Small helper that manages the "flash" effect lifecycle for the overlay.
 *
 * It simply tracks an integer countdown; the overlay view is responsible
 * for actually drawing the flash when showFlash is true.
 */
class CaptureFlashController(
    private val totalFlashFrames: Int = 8
) {
    private var remainingFrames: Int = 0

    val isActive: Boolean
        get() = remainingFrames > 0

    fun triggerFlash() {
        remainingFrames = totalFlashFrames
    }

    /**
     * Call once per rendered frame to let the controller decay the effect.
     */
    fun onFrameRendered() {
        if (remainingFrames > 0) {
            remainingFrames -= 1
        }
    }
}


