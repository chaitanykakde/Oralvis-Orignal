package com.oralvis.oralviscamera.feature.camera.capture

import android.os.Handler
import android.os.Looper
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICaptureCallBack
import java.io.FileOutputStream
import java.util.Date

/**
 * Video capture and recording timer logic extracted from MainActivity (Phase 5C).
 */
class VideoCaptureHandler(
    private val host: VideoCaptureHost
) {
    @Volatile
    var isRecording: Boolean = false
        private set

    private var recordingStartTime = 0L
    private var recordingRunnable: Runnable? = null

    fun toggleRecordingWithRetry() = toggleRecordingWithRetry(0)

    fun toggleRecordingWithRetry(retryCount: Int) {
        val maxRetries = 3
        val retryDelay = 1000L

        android.util.Log.d("RecordingManager", "toggleRecordingWithRetry: attempt ${retryCount + 1}/$maxRetries")

        if (host.getCamera() == null) {
            android.util.Log.d("RecordingManager", "Camera reference is null, attempting to refresh...")
            host.refreshCameraReference()
        }

        if (!host.isCameraReadyForRecording()) {
            if (retryCount < maxRetries) {
                android.util.Log.w("RecordingManager", "Camera not ready, retrying in ${retryDelay}ms...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    toggleRecordingWithRetry(retryCount + 1)
                }, retryDelay)
                return
            } else {
                android.util.Log.e("RecordingManager", "Camera not ready after $maxRetries attempts")
                host.showToast("Camera not ready for recording - please try again")
                return
            }
        }

        toggleRecording()
    }

    fun toggleRecording() {
        if (host.getCamera() == null) {
            android.util.Log.d("RecordingManager", "Camera reference is null, attempting to refresh...")
            host.refreshCameraReference()
        }

        if (!host.isCameraReadyForRecording()) {
            android.util.Log.w("RecordingManager", "Camera not ready for recording")
            host.showToast("Camera not ready for recording - please try again")
            return
        }

        host.getCamera()?.let { camera ->
            if (!isRecording) {
                try {
                    android.util.Log.d("RecordingManager", "Starting video recording...")
                    val videoFile = host.createVideoFile()
                    camera.captureVideoStart(object : ICaptureCallBack {
                        override fun onBegin() {
                            if (host.isActivityValid()) {
                                host.runOnMain(Runnable {
                                    if (host.isActivityValid()) {
                                        isRecording = true
                                        startRecordingTimer()
                                        host.showToast("Recording started")
                                        android.util.Log.d("RecordingManager", "Recording started successfully")
                                    }
                                })
                            }
                        }

                        override fun onError(error: String?) {
                            if (host.isActivityValid()) {
                                host.runOnMain(Runnable {
                                    if (host.isActivityValid()) {
                                        isRecording = false
                                        host.setRecordButtonBackground(com.oralvis.oralviscamera.R.drawable.button_gradient_darkred)
                                        stopRecordingTimer()
                                        host.showToast("Recording failed: $error")
                                        android.util.Log.e("RecordingManager", "Recording failed: $error")
                                    }
                                })
                            }
                        }

                        override fun onComplete(path: String?) {
                            if (host.isActivityValid()) {
                                host.runOnMain(Runnable {
                                    if (host.isActivityValid()) {
                                        isRecording = false
                                        host.setRecordButtonBackground(com.oralvis.oralviscamera.R.drawable.button_gradient_darkred)
                                        stopRecordingTimer()
                                        val finalPath = path ?: videoFile
                                        host.showToast("Video saved")
                                        host.captureRealImageToRepository(finalPath, "Video")
                                        android.util.Log.d("RecordingManager", "Recording completed: $finalPath")
                                    }
                                })
                            }
                        }
                    }, videoFile)
                } catch (e: Exception) {
                    android.util.Log.e("RecordingManager", "Recording failed with exception: ${e.message}")
                    host.showToast("Recording failed: ${e.message}")
                }
            } else {
                try {
                    android.util.Log.d("RecordingManager", "Stopping video recording...")
                    camera.captureVideoStop()
                    isRecording = false
                    host.setRecordButtonBackground(com.oralvis.oralviscamera.R.drawable.button_gradient_darkred)
                    stopRecordingTimer()
                    host.showToast("Recording stopped")
                    android.util.Log.d("RecordingManager", "Recording stopped successfully")
                } catch (e: Exception) {
                    android.util.Log.e("RecordingManager", "Stop recording failed with exception: ${e.message}")
                    stopRecordingTimer()
                    host.showToast("Stop recording failed: ${e.message}")
                }
            }
        } ?: run {
            android.util.Log.w("RecordingManager", "Camera not available for recording")
            host.showToast("Camera not connected")
        }
    }

    fun startRecordingTimer() {
        recordingStartTime = System.currentTimeMillis()
        host.setRecordingTimerVisible(true)

        val handler = host.getMainHandler()
        recordingRunnable = object : Runnable {
            override fun run() {
                val elapsedTime = System.currentTimeMillis() - recordingStartTime
                val seconds = (elapsedTime / 1000) % 60
                val minutes = (elapsedTime / (1000 * 60)) % 60
                val hours = (elapsedTime / (1000 * 60 * 60)) % 24
                val timeString = if (hours > 0) {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }
                host.updateRecordingTimerText(timeString)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(recordingRunnable!!)
    }

    fun stopRecordingTimer() {
        val handler = host.getMainHandler()
        recordingRunnable?.let { handler.removeCallbacks(it) }
        recordingRunnable = null
        host.setRecordingTimerVisible(false)
        host.updateRecordingTimerText("00:00")
    }

    /** Call when camera closes to reset recording UI and state. */
    fun resetRecordingState() {
        if (isRecording) {
            android.util.Log.d("RecordingManager", "Camera closed during recording - resetting recording state")
            isRecording = false
            host.setRecordButtonBackground(com.oralvis.oralviscamera.R.drawable.button_gradient_darkred)
            stopRecordingTimer()
        }
    }

    fun captureBlankVideo() {
        try {
            android.util.Log.d("BlankCapture", "Starting blank video recording (no camera connected)...")
            isRecording = true
            startRecordingTimer()
            host.showToast("Recording started (blank video)")

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val videoPath = host.createVideoFile()
                    android.util.Log.d("BlankCapture", "Video path created: $videoPath")
                    val videoFile = java.io.File(videoPath)
                    val parentDir = videoFile.parentFile
                    android.util.Log.d("BlankCapture", "Video parent directory exists: ${parentDir?.exists()}")
                    android.util.Log.d("BlankCapture", "Video parent directory path: ${parentDir?.absolutePath}")
                    android.util.Log.d("BlankCapture", "Creating video file: $videoPath")
                    val fileCreated = videoFile.createNewFile()
                    android.util.Log.d("BlankCapture", "Video file created: $fileCreated")

                    val outputStream = FileOutputStream(videoFile)
                    val content = "Blank Video - ${host.getCurrentMode()} Mode - No Camera Connected\nGenerated at: ${Date()}\nThis is a placeholder video file."
                    outputStream.write(content.toByteArray())
                    outputStream.flush()
                    outputStream.close()

                    val fileExists = videoFile.exists()
                    val fileSize = if (fileExists) videoFile.length() else 0
                    android.util.Log.d("BlankCapture", "Video file exists after save: $fileExists, size: $fileSize bytes")

                    Handler(Looper.getMainLooper()).postDelayed({
                        android.util.Log.d("BlankCapture", "Logging blank video to database: $videoPath")
                        host.logMediaToDatabase(videoPath, "Video")
                        host.addSessionMedia(videoPath, true)
                        host.showToast("Video recorded")
                        android.util.Log.d("BlankCapture", "Blank video recorded successfully: $videoPath")
                    }, 100)

                    isRecording = false
                    host.setRecordButtonBackground(com.oralvis.oralviscamera.R.drawable.button_gradient_darkred)
                    stopRecordingTimer()
                } catch (e: Exception) {
                    android.util.Log.e("BlankCapture", "Failed to create blank video file: ${e.message}")
                    host.showToast("Failed to create blank video: ${e.message}")
                    isRecording = false
                    host.setRecordButtonBackground(com.oralvis.oralviscamera.R.drawable.button_gradient_darkred)
                    stopRecordingTimer()
                }
            }, 3000)
        } catch (e: Exception) {
            android.util.Log.e("BlankCapture", "Failed to start blank video recording: ${e.message}")
            host.showToast("Failed to start blank video recording: ${e.message}")
        }
    }
}
