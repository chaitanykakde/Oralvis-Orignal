/*
 * Copyright 2017-2023 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jiangdg.ausbc.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.provider.MediaStore
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.MultiCameraClient.Companion.CAPTURE_TIMES_OUT_SEC
import com.jiangdg.ausbc.MultiCameraClient.Companion.MAX_NV21_DATA
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.ausbc.utils.DeviceProfile
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.MediaUtils
import com.jiangdg.ausbc.utils.Utils
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
import java.io.File
import java.util.concurrent.TimeUnit

/** UVC Camera
 *
 * @author Created by jiangdg on 2023/1/15
 */
class CameraUVC(ctx: Context, device: UsbDevice) : MultiCameraClient.ICamera(ctx, device) {
    private var mUvcCamera: UVCCamera? = null
    private val mCameraPreviewSize by lazy {
        arrayListOf<PreviewSize>()
    }

    private val frameCallBack = IFrameCallback { frame ->
        frame?.apply {
            frame.position(0)
            val data = ByteArray(capacity())
            get(data)
            mCameraRequest?.apply {
                if (data.size != previewWidth * previewHeight * 3 / 2) {
                    return@IFrameCallback
                }
                // for preview callback
                mPreviewDataCbList.forEach { cb ->
                    cb?.onPreviewData(data, previewWidth, previewHeight, IPreviewDataCallBack.DataFormat.NV21)
                }
                // for image
                if (mNV21DataQueue.size >= MAX_NV21_DATA) {
                    mNV21DataQueue.removeLast()
                }
                mNV21DataQueue.offerFirst(data)
                // for video
                // avoid preview size changed
                putVideoData(data)
            }
        }
    }

    override fun getAllPreviewSizes(aspectRatio: Double?): MutableList<PreviewSize> {
        Logger.d(TAG, "getAllPreviewSizes() called")
        Logger.d(TAG, "  aspectRatio filter: $aspectRatio")
        Logger.d(TAG, "  mUvcCamera is null: ${mUvcCamera == null}")
        
        val previewSizeList = arrayListOf<PreviewSize>()
        
        // Since tablet mode is disabled, we use MJPEG format primarily
        // Check MJPEG first, then YUYV to get all possible sizes
        // This ensures we check against the format we're actually using
        Logger.d(TAG, "Querying MJPEG format sizes...")
        val mjpegSizes = mUvcCamera?.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG)
        Logger.d(TAG, "  MJPEG sizes returned: ${mjpegSizes?.size ?: 0}")
        if (mjpegSizes != null && mjpegSizes.isNotEmpty()) {
            Logger.d(TAG, "  MJPEG sizes: ${mjpegSizes.take(10).map { "${it.width}x${it.height}" }.joinToString(", ")}${if (mjpegSizes.size > 10) "..." else ""}")
        }
        
