package com.oralvis.oralviscamera.feature.camera

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView

/**
 * CameraController
 *
 * Phase 1 (structure-only) pass-through controller that wraps
 * MultiCameraClient camera operations needed by the main screen.
 *
 * This class must not change behavior or add new logic; it is a
 * thin indirection layer that can later be used by a ViewModel.
 */
class CameraController(
    private val cameraClient: MultiCameraClient
) {

    fun openCamera(
        camera: MultiCameraClient.ICamera,
        textureView: AspectRatioTextureView,
        request: CameraRequest
    ) {
        camera.openCamera(textureView, request)
    }

    fun closeCamera(camera: MultiCameraClient.ICamera) {
        camera.closeCamera()
    }
}

