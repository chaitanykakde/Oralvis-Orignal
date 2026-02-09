package com.oralvis.oralviscamera.feature.camera.preview

import com.jiangdg.ausbc.MultiCameraClient
import com.oralvis.oralviscamera.feature.guided.GuidedController

/**
 * Routes preview callbacks: base (via provider) and guided (Phase 5B).
 * Performs all camera.addPreviewDataCallBack / removePreviewDataCallBack; GuidedController does not touch camera APIs.
 */
class PreviewCallbackRouter(
    private val guidedControllerProvider: () -> GuidedController?,
    private val baseProvider: BasePreviewCallbackProvider
) {
    /**
     * Ensure base no-op callback is registered, then add guided callback if guided session is active.
     * Call when camera opens (OPENED). Preserves exact add order: base first, then guided if active.
     */
    fun onCameraOpened(camera: MultiCameraClient.ICamera) {
        baseProvider.registerIfNeeded(camera)
        val guided = guidedControllerProvider() ?: return
        if (guided.isGuidedActive()) {
            guided.getPreviewCallback()?.let { camera.addPreviewDataCallBack(it) }
        }
    }

    /**
     * Remove guided callback and reset base registration state.
     * Call when camera closes (CLOSED).
     */
    fun onCameraClosed(camera: MultiCameraClient.ICamera?) {
        val guided = guidedControllerProvider() ?: return
        guided.getPreviewCallback()?.let { callback -> camera?.removePreviewDataCallBack(callback) }
        baseProvider.reset()
    }

    /**
     * Remove guided callback only (e.g. on activity destroy). Does not reset base provider.
     */
    fun detachFromCamera(camera: MultiCameraClient.ICamera?) {
        val guided = guidedControllerProvider() ?: return
        guided.getPreviewCallback()?.let { callback -> camera?.removePreviewDataCallBack(callback) }
    }

    /**
     * Enable guided capture (controller) and attach its preview callback to camera. Router performs add.
     */
    fun enableGuided(camera: MultiCameraClient.ICamera?) {
        val guided = guidedControllerProvider() ?: return
        guided.enable(camera)
        if (camera != null && guided.isGuidedActive()) {
            guided.getPreviewCallback()?.let { camera.addPreviewDataCallBack(it) }
        }
    }
}
