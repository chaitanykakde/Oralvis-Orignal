package com.oralvis.oralviscamera.feature.camera.state

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.bean.CameraRequest

/**
 * Holds camera lifecycle state moved from MainActivity (Phase 5A/5B).
 * No behavior change — ownership of flags and references only.
 */
class CameraStateStore {
    var isCameraReady: Boolean = false
    var hasReceivedFirstFrame: Boolean = false
    var isSurfaceTextureReady: Boolean = false
    var mCurrentCamera: MultiCameraClient.ICamera? = null
    var mPendingCameraOpen: Pair<MultiCameraClient.ICamera, CameraRequest>? = null
}
