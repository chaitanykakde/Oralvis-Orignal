package com.oralvis.oralviscamera.feature.camera.mode

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.CameraUVC
import com.oralvis.oralviscamera.camera.CameraModePresets

/**
 * Owns currentMode and all mode-switching entry points (Phase 5D).
 * Called by UI and USB (via CameraCommandReceiver → MainActivity → controller).
 * Mode logic moved verbatim from MainActivity.
 */
enum class CameraMode {
    NORMAL,
    CARIES
}

class CameraModeController(
    private val getCamera: () -> MultiCameraClient.ICamera?,
    private val modeUi: CameraModeUi,
    private val fluorescenceAdapter: FluorescenceModeAdapter,
    private val runOnUiThread: (Runnable) -> Unit
) {
    var currentMode: CameraMode = CameraMode.NORMAL
        private set

    /**
     * Set current mode to Normal without applying preset (e.g. camera open init).
     */
    fun setCurrentModeNormal() {
        currentMode = CameraMode.NORMAL
    }

    /**
     * Unified entry point for all mode changes (UI toggle + hardware UV/RGB).
     */
    fun applyCameraMode(mode: CameraMode) {
        currentMode = mode
        updateModeUI()
        applyModePreset(mode)
    }

    private fun updateModeUI() { /* no-op: mode toggles shown in settings panel only */ }

    /**
     * Apply camera preset for the given mode.
     */
    fun applyModePreset(mode: CameraMode) {
        val preset = when (mode) {
            CameraMode.NORMAL -> CameraModePresets.NORMAL
            CameraMode.CARIES -> CameraModePresets.FLUORESCENCE
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
                    fluorescenceAdapter.applyForMode(
                        camera,
                        if (mode == CameraMode.CARIES) "Fluorescence" else "Normal"
                    )

                    // Update UI controls (sliders, labels)
                    modeUi.updateCameraControlValues()
                    // Sync toggle state in UI
                    modeUi.syncModeToggle(mode)
                } catch (e: Exception) {
                    android.util.Log.e("FluorescenceMode", "Failed to apply $mode preset: ${e.message}", e)
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
                applyCameraMode(CameraMode.NORMAL)
                android.util.Log.i("UsbCommand", "RGB (Normal) mode command executed successfully")
            } catch (e: Exception) {
                android.util.Log.e("UsbCommand", "Error switching to Normal mode: ${e.message}", e)
            }
        })
        return true
    }

    /**
     * Switch to Fluorescence (UV / Caries) mode. Entry point for USB hardware command.
     * Blocks when camera not available; executes switch on main thread.
     */
    fun switchToFluorescenceMode(): Boolean {
        if (getCamera() == null) {
            android.util.Log.w("UsbCommand", "UV mode switch ignored: camera not available")
            return false
        }
        runOnUiThread(Runnable {
            try {
                applyCameraMode(CameraMode.CARIES)
                android.util.Log.i("UsbCommand", "UV (Fluorescence) mode command executed successfully")
            } catch (e: Exception) {
                android.util.Log.e("UsbCommand", "Error switching to Fluorescence mode: ${e.message}", e)
            }
        })
        return true
    }
}
