package com.oralvis.oralviscamera.feature.camera.mode

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.CameraUVC
import com.oralvis.oralviscamera.camera.CameraModePresets

/**
 * Owns currentMode and all mode-switching entry points (Phase 5D).
 * Called by UI and USB (via CameraCommandReceiver → MainActivity → controller).
 * Mode logic moved verbatim from MainActivity.
 */
class CameraModeController(
    private val getCamera: () -> MultiCameraClient.ICamera?,
    private val modeUi: CameraModeUi,
    private val fluorescenceAdapter: FluorescenceModeAdapter,
    private val runOnUiThread: (Runnable) -> Unit
) {
    var currentMode = "Normal"
        private set

    /**
     * Set current mode to Normal without applying preset (e.g. camera open init, spinner setup).
     */
    fun setCurrentModeNormal() {
        currentMode = "Normal"
    }

    /**
     * Switch to a mode: update state, update UI, apply preset.
     * Logic moved verbatim from MainActivity.switchToMode + applyModePreset.
     */
    fun switchToMode(mode: String) {
        currentMode = mode
        updateModeUI()
        applyModePreset(mode)
    }

    private fun updateModeUI() { /* no-op: mode toggles shown in settings panel only */ }

    /**
     * Apply camera preset for the given mode. Moved verbatim from MainActivity.applyModePreset.
     */
    fun applyModePreset(mode: String) {
        val preset = when (mode) {
            "Normal" -> CameraModePresets.NORMAL
            "Fluorescence" -> CameraModePresets.FLUORESCENCE
            else -> CameraModePresets.NORMAL
        }

        getCamera()?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    // Apply camera parameters
                    camera.setAutoExposure(preset.autoExposure)
                    camera.setAutoWhiteBalance(preset.autoWhiteBalance)
                    camera.setContrast(preset.contrast)
                    camera.setSaturation(preset.saturation)
                    camera.setBrightness(preset.brightness)
                    camera.setGamma(preset.gamma)
                    camera.setHue(preset.hue)
                    camera.setSharpness(preset.sharpness)
                    camera.setGain(preset.gain)
                    camera.setExposure(preset.exposure)

                    // Apply/remove visual fluorescence effect
                    fluorescenceAdapter.applyForMode(camera, mode)

                    // Update UI controls
                    modeUi.updateCameraControlValues()

                    modeUi.showToast("Switched to $mode mode")
                } catch (e: Exception) {
                    android.util.Log.e("FluorescenceMode", "Failed to apply $mode preset: ${e.message}", e)
                    modeUi.showToast("Failed to apply $mode preset: ${e.message}")
                }
            }
        }
    }

    /**
     * Update UI controls from current camera values. Delegates to host.
     */
    fun updateCameraControlValues() {
        modeUi.updateCameraControlValues()
    }

    /**
     * Switch to Normal (RGB) mode. Entry point for USB hardware command.
     * Blocks when camera not available; executes switch on main thread.
     */
    fun switchToNormalMode(): Boolean {
        if (getCamera() == null) {
            android.util.Log.w("UsbCommand", "RGB mode switch ignored: camera not available")
            return false
        }
        runOnUiThread(Runnable {
            try {
                switchToMode("Normal")
                android.util.Log.i("UsbCommand", "RGB (Normal) mode command executed successfully")
            } catch (e: Exception) {
                android.util.Log.e("UsbCommand", "Error switching to Normal mode: ${e.message}", e)
            }
        })
        return true
    }

    /**
     * Switch to Fluorescence (UV) mode. Entry point for USB hardware command.
     * Blocks when camera not available; executes switch on main thread.
     */
    fun switchToFluorescenceMode(): Boolean {
        if (getCamera() == null) {
            android.util.Log.w("UsbCommand", "UV mode switch ignored: camera not available")
            return false
        }
        runOnUiThread(Runnable {
            try {
                switchToMode("Fluorescence")
                android.util.Log.i("UsbCommand", "UV (Fluorescence) mode command executed successfully")
            } catch (e: Exception) {
                android.util.Log.e("UsbCommand", "Error switching to Fluorescence mode: ${e.message}", e)
            }
        })
        return true
    }
}
