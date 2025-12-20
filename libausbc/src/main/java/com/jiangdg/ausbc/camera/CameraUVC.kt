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
        val previewSizeList = arrayListOf<PreviewSize>()
        if (mUvcCamera?.supportedSizeList?.isNotEmpty() == true) {
            mUvcCamera?.supportedSizeList
        }  else {
            mUvcCamera?.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV)
        }?.let { sizeList ->
            if (mCameraPreviewSize.isEmpty()) {
                mCameraPreviewSize.clear()
                sizeList.forEach { size->
                    val width = size.width
                    val height = size.height
                    mCameraPreviewSize.add(PreviewSize(width, height))
                }
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

        return previewSizeList
    }

    override fun <T> openCameraInternal(cameraView: T) {
        if (Utils.isTargetSdkOverP(ctx) && !CameraUtils.hasCameraPermission(ctx)) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "Has no CAMERA permission.")
            Logger.e(TAG,"open camera failed, need Manifest.permission.CAMERA permission when targetSdk>=28")
            return
        }
        if (mCtrlBlock == null) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "Usb control block can not be null ")
            return
        }
        // 1. create a UVCCamera
        val request = mCameraRequest!!
        val isTablet = DeviceProfile.isTablet(ctx)
        try {
            mUvcCamera = UVCCamera().apply {
                open(mCtrlBlock)
            }
        } catch (e: Exception) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "open camera failed ${e.localizedMessage}")
            Logger.e(TAG, "open camera failed.", e)
        }

        // 2. set preview size and register preview callback
        var previewSize = getSuitableSize(request.previewWidth, request.previewHeight).apply {
            mCameraRequest!!.previewWidth = width
            mCameraRequest!!.previewHeight = height
        }

        // Decide preview format & frame rate based on device profile.
        // Use a UVC‑friendly FPS range (15–30fps) for all devices to
        // avoid negotiation failures, and vary only the preferred frame
        // format between phone and tablet.
        val minFps = 15
        val maxFps = 30
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

        try {
            Logger.i(TAG, "getSuitableSize: $previewSize")
            if (! isPreviewSizeSupported(previewSize)) {
                closeCamera()
                postStateEvent(ICameraStateCallBack.State.ERROR, "unsupported preview size")
                Logger.e(TAG, "open camera failed, preview size($previewSize) unsupported-> ${mUvcCamera?.supportedSizeList}")
                return
            }
            initEncodeProcessor(previewSize.width, previewSize.height)
            // if give custom minFps or maxFps or unsupported preview size
            // this method will fail
            mUvcCamera?.setPreviewSize(
                previewSize.width,
                previewSize.height,
                minFps,
                maxFps,
                primaryFormat,
                UVCCamera.DEFAULT_BANDWIDTH
            )
        } catch (e: Exception) {
            try {
                previewSize = getSuitableSize(request.previewWidth, request.previewHeight).apply {
                    mCameraRequest!!.previewWidth = width
                    mCameraRequest!!.previewHeight = height
                }
                if (! isPreviewSizeSupported(previewSize)) {
                    postStateEvent(ICameraStateCallBack.State.ERROR, "unsupported preview size")
                    closeCamera()
                    Logger.e(TAG, "open camera failed, preview size($previewSize) unsupported-> ${mUvcCamera?.supportedSizeList}")
                    return
                }
                Logger.e(
                    TAG,
                    "setPreviewSize failed for primary format=$primaryFormat, " +
                            "trying fallback format=$fallbackFormat at ${previewSize.width}x${previewSize.height}@$minFps-$maxFps"
                )
                mUvcCamera?.setPreviewSize(
                    previewSize.width,
                    previewSize.height,
                    minFps,
                    maxFps,
                    fallbackFormat,
                    UVCCamera.DEFAULT_BANDWIDTH
                )
            } catch (e: Exception) {
                closeCamera()
                val errMsg = "setPreviewSize failed for both primary=$primaryFormat and fallback=$fallbackFormat: ${e.localizedMessage}"
                postStateEvent(ICameraStateCallBack.State.ERROR, errMsg)
                Logger.e(TAG, errMsg, e)
                return
            }
        }
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
        private const val MIN_FS = 15
        private const val MAX_FPS = 30
    }
}