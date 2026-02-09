package com.oralvis.oralviscamera.feature.camera.mode

/**
 * UI callbacks for mode switching (Phase 5D).
 * Implemented by MainActivity so controller can update seek bars and show toasts
 * without touching camera parameters directly.
 */
interface CameraModeUi {
    fun updateCameraControlValues()
    fun setDefaultCameraControlValues()
    fun showToast(message: String)
}
