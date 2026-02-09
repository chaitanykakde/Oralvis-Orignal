package com.oralvis.oralviscamera.feature.camera.mode

import android.content.Context
import com.jiangdg.ausbc.camera.CameraUVC
import com.oralvis.oralviscamera.camera.FluorescenceEffect

/**
 * Owns FluorescenceEffect and UV-specific parameter application (Phase 5D).
 * Called only by CameraModeController.
 */
class FluorescenceModeAdapter(private val context: Context) {

    private var fluorescenceEffect: FluorescenceEffect? = null

    /**
     * Apply or remove fluorescence effect on the camera based on mode.
     * Logic moved verbatim from MainActivity.applyModePreset.
     */
    fun applyForMode(camera: CameraUVC, mode: String) {
        if (mode == "Fluorescence") {
            if (fluorescenceEffect == null) {
                fluorescenceEffect = FluorescenceEffect(context)
            }
            camera.addRenderEffect(fluorescenceEffect!!)
            android.util.Log.d("FluorescenceMode", "Fluorescence effect applied")
        } else {
            fluorescenceEffect?.let { effect ->
                camera.removeRenderEffect(effect)
                android.util.Log.d("FluorescenceMode", "Fluorescence effect removed")
            }
        }
    }
}
