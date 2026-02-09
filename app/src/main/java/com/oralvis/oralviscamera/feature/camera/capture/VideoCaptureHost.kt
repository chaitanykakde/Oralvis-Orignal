package com.oralvis.oralviscamera.feature.camera.capture

import android.os.Handler
import com.jiangdg.ausbc.MultiCameraClient

/**
 * Host callbacks for VideoCaptureHandler (Phase 5C). Implemented by MainActivity.
 */
interface VideoCaptureHost {
    fun runOnMain(r: Runnable)
    fun showToast(message: String)
    fun isActivityValid(): Boolean
    fun createVideoFile(): String
    fun refreshCameraReference()
    fun isCameraReadyForRecording(): Boolean
    fun captureRealImageToRepository(filePath: String, mediaType: String)
    fun getCurrentMode(): String
    fun getCamera(): MultiCameraClient.ICamera?
    fun setRecordButtonBackground(resId: Int)
    fun setRecordingTimerVisible(visible: Boolean)
    fun updateRecordingTimerText(text: String)
    fun getMainHandler(): Handler
    fun addSessionMedia(filePath: String, isVideo: Boolean)
    fun logMediaToDatabase(filePath: String, mediaType: String)
}
