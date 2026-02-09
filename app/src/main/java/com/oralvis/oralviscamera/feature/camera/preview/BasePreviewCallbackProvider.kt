package com.oralvis.oralviscamera.feature.camera.preview

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IPreviewDataCallBack

/**
 * Owns the base no-op preview callback and single-registration guarantee (Phase 5B).
 * Logic copied verbatim from MainActivity; enables CameraUVC parameter application.
 */
class BasePreviewCallbackProvider {
    private var hasRegisteredBasePreviewCallback = false

    private val noOpPreviewCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
            // Intentionally empty - exists only to enable CameraUVC parameter application
        }
    }

    /**
     * Register the base preview callback on the camera if not already registered.
     * Guarantees single registration per camera open.
     * @return true if registration was performed, false if already registered
     */
    fun registerIfNeeded(camera: MultiCameraClient.ICamera?): Boolean {
        if (camera == null) return false
        if (!hasRegisteredBasePreviewCallback) {
            camera.addPreviewDataCallBack(noOpPreviewCallback)
            hasRegisteredBasePreviewCallback = true
            android.util.Log.d("CameraActivation", "PHASE A COMPLETE: Camera pipeline activated - parameter application enabled")
            return true
        }
        android.util.Log.d("CameraActivation", "Camera pipeline already activated")
        return false
    }

    /**
     * Reset registration state. Call when camera closes so next open can register again.
     */
    fun reset() {
        hasRegisteredBasePreviewCallback = false
    }
}
