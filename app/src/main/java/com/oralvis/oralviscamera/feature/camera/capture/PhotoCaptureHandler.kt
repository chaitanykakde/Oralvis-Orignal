package com.oralvis.oralviscamera.feature.camera.capture

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.oralvis.oralviscamera.database.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Photo capture logic extracted from MainActivity (Phase 5C). Owns capture lock and retry.
 */
class PhotoCaptureHandler(
    private val mediaRepository: MediaRepository,
    private val host: PhotoCaptureHost,
    private val scope: CoroutineScope
) {
    private val captureLock = AtomicBoolean(false)

    fun capturePhotoWithRetry() = capturePhotoWithRetry(0)

    fun capturePhotoWithRetry(retryCount: Int) {
        val maxRetries = 3
        val retryDelay = 1000L

        android.util.Log.d("PhotoManager", "capturePhotoWithRetry: attempt ${retryCount + 1}/$maxRetries")

        if (host.getCamera() == null) {
            android.util.Log.d("PhotoManager", "Camera reference is null, attempting to refresh...")
            host.refreshCameraReference()
        }

        if (!host.isCameraReadyForPhotoCapture()) {
            if (retryCount < maxRetries) {
                android.util.Log.w("PhotoManager", "Camera not ready, retrying in ${retryDelay}ms...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    capturePhotoWithRetry(retryCount + 1)
                }, retryDelay)
                return
            } else {
                android.util.Log.e("PhotoManager", "Camera not ready after $maxRetries attempts")
                host.showToast("Camera not ready for photo capture - please try again")
                return
            }
        }

        capturePhoto()
    }

    fun capturePhotoWithGuidedMetadata(
        guidedSessionId: String,
        dentalArch: String,
        sequenceNumber: Int
    ) {
        if (!host.isCameraReadyForPhotoCapture()) {
            android.util.Log.w("PhotoManager", "Camera not ready for guided photo capture")
            host.showToast("Camera not ready for guided capture - please try again")
            return
        }

        host.getCamera()?.let { camera ->
            try {
                android.util.Log.d("PhotoManager", "Starting guided photo capture...")
                val imagePath = host.createImageFile()
                camera.captureImage(object : ICaptureCallBack {
                    override fun onBegin() {
                        if (host.isActivityValid()) {
                            host.runOnMain(Runnable {
                                if (host.isActivityValid()) {
                                    host.showToast("Capturing photo...")
                                }
                            })
                        }
                    }

                    override fun onError(error: String?) {
                        if (host.isActivityValid()) {
                            host.runOnMain(Runnable {
                                if (host.isActivityValid()) {
                                    host.showToast("Capture failed: $error")
                                }
                            })
                        }
                    }

                    override fun onComplete(path: String?) {
                        if (host.isActivityValid()) {
                            host.runOnMain(Runnable {
                                if (host.isActivityValid()) {
                                    val finalPath = path ?: imagePath
                                    android.util.Log.d("GuidedCapture", "Guided photo captured - dentalArch: $dentalArch, sequenceNumber: $sequenceNumber, guidedSessionId: $guidedSessionId")
                                    host.showToast("Photo saved")
                                    host.captureRealImageToRepository(finalPath, "Image", guidedSessionId, dentalArch, sequenceNumber)
                                }
                            })
                        }
                    }
                }, imagePath)
            } catch (e: Exception) {
                android.util.Log.e("PhotoManager", "Guided photo capture failed: ${e.message}")
                host.showToast("Capture failed: ${e.message}")
            }
        } ?: run {
            android.util.Log.w("PhotoManager", "Camera not available for guided photo capture")
            host.showToast("Camera not connected")
        }
    }

    fun capturePhoto() {
        if (host.getCamera() == null) {
            android.util.Log.d("PhotoManager", "Camera reference is null, attempting to refresh...")
            host.refreshCameraReference()
        }

        if (!host.isCameraReadyForPhotoCapture()) {
            android.util.Log.w("PhotoManager", "Camera not ready for photo capture")
            host.showToast("Camera not ready for photo capture - please try again")
            return
        }

        host.getCamera()?.let { camera ->
            try {
                android.util.Log.d("PhotoManager", "Starting photo capture...")
                val imagePath = host.createImageFile()
                camera.captureImage(object : ICaptureCallBack {
                    override fun onBegin() {
                        if (host.isActivityValid()) {
                            host.runOnMain(Runnable {
                                if (host.isActivityValid()) {
                                    host.showToast("Capturing photo...")
                                    android.util.Log.d("PhotoManager", "Photo capture started")
                                }
                            })
                        }
                    }

                    override fun onError(error: String?) {
                        if (host.isActivityValid()) {
                            host.runOnMain(Runnable {
                                if (host.isActivityValid()) {
                                    host.showToast("Capture failed: $error")
                                    android.util.Log.e("PhotoManager", "Photo capture failed: $error")
                                }
                            })
                        }
                    }

                    override fun onComplete(path: String?) {
                        if (host.isActivityValid()) {
                            host.runOnMain(Runnable {
                                if (host.isActivityValid()) {
                                    val finalPath = path ?: imagePath
                                    host.showToast("Photo saved")
                                    host.captureRealImageToRepository(finalPath, "Image", null, null, null)
                                    android.util.Log.d("PhotoManager", "Photo capture completed: $finalPath")
                                }
                            })
                        }
                    }
                }, imagePath)
            } catch (e: Exception) {
                android.util.Log.e("PhotoManager", "Photo capture failed with exception: ${e.message}")
                host.showToast("Capture failed: ${e.message}")
            }
        } ?: run {
            android.util.Log.w("PhotoManager", "Camera not available for photo capture")
            host.showToast("Camera not connected")
        }
    }

    fun captureBlankImage() {
        scope.launch {
            try {
                android.util.Log.d("BlankCapture", "Capturing blank image (no camera connected)...")
                val patientId = host.getPatientId()
                if (patientId == null) {
                    android.util.Log.w("BlankCapture", "No patient selected, cannot capture")
                    host.runOnMain(Runnable { host.showToast("Please select a patient first") })
                    return@launch
                }
                val sessionId = host.getSessionId()
                android.util.Log.d("BlankCapture", "Patient ID: $patientId, Session ID: $sessionId")

                val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLACK)
                val paint = Paint().apply {
                    color = Color.WHITE
                    textSize = 60f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                val text = "Blank Image\n${host.getCurrentMode()} Mode\nNo Camera Connected"
                val textBounds = Rect()
                paint.getTextBounds(text, 0, text.length, textBounds)
                val x = bitmap.width / 2f
                val y = bitmap.height / 2f + textBounds.height() / 2f
                canvas.drawText(text, x, y, paint)

                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val imageData = outputStream.toByteArray()
                outputStream.close()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "OralVis_Image_${host.getCurrentMode()}_$timestamp.jpg"

                val mediaRecord = withContext(Dispatchers.IO) {
                    mediaRepository.createMediaRecord(
                        patientId = patientId,
                        sessionId = sessionId,
                        mediaType = "Image",
                        mode = host.getCurrentMode(),
                        fileName = fileName,
                        fileContent = imageData
                    )
                }

                if (mediaRecord != null) {
                    android.util.Log.d("BlankCapture", "Media record created: ${mediaRecord.mediaId}")
                    host.runOnMain(Runnable {
                        mediaRecord.filePath?.let { host.addSessionMedia(it, false) }
                        host.showToast("Image captured")
                    })
                    android.util.Log.d("BlankCapture", "Blank image captured successfully: ${mediaRecord.mediaId}")
                } else {
                    android.util.Log.e("BlankCapture", "Failed to create media record")
                    host.runOnMain(Runnable { host.showToast("Failed to capture blank image") })
                }
            } catch (e: Exception) {
                android.util.Log.e("BlankCapture", "Failed to capture blank image: ${e.message}", e)
                host.runOnMain(Runnable { host.showToast("Failed to capture blank image: ${e.message}") })
            }
        }
    }

    fun captureBlankImageWithGuidedMetadata(
        guidedSessionId: String,
        dentalArch: String,
        sequenceNumber: Int
    ) {
        scope.launch {
            try {
                android.util.Log.d("BlankCapture", "Capturing guided blank image (no camera connected)...")
                android.util.Log.d("BlankCapture", "Guided metadata - dentalArch: $dentalArch, sequenceNumber: $sequenceNumber, guidedSessionId: $guidedSessionId")
                val patientId = host.getPatientId()
                if (patientId == null) {
                    android.util.Log.w("BlankCapture", "No patient selected, cannot capture")
                    host.runOnMain(Runnable { host.showToast("Please select a patient first") })
                    return@launch
                }
                val sessionId = host.getSessionId()
                android.util.Log.d("BlankCapture", "Patient ID: $patientId, Session ID: $sessionId")

                val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLACK)
                val paint = Paint().apply {
                    color = Color.WHITE
                    textSize = 60f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                val text = "Blank Guided Image\n${host.getCurrentMode()} Mode\nNo Camera Connected"
                val textBounds = Rect()
                paint.getTextBounds(text, 0, text.length, textBounds)
                val x = bitmap.width / 2f
                val y = bitmap.height / 2f + textBounds.height() / 2f
                canvas.drawText(text, x, y, paint)

                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val imageData = outputStream.toByteArray()
                outputStream.close()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "OralVis_Image_${host.getCurrentMode()}_$timestamp.jpg"

                val mediaRecord = withContext(Dispatchers.IO) {
                    mediaRepository.createMediaRecord(
                        patientId = patientId,
                        sessionId = sessionId,
                        mediaType = "Image",
                        mode = host.getCurrentMode(),
                        fileName = fileName,
                        dentalArch = dentalArch,
                        sequenceNumber = sequenceNumber,
                        guidedSessionId = guidedSessionId,
                        fileContent = imageData
                    )
                }

                if (mediaRecord != null) {
                    android.util.Log.d("BlankCapture", "Guided media record created: ${mediaRecord.mediaId}")
                    host.runOnMain(Runnable {
                        mediaRecord.filePath?.let { host.addSessionMedia(it, false) }
                        host.showToast("Guided blank image captured")
                    })
                    android.util.Log.d("BlankCapture", "Guided blank image captured successfully: ${mediaRecord.mediaId}")
                } else {
                    android.util.Log.e("BlankCapture", "Failed to create guided media record")
                    host.runOnMain(Runnable { host.showToast("Failed to capture guided blank image") })
                }
            } catch (e: Exception) {
                android.util.Log.e("BlankCapture", "Failed to capture guided blank image: ${e.message}", e)
                host.runOnMain(Runnable { host.showToast("Failed to capture blank image: ${e.message}") })
            }
        }
    }

    /** Returns true if lock was acquired. Caller must call releaseCaptureLock() in finally. */
    fun tryAcquireCaptureLock(): Boolean = captureLock.compareAndSet(false, true)

    fun releaseCaptureLock() {
        captureLock.set(false)
    }
}
