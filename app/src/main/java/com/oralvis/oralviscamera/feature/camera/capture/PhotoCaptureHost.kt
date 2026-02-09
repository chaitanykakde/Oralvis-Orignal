package com.oralvis.oralviscamera.feature.camera.capture

import com.jiangdg.ausbc.MultiCameraClient

/**
 * Host callbacks for PhotoCaptureHandler (Phase 5C). Implemented by MainActivity.
 */
interface PhotoCaptureHost {
    fun runOnMain(r: Runnable)
    fun showToast(message: String)
    fun isActivityValid(): Boolean
    fun createImageFile(): String
    fun refreshCameraReference()
    fun isCameraReadyForPhotoCapture(): Boolean
    fun captureRealImageToRepository(
        filePath: String,
        mediaType: String,
        guidedSessionId: String?,
        dentalArch: String?,
        sequenceNumber: Int?
    )
    fun getCurrentMode(): String
    fun addSessionMedia(filePath: String, isVideo: Boolean)
    fun getPatientId(): Long?
    fun getSessionId(): String?
    fun getCamera(): MultiCameraClient.ICamera?
}