        Logger.d(TAG, "Querying YUYV format sizes...")
        val yuyvSizes = mUvcCamera?.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV)
        Logger.d(TAG, "  YUYV sizes returned: ${yuyvSizes?.size ?: 0}")
        if (yuyvSizes != null && yuyvSizes.isNotEmpty()) {
            Logger.d(TAG, "  YUYV sizes: ${yuyvSizes.take(10).map { "${it.width}x${it.height}" }.joinToString(", ")}${if (yuyvSizes.size > 10) "..." else ""}")
        }
        
        // Merge both format sizes to get complete list (deduplicate by width x height)
        val uniqueSizes = mutableSetOf<Pair<Int, Int>>()
        val mergedPreviewSizes = mutableListOf<PreviewSize>()
        
        // Add MJPEG sizes first (primary format)
        mjpegSizes?.forEach { size ->
            val key = Pair(size.width, size.height)
            if (uniqueSizes.add(key)) {
                mergedPreviewSizes.add(PreviewSize(size.width, size.height))
            }
        }
        // Add YUYV sizes (fallback format)
        yuyvSizes?.forEach { size ->
            val key = Pair(size.width, size.height)
            if (uniqueSizes.add(key)) {
                mergedPreviewSizes.add(PreviewSize(size.width, size.height))
            }
        }
        
        Logger.d(TAG, "After merging: ${mergedPreviewSizes.size} unique sizes")
        if (mergedPreviewSizes.isNotEmpty()) {
            Logger.d(TAG, "  Merged sizes: ${mergedPreviewSizes.take(10).map { "${it.width}x${it.height}" }.joinToString(", ")}${if (mergedPreviewSizes.size > 10) "..." else ""}")
        }
        
        // If no sizes found from format queries, try using cached supportedSizeList
        val sizeList = if (mergedPreviewSizes.isNotEmpty()) {
            Logger.d(TAG, "Using merged sizes from format queries")
            mergedPreviewSizes
        } else if (mUvcCamera?.supportedSizeList?.isNotEmpty() == true) {
            Logger.d(TAG, "Using cached supportedSizeList (${mUvcCamera?.supportedSizeList?.size} sizes)")
            // Convert cached sizes to PreviewSize
            val cachedSizes = mutableListOf<PreviewSize>()
            mUvcCamera?.supportedSizeList?.forEach { size ->
                cachedSizes.add(PreviewSize(size.width, size.height))
            }
            Logger.d(TAG, "  Cached sizes: ${cachedSizes.take(10).map { "${it.width}x${it.height}" }.joinToString(", ")}${if (cachedSizes.size > 10) "..." else ""}")
            cachedSizes
        } else {
            Logger.w(TAG, "WARNING: No sizes found from any source!")
            Logger.w(TAG, "  mergedPreviewSizes.isEmpty: ${mergedPreviewSizes.isEmpty()}")
            Logger.w(TAG, "  mUvcCamera?.supportedSizeList: ${mUvcCamera?.supportedSizeList?.size ?: "null or empty"}")
            null
        }
        
        sizeList?.let { sizes ->
            if (mCameraPreviewSize.isEmpty()) {
                Logger.d(TAG, "Updating mCameraPreviewSize cache with ${sizes.size} sizes")
                mCameraPreviewSize.clear()
                mCameraPreviewSize.addAll(sizes)
            } else {
                Logger.d(TAG, "Using cached mCameraPreviewSize (${mCameraPreviewSize.size} sizes)")
            }
            mCameraPreviewSize
        }?.onEach { size ->
            val width = size.width
            val height = size.height
            val ratio = width.toDouble() / height
            if (aspectRatio == null || aspectRatio == ratio) {
                previewSizeList.add(PreviewSize(width, height))
            }
        }
        
        if (Utils.debugCamera) {
            Logger.i(TAG, "aspect ratio = $aspectRatio, getAllPreviewSizes = $previewSizeList, ")
        }
        
        Logger.d(TAG, "getAllPreviewSizes() result:")
        Logger.d(TAG, "  MJPEG sizes: ${mjpegSizes?.size ?: 0}")
        Logger.d(TAG, "  YUYV sizes: ${yuyvSizes?.size ?: 0}")
        Logger.d(TAG, "  Merged unique: ${sizeList?.size ?: 0}")
        Logger.d(TAG, "  After aspect filter: ${previewSizeList.size}")
        if (previewSizeList.isNotEmpty()) {
            Logger.d(TAG, "  Final sizes: ${previewSizeList.map { "${it.width}x${it.height}" }.joinToString(", ")}")
        }

        return previewSizeList
    }

    override fun <T> openCameraInternal(cameraView: T) {
        Logger.d(TAG, "========================================")
        Logger.d(TAG, "CameraUVC.openCameraInternal() CALLED")
        
        if (Utils.isTargetSdkOverP(ctx) && !CameraUtils.hasCameraPermission(ctx)) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "Has no CAMERA permission.")
            Logger.e(TAG,"open camera failed, need Manifest.permission.CAMERA permission when targetSdk>=28")
            return
        }
        if (mCtrlBlock == null) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "Usb control block can not be null ")
            Logger.e(TAG, "openCameraInternal FAILED: mCtrlBlock is null")
            return
        }
        
        // 1. create a UVCCamera
        val request = mCameraRequest!!
        val isTablet = DeviceProfile.isTablet(ctx)
        
        Logger.d(TAG, "Camera request details:")
        Logger.d(TAG, "  previewWidth: ${request.previewWidth}")
        Logger.d(TAG, "  previewHeight: ${request.previewHeight}")
        Logger.d(TAG, "  isTablet: $isTablet")
        Logger.d(TAG, "  mCtrlBlock: ${mCtrlBlock != null}")
        
        try {
            Logger.d(TAG, "Opening UVCCamera...")
            mUvcCamera = UVCCamera().apply {
                open(mCtrlBlock)
            }
            Logger.d(TAG, "UVCCamera opened successfully")
        } catch (e: Exception) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "open camera failed ${e.localizedMessage}")
            Logger.e(TAG, "open camera failed.", e)
            return
        }

        // 2. set preview size and register preview callback
        // Check if requested resolution is directly supported first
        val requestedSize = PreviewSize(request.previewWidth, request.previewHeight)
        Logger.i(TAG, "========================================")
        Logger.i(TAG, "RESOLUTION NEGOTIATION START")
        Logger.i(TAG, "Requested resolution: ${request.previewWidth}x${request.previewHeight}")
        
        Logger.d(TAG, "Querying available resolutions from camera...")
        val availableSizes = getAllPreviewSizes()
        Logger.d(TAG, "Available resolutions count: ${availableSizes.size}")
        Logger.d(TAG, "Available resolutions: ${availableSizes.map { "${it.width}x${it.height}" }.joinToString(", ")}")
        
        Logger.d(TAG, "Checking if requested resolution is supported...")
        val isSupported = isPreviewSizeSupported(requestedSize)
        Logger.d(TAG, "isPreviewSizeSupported(${request.previewWidth}x${request.previewHeight}) = $isSupported")
        
        var previewSize = if (isSupported) {
            // Requested resolution is supported - use it directly, DON'T overwrite mCameraRequest
            Logger.i(TAG, "✓ Requested resolution ${request.previewWidth}x${request.previewHeight} IS SUPPORTED - using it directly")
            requestedSize
        } else {
            // Requested resolution not supported - find suitable size and update request
            Logger.w(TAG, "✗ Requested resolution ${request.previewWidth}x${request.previewHeight} NOT SUPPORTED")
            Logger.d(TAG, "Finding suitable alternative size...")
            val suitableSize = getSuitableSize(request.previewWidth, request.previewHeight)
            Logger.w(TAG, "Found suitable size: ${suitableSize.width}x${suitableSize.height} (requested: ${request.previewWidth}x${request.previewHeight})")
            Logger.d(TAG, "Updating mCameraRequest to suitable size...")
            Logger.d(TAG, "  BEFORE: previewWidth=${mCameraRequest!!.previewWidth}, previewHeight=${mCameraRequest!!.previewHeight}")
            suitableSize.apply {
                mCameraRequest!!.previewWidth = width
                mCameraRequest!!.previewHeight = height
            }
            Logger.d(TAG, "  AFTER: previewWidth=${mCameraRequest!!.previewWidth}, previewHeight=${mCameraRequest!!.previewHeight}")
            suitableSize
        }
        
        Logger.i(TAG, "Final preview size selected: ${previewSize.width}x${previewSize.height}")

        // Decide preview format & frame rate based on device profile.
        // Use a UVC‑friendly FPS range (15–60fps) for all devices.
        // Increased max FPS to allow higher quality if camera supports it.
        val minFps = 15
        val maxFps = 60
        // Phones: favour MJPEG (higher temporal resolution, strong CPU).
        // Tablets: try YUYV first to reduce MJPEG decode cost; fall back
        // to MJPEG automatically if YUYV is not supported by the device.
        val primaryFormat = if (isTablet) {
            UVCCamera.FRAME_FORMAT_YUYV
        } else {
            UVCCamera.FRAME_FORMAT_MJPEG
        }
        val fallbackFormat = if (isTablet) {
            UVCCamera.FRAME_FORMAT_MJPEG
        } else {
            UVCCamera.FRAME_FORMAT_YUYV
        }

        Logger.d(TAG, "Format selection:")
        Logger.d(TAG, "  primaryFormat: ${if (primaryFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"}")
        Logger.d(TAG, "  fallbackFormat: ${if (fallbackFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"}")
        Logger.d(TAG, "  FPS range: $minFps-$maxFps")

        try {
            Logger.i(TAG, "Attempting to set preview size with PRIMARY format")
            Logger.i(TAG, "  Size: ${previewSize.width}x${previewSize.height}")
            Logger.i(TAG, "  Format: ${if (primaryFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"}")
            Logger.i(TAG, "  Requested was: ${request.previewWidth}x${request.previewHeight}")
            
            if (! isPreviewSizeSupported(previewSize)) {
                closeCamera()
                postStateEvent(ICameraStateCallBack.State.ERROR, "unsupported preview size")
                Logger.e(TAG, "FAILED: preview size($previewSize) is not supported")
                Logger.e(TAG, "  mUvcCamera?.supportedSizeList: ${mUvcCamera?.supportedSizeList}")
                return
            }
            
            Logger.d(TAG, "Initializing encode processor for ${previewSize.width}x${previewSize.height}")
            initEncodeProcessor(previewSize.width, previewSize.height)
            
            Logger.d(TAG, "Calling mUvcCamera.setPreviewSize(${previewSize.width}, ${previewSize.height}, $minFps, $maxFps, $primaryFormat, ${UVCCamera.DEFAULT_BANDWIDTH})")
            mUvcCamera?.setPreviewSize(
                previewSize.width,
                previewSize.height,
                minFps,
                maxFps,
                primaryFormat,
                UVCCamera.DEFAULT_BANDWIDTH
            )
            Logger.i(TAG, "✓ setPreviewSize() succeeded with PRIMARY format")
            Logger.d(TAG, "  Actual size set: ${previewSize.width}x${previewSize.height}")
        } catch (e: Exception) {
            Logger.w(TAG, "✗ setPreviewSize() FAILED with PRIMARY format")
            Logger.w(TAG, "  Exception: ${e.javaClass.simpleName}: ${e.message}")
            Logger.w(TAG, "  Stack trace:", e)
            
            try {
                Logger.d(TAG, "Retrying with FALLBACK format...")
                // Retry with fallback format - check if requested size is still supported
                val retryRequestedSize = PreviewSize(request.previewWidth, request.previewHeight)
                Logger.d(TAG, "Checking if requested size ${retryRequestedSize.width}x${retryRequestedSize.height} is supported for fallback format")
                
                previewSize = if (isPreviewSizeSupported(retryRequestedSize)) {
                    // Requested resolution is supported - use it directly
                    Logger.i(TAG, "✓ Retry: Requested resolution ${request.previewWidth}x${request.previewHeight} IS SUPPORTED - using it directly")
                    retryRequestedSize
                } else {
                    // Requested resolution not supported - find suitable size
                    Logger.w(TAG, "✗ Retry: Requested resolution ${request.previewWidth}x${request.previewHeight} NOT SUPPORTED")
                    Logger.d(TAG, "Finding suitable size for fallback format...")
                    val suitableSize = getSuitableSize(request.previewWidth, request.previewHeight)
                    Logger.w(TAG, "Found suitable size: ${suitableSize.width}x${suitableSize.height}")
                    Logger.d(TAG, "Updating mCameraRequest to suitable size...")
                    suitableSize.apply {
                        mCameraRequest!!.previewWidth = width
                        mCameraRequest!!.previewHeight = height
                    }
                    suitableSize
                }
                
                if (! isPreviewSizeSupported(previewSize)) {
                    postStateEvent(ICameraStateCallBack.State.ERROR, "unsupported preview size")
                    closeCamera()
                    Logger.e(TAG, "FAILED: preview size($previewSize) is not supported even for fallback format")
                    Logger.e(TAG, "  mUvcCamera?.supportedSizeList: ${mUvcCamera?.supportedSizeList}")
                    return
                }
                
                Logger.w(TAG, "Attempting setPreviewSize() with FALLBACK format")
                Logger.w(TAG, "  Size: ${previewSize.width}x${previewSize.height}")
                Logger.w(TAG, "  Format: ${if (fallbackFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"}")
                Logger.w(TAG, "  Original request was: ${request.previewWidth}x${request.previewHeight}")
                
                mUvcCamera?.setPreviewSize(
                    previewSize.width,
                    previewSize.height,
                    minFps,
                    maxFps,
                    fallbackFormat,
                    UVCCamera.DEFAULT_BANDWIDTH
                )
                Logger.i(TAG, "✓ setPreviewSize() succeeded with FALLBACK format")
                Logger.d(TAG, "  Actual size set: ${previewSize.width}x${previewSize.height}")
            } catch (e2: Exception) {
                closeCamera()
                val errMsg = "setPreviewSize failed for both primary=$primaryFormat and fallback=$fallbackFormat: ${e2.localizedMessage}"
                postStateEvent(ICameraStateCallBack.State.ERROR, errMsg)
                Logger.e(TAG, "✗✗ FAILED: Both primary and fallback formats failed")
                Logger.e(TAG, "  Primary format exception: ${e.javaClass.simpleName}: ${e.message}")
                Logger.e(TAG, "  Fallback format exception: ${e2.javaClass.simpleName}: ${e2.message}")
                Logger.e(TAG, "  Final preview size attempted: ${previewSize.width}x${previewSize.height}")
                Logger.e(TAG, errMsg, e2)
                return
            }
        }
        
        Logger.i(TAG, "RESOLUTION NEGOTIATION COMPLETE")
        Logger.i(TAG, "  Requested: ${request.previewWidth}x${request.previewHeight}")
        Logger.i(TAG, "  Actual: ${previewSize.width}x${previewSize.height}")
        Logger.i(TAG, "========================================")
        // if not opengl render or opengl render with preview callback
        // there should opened
        if (! isNeedGLESRender || mCameraRequest!!.isRawPreviewData || mCameraRequest!!.isCaptureRawImage) {
            mUvcCamera?.setFrameCallback(frameCallBack, UVCCamera.PIXEL_FORMAT_YUV420SP)
        }
        // 3. start preview
        when(cameraView) {
            is Surface -> {
                mUvcCamera?.setPreviewDisplay(cameraView)
            }
            is SurfaceTexture -> {
                mUvcCamera?.setPreviewTexture(cameraView)
            }
            is SurfaceView -> {
                mUvcCamera?.setPreviewDisplay(cameraView.holder)
            }
            is TextureView -> {
                mUvcCamera?.setPreviewTexture(cameraView.surfaceTexture)
            }
            else -> {
                throw IllegalStateException("Only support Surface or SurfaceTexture or SurfaceView or TextureView or GLSurfaceView--$cameraView")
            }
        }
        mUvcCamera?.autoFocus = true
        mUvcCamera?.autoWhiteBlance = true
        mUvcCamera?.startPreview()
        mUvcCamera?.updateCameraParams()
        isPreviewed = true
        postStateEvent(ICameraStateCallBack.State.OPENED)
        if (Utils.debugCamera) {
            Logger.i(TAG, " start preview, name = ${device.deviceName}, preview=$previewSize")
        }
    }

    override fun closeCameraInternal() {
        postStateEvent(ICameraStateCallBack.State.CLOSED)
        isPreviewed = false
        releaseEncodeProcessor()
        mUvcCamera?.destroy()
        mUvcCamera = null
        if (Utils.debugCamera) {
            Logger.i(TAG, " stop preview, name = ${device.deviceName}")
        }
    }

    override fun captureImageInternal(savePath: String?, callback: ICaptureCallBack) {
        mSaveImageExecutor.submit {
            if (! CameraUtils.hasStoragePermission(ctx)) {
                mMainHandler.post {
                    callback.onError("have no storage permission")
                }
                Logger.e(TAG,"open camera failed, have no storage permission")
                return@submit
            }
            if (! isPreviewed) {
                mMainHandler.post {
                    callback.onError("camera not previewing")
                }
                Logger.i(TAG, "captureImageInternal failed, camera not previewing")
                return@submit
            }
            val data = mNV21DataQueue.pollFirst(CAPTURE_TIMES_OUT_SEC, TimeUnit.SECONDS)
            if (data == null) {
                mMainHandler.post {
                    callback.onError("Times out")
                }
                Logger.i(TAG, "captureImageInternal failed, times out.")
                return@submit
            }
            mMainHandler.post {
                callback.onBegin()
            }
            val date = mDateFormat.format(System.currentTimeMillis())
            val title = savePath ?: "IMG_AUSBC_$date"
            val displayName = savePath ?: "$title.jpg"
            val path = savePath ?: "$mCameraDir/$displayName"
            val location = Utils.getGpsLocation(ctx)
            val width = mCameraRequest!!.previewWidth
            val height = mCameraRequest!!.previewHeight
            val ret = MediaUtils.saveYuv2Jpeg(path, data, width, height)
            if (! ret) {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
                mMainHandler.post {
                    callback.onError("save yuv to jpeg failed.")
                }
                Logger.w(TAG, "save yuv to jpeg failed.")
                return@submit
            }
            val values = ContentValues()
            values.put(MediaStore.Images.ImageColumns.TITLE, title)
            values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, displayName)
            values.put(MediaStore.Images.ImageColumns.DATA, path)
            values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, date)
            values.put(MediaStore.Images.ImageColumns.LONGITUDE, location?.longitude)
            values.put(MediaStore.Images.ImageColumns.LATITUDE, location?.latitude)
            ctx.contentResolver?.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            mMainHandler.post {
                callback.onComplete(path)
            }
            if (Utils.debugCamera) { Logger.i(TAG, "captureImageInternal save path = $path") }
        }
    }

    /**
     * Is mic supported
     *
     * @return true camera support mic
     */
    fun isMicSupported() = CameraUtils.isCameraContainsMic(this.device)

    /**
     * Send camera command
     *
     * This method cannot be verified, please use it with caution
     */
    fun sendCameraCommand(command: Int) {
        mCameraHandler?.post {
            mUvcCamera?.sendCommand(command)
        }
    }

    /**
     * Set auto focus
     *
     * @param enable true enable auto focus
     */
    fun setAutoFocus(enable: Boolean) {
        mUvcCamera?.autoFocus = enable
    }

    /**
     * Get auto focus
     *
     * @return true enable auto focus
     */
    fun getAutoFocus() = mUvcCamera?.autoFocus

    /**
     * Reset auto focus
     */
    fun resetAutoFocus() {
        mUvcCamera?.resetFocus()
    }

    /**
     * Set auto white balance
     *
     * @param autoWhiteBalance true enable auto white balance
     */
    fun setAutoWhiteBalance(autoWhiteBalance: Boolean) {
        mUvcCamera?.autoWhiteBlance = autoWhiteBalance
    }

    /**
     * Get auto white balance
     *
     * @return true enable auto white balance
     */
    fun getAutoWhiteBalance() = mUvcCamera?.autoWhiteBlance

    /**
     * Set zoom
     *
     * @param zoom zoom value, 0 means reset
     */
    fun setZoom(zoom: Int) {
        mUvcCamera?.zoom = zoom
    }

    /**
     * Get zoom
     */
    fun getZoom() = mUvcCamera?.zoom

    /**
     * Reset zoom
     */
    fun resetZoom() {
        mUvcCamera?.resetZoom()
    }

    /**
     * Set gain
     *
     * @param gain gain value, 0 means reset
     */
    fun setGain(gain: Int) {
        mUvcCamera?.gain = gain
    }

    /**
     * Get gain
     */
    fun getGain() = mUvcCamera?.gain

    /**
     * Reset gain
     */
    fun resetGain() {
        mUvcCamera?.resetGain()
    }

    /**
     * Set gamma
     *
     * @param gamma gamma value, 0 means reset
     */
    fun setGamma(gamma: Int) {
        mUvcCamera?.gamma = gamma
    }

    /**
     * Get gamma
     */
    fun getGamma() = mUvcCamera?.gamma

    /**
     * Reset gamma
     */
    fun resetGamma() {
        mUvcCamera?.resetGamma()
    }

    /**
     * Set brightness
     *
     * @param brightness brightness value, 0 means reset
     */
    fun setBrightness(brightness: Int) {
        mUvcCamera?.brightness = brightness
    }

    /**
     * Get brightness
     */
    fun getBrightness() = mUvcCamera?.brightness

    /**
     * Reset brightnes
     */
    fun resetBrightness() {
        mUvcCamera?.resetBrightness()
    }

    /**
     * Set contrast
     *
     * @param contrast contrast value, 0 means reset
     */
    fun setContrast(contrast: Int) {
        mUvcCamera?.contrast = contrast
    }

    /**
     * Get contrast
     */
    fun getContrast() = mUvcCamera?.contrast

    /**
     * Reset contrast
     */
    fun resetContrast() {
        mUvcCamera?.resetContrast()
    }

    /**
     * Set sharpness
     *
     * @param sharpness sharpness value, 0 means reset
     */
    fun setSharpness(sharpness: Int) {
        mUvcCamera?.sharpness = sharpness
    }

    /**
     * Get sharpness
     */
    fun getSharpness() = mUvcCamera?.sharpness

    /**
     * Reset sharpness
     */
    fun resetSharpness() {
        mUvcCamera?.resetSharpness()
    }

    /**
     * Set saturation
     *
     * @param saturation saturation value, 0 means reset
     */
    fun setSaturation(saturation: Int) {
        mUvcCamera?.saturation = saturation
    }

    /**
     * Get saturation
     */
    fun getSaturation() = mUvcCamera?.saturation

    /**
     * Reset saturation
     */
    fun resetSaturation() {
        mUvcCamera?.resetSaturation()
    }

    /**
     * Set hue
     *
     * @param hue hue value, 0 means reset
     */
    fun setHue(hue: Int) {
        mUvcCamera?.hue = hue
    }

    /**
     * Get hue
     */
    fun getHue() = mUvcCamera?.hue

    /**
     * Reset saturation
     */
    fun resetHue() {
        mUvcCamera?.resetHue()
    }

    /**
     * Check if auto exposure is supported by the camera
     *
     * @return true if auto exposure is supported
     */
    fun isAutoExposureSupported(): Boolean {
        return mUvcCamera?.checkSupportFlag(UVCCamera.CTRL_AE.toLong()) ?: false
    }

    /**
     * Set auto exposure mode using reflection to access private native methods
     *
     * @param enable true enable auto exposure
     */
    fun setAutoExposure(enable: Boolean) {
        if (!isAutoExposureSupported()) {
            Logger.w(TAG, "setAutoExposure: Auto exposure not supported by this camera")
            return
        }
        
        if (mUvcCamera == null) {
            Logger.w(TAG, "setAutoExposure: Camera not initialized")
            return
        }
        
        try {
            // Use reflection to access the private nativeSetExposureMode method
            val method = mUvcCamera!!.javaClass.getDeclaredMethod("nativeSetExposureMode", Long::class.java, Int::class.java)
            method.isAccessible = true
            
            // Get the native pointer using reflection
            val nativePtrField = mUvcCamera!!.javaClass.getDeclaredField("mNativePtr")
            nativePtrField.isAccessible = true
            val nativePtr = nativePtrField.getLong(mUvcCamera!!)
            
            if (nativePtr == 0L) {
                Logger.w(TAG, "setAutoExposure: Native pointer is null")
                return
            }
            
            // Set exposure mode: 8 = Auto, 1 = Manual (UVC specification)
            val exposureMode = if (enable) 8 else 1
            val result = method.invoke(mUvcCamera!!, nativePtr, exposureMode) as Int
            
            Logger.d(TAG, "setAutoExposure: enable=$enable, exposureMode=$exposureMode, result=$result")
            
            if (result == 0) {
                Logger.d(TAG, "Auto exposure set successfully")
            } else {
                Logger.w(TAG, "Failed to set auto exposure, result: $result")
            }
        } catch (e: NoSuchMethodException) {
            Logger.e(TAG, "setAutoExposure: nativeSetExposureMode method not found", e)
        } catch (e: NoSuchFieldException) {
            Logger.e(TAG, "setAutoExposure: mNativePtr field not found", e)
        } catch (e: IllegalAccessException) {
            Logger.e(TAG, "setAutoExposure: Access denied to native method", e)
        } catch (e: Exception) {
            Logger.e(TAG, "setAutoExposure failed: ${e.message}", e)
        }
    }

    /**
     * Get auto exposure mode using reflection
     *
     * @return true if auto exposure is enabled
     */
    fun getAutoExposure(): Boolean {
        if (!isAutoExposureSupported()) {
            Logger.w(TAG, "getAutoExposure: Auto exposure not supported by this camera")
            return false
        }
        
        if (mUvcCamera == null) {
            Logger.w(TAG, "getAutoExposure: Camera not initialized")
            return false
        }
        
        try {
            // Use reflection to access the private nativeGetExposureMode method
            val method = mUvcCamera!!.javaClass.getDeclaredMethod("nativeGetExposureMode", Long::class.java)
            method.isAccessible = true
            
            // Get the native pointer using reflection
            val nativePtrField = mUvcCamera!!.javaClass.getDeclaredField("mNativePtr")
            nativePtrField.isAccessible = true
            val nativePtr = nativePtrField.getLong(mUvcCamera!!)
            
            if (nativePtr == 0L) {
                Logger.w(TAG, "getAutoExposure: Native pointer is null")
                return false
            }
            
            val result = method.invoke(mUvcCamera!!, nativePtr) as Int
            val isAuto = result == 8  // UVC specification: 8 = Auto exposure
            
            Logger.d(TAG, "getAutoExposure: result=$result, isAuto=$isAuto")
            return isAuto
        } catch (e: NoSuchMethodException) {
            Logger.e(TAG, "getAutoExposure: nativeGetExposureMode method not found", e)
            return false
        } catch (e: NoSuchFieldException) {
            Logger.e(TAG, "getAutoExposure: mNativePtr field not found", e)
            return false
        } catch (e: IllegalAccessException) {
            Logger.e(TAG, "getAutoExposure: Access denied to native method", e)
            return false
        } catch (e: Exception) {
            Logger.e(TAG, "getAutoExposure failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Check if exposure control is supported by the camera
     *
     * @return true if exposure control is supported
     */
    fun isExposureSupported(): Boolean {
        return mUvcCamera?.checkSupportFlag(UVCCamera.CTRL_AE_ABS.toLong()) ?: false
    }

    /**
     * Set exposure value using reflection to access private native methods
     *
     * @param exposure exposure value (0-100), 0 means reset
     */
    fun setExposure(exposure: Int) {
        if (!isExposureSupported()) {
            Logger.w(TAG, "setExposure: Exposure control not supported by this camera")
            return
        }
        
        if (mUvcCamera == null) {
            Logger.w(TAG, "setExposure: Camera not initialized")
            return
        }
        
        // Validate input range
        val clampedExposure = exposure.coerceIn(0, 100)
        if (clampedExposure != exposure) {
            Logger.w(TAG, "setExposure: Input $exposure clamped to $clampedExposure")
        }
        
        // Performance monitoring
        val startTime = System.currentTimeMillis()
        
        try {
            // First update exposure limits to get min/max values
            updateExposureLimits()
            
            // Get exposure limits using reflection
            val minField = mUvcCamera!!.javaClass.getDeclaredField("mExposureMin")
            val maxField = mUvcCamera!!.javaClass.getDeclaredField("mExposureMax")
            minField.isAccessible = true
            maxField.isAccessible = true
            
            val minExposure = minField.getInt(mUvcCamera!!)
            val maxExposure = maxField.getInt(mUvcCamera!!)
            
            Logger.d(TAG, "setExposure: input=$clampedExposure, min=$minExposure, max=$maxExposure")
            
            // Validate exposure limits
            if (minExposure >= maxExposure) {
                Logger.w(TAG, "setExposure: Invalid exposure limits: min=$minExposure, max=$maxExposure")
                return
            }
            
            // Convert percentage to actual exposure value
            val actualExposure = when {
                clampedExposure <= 0 -> minExposure  // Reset to minimum
                clampedExposure >= 100 -> maxExposure  // Maximum
                else -> {
                    val range = maxExposure - minExposure
                    (minExposure + (clampedExposure / 100.0 * range)).toInt()
                }
            }
            
            // Ensure actual exposure is within valid range
            val finalExposure = actualExposure.coerceIn(minExposure, maxExposure)
            
            // Use reflection to access the private nativeSetExposure method
            val method = mUvcCamera!!.javaClass.getDeclaredMethod("nativeSetExposure", Long::class.java, Int::class.java)
            method.isAccessible = true
            
            // Get the native pointer using reflection
            val nativePtrField = mUvcCamera!!.javaClass.getDeclaredField("mNativePtr")
            nativePtrField.isAccessible = true
            val nativePtr = nativePtrField.getLong(mUvcCamera!!)
            
            if (nativePtr == 0L) {
                Logger.w(TAG, "setExposure: Native pointer is null")
                return
            }
            
            val result = method.invoke(mUvcCamera!!, nativePtr, finalExposure) as Int
            
            Logger.d(TAG, "setExposure: finalExposure=$finalExposure, result=$result")
            
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            
            if (result == 0) {
                Logger.d(TAG, "Exposure set successfully in ${duration}ms")
                if (duration > 50) {
                    Logger.w(TAG, "setExposure: Slow operation detected (${duration}ms) - consider throttling")
                }
            } else {
                Logger.w(TAG, "Failed to set exposure, result: $result (${duration}ms)")
            }
        } catch (e: NoSuchMethodException) {
            Logger.e(TAG, "setExposure: nativeSetExposure method not found", e)
        } catch (e: NoSuchFieldException) {
            Logger.e(TAG, "setExposure: Exposure limit fields not found", e)
        } catch (e: IllegalAccessException) {
            Logger.e(TAG, "setExposure: Access denied to native method", e)
        } catch (e: Exception) {
            Logger.e(TAG, "setExposure failed: ${e.message}", e)
        }
    }

    /**
     * Get exposure value using reflection
     *
     * @return exposure value (0-100) or -1 if not supported
     */
    fun getExposure(): Int {
        if (!isExposureSupported()) {
            Logger.w(TAG, "getExposure: Exposure control not supported by this camera")
            return -1
        }
        
        if (mUvcCamera == null) {
            Logger.w(TAG, "getExposure: Camera not initialized")
            return -1
        }
        
        try {
            // Use reflection to access the private nativeGetExposure method
            val method = mUvcCamera!!.javaClass.getDeclaredMethod("nativeGetExposure", Long::class.java)
            method.isAccessible = true
            
            // Get the native pointer using reflection
            val nativePtrField = mUvcCamera!!.javaClass.getDeclaredField("mNativePtr")
            nativePtrField.isAccessible = true
            val nativePtr = nativePtrField.getLong(mUvcCamera!!)
            
            if (nativePtr == 0L) {
                Logger.w(TAG, "getExposure: Native pointer is null")
                return -1
            }
            
            val actualExposure = method.invoke(mUvcCamera!!, nativePtr) as Int
            
            // Get exposure limits to convert back to percentage
            val minField = mUvcCamera!!.javaClass.getDeclaredField("mExposureMin")
            val maxField = mUvcCamera!!.javaClass.getDeclaredField("mExposureMax")
            minField.isAccessible = true
            maxField.isAccessible = true
            
            val minExposure = minField.getInt(mUvcCamera!!)
            val maxExposure = maxField.getInt(mUvcCamera!!)
            
            // Validate exposure limits
            if (minExposure >= maxExposure) {
                Logger.w(TAG, "getExposure: Invalid exposure limits: min=$minExposure, max=$maxExposure")
                return 50  // Return default value
            }
            
            val percentage = if (maxExposure > minExposure) {
                ((actualExposure - minExposure) * 100.0 / (maxExposure - minExposure)).toInt().coerceIn(0, 100)
            } else {
                50
            }
            
            Logger.d(TAG, "getExposure: actualExposure=$actualExposure, percentage=$percentage")
            return percentage
        } catch (e: NoSuchMethodException) {
            Logger.e(TAG, "getExposure: nativeGetExposure method not found", e)
            return -1
        } catch (e: NoSuchFieldException) {
            Logger.e(TAG, "getExposure: Exposure limit fields not found", e)
            return -1
        } catch (e: IllegalAccessException) {
            Logger.e(TAG, "getExposure: Access denied to native method", e)
            return -1
        } catch (e: Exception) {
            Logger.e(TAG, "getExposure failed: ${e.message}", e)
            return -1
        }
    }

    /**
     * Update exposure limits using reflection
     */
    private fun updateExposureLimits() {
        if (mUvcCamera == null) {
            Logger.w(TAG, "updateExposureLimits: Camera not initialized")
            return
        }
        
        try {
            // Use reflection to access the private nativeUpdateExposureLimit method
            val method = mUvcCamera!!.javaClass.getDeclaredMethod("nativeUpdateExposureLimit", Long::class.java)
            method.isAccessible = true
            
            // Get the native pointer using reflection
            val nativePtrField = mUvcCamera!!.javaClass.getDeclaredField("mNativePtr")
            nativePtrField.isAccessible = true
            val nativePtr = nativePtrField.getLong(mUvcCamera!!)
            
            if (nativePtr == 0L) {
                Logger.w(TAG, "updateExposureLimits: Native pointer is null")
                return
            }
            
            val result = method.invoke(mUvcCamera!!, nativePtr) as Int
            Logger.d(TAG, "updateExposureLimits: result=$result")
            
            if (result != 0) {
                Logger.w(TAG, "updateExposureLimits: Failed to update limits, result: $result")
            }
        } catch (e: NoSuchMethodException) {
            Logger.e(TAG, "updateExposureLimits: nativeUpdateExposureLimit method not found", e)
        } catch (e: NoSuchFieldException) {
            Logger.e(TAG, "updateExposureLimits: mNativePtr field not found", e)
        } catch (e: IllegalAccessException) {
            Logger.e(TAG, "updateExposureLimits: Access denied to native method", e)
        } catch (e: Exception) {
            Logger.e(TAG, "updateExposureLimits failed: ${e.message}", e)
        }
    }

    /**
     * Reset exposure
     */
    fun resetExposure() {
        if (!isExposureSupported()) {
            Logger.w(TAG, "resetExposure: Exposure control not supported by this camera")
            return
        }
        
        if (mUvcCamera == null) {
            Logger.w(TAG, "resetExposure: Camera not initialized")
            return
        }
        
        try {
            // First update exposure limits to get default value
            updateExposureLimits()
            
            // Get default exposure value using reflection
            val defField = mUvcCamera!!.javaClass.getDeclaredField("mExposureDef")
            defField.isAccessible = true
            val defaultExposure = defField.getInt(mUvcCamera!!)
            
            // Use reflection to access the private nativeSetExposure method
            val method = mUvcCamera!!.javaClass.getDeclaredMethod("nativeSetExposure", Long::class.java, Int::class.java)
            method.isAccessible = true
            
            // Get the native pointer using reflection
            val nativePtrField = mUvcCamera!!.javaClass.getDeclaredField("mNativePtr")
            nativePtrField.isAccessible = true
            val nativePtr = nativePtrField.getLong(mUvcCamera!!)
            
            if (nativePtr == 0L) {
                Logger.w(TAG, "resetExposure: Native pointer is null")
                return
            }
            
            val result = method.invoke(mUvcCamera!!, nativePtr, defaultExposure) as Int
            Logger.d(TAG, "resetExposure: defaultExposure=$defaultExposure, result=$result")
            
            if (result == 0) {
                Logger.d(TAG, "Exposure reset successfully")
            } else {
                Logger.w(TAG, "Failed to reset exposure, result: $result")
            }
        } catch (e: NoSuchMethodException) {
            Logger.e(TAG, "resetExposure: nativeSetExposure method not found", e)
        } catch (e: NoSuchFieldException) {
            Logger.e(TAG, "resetExposure: mExposureDef or mNativePtr field not found", e)
        } catch (e: IllegalAccessException) {
            Logger.e(TAG, "resetExposure: Access denied to native method", e)
        } catch (e: Exception) {
            Logger.e(TAG, "resetExposure failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "CameraUVC"
        // Default phone-friendly FPS bounds; tablets override via DeviceProfile.
        // Increased MAX_FPS to allow higher quality if camera supports it.
        private const val MIN_FS = 15
        private const val MAX_FPS = 60
    }
}