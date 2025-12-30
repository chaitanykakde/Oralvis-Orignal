package com.jiangdg.ausbc

import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.*
import android.view.Surface
import com.jiangdg.ausbc.callback.*
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.encode.AACEncodeProcessor
import com.jiangdg.ausbc.encode.AbstractProcessor
import com.jiangdg.ausbc.encode.H264EncodeProcessor
import com.jiangdg.ausbc.encode.audio.AudioStrategySystem
import com.jiangdg.ausbc.encode.audio.AudioStrategyUAC
import com.jiangdg.ausbc.encode.audio.IAudioStrategy
import com.jiangdg.ausbc.encode.bean.RawData
import com.jiangdg.ausbc.encode.muxer.Mp4Muxer
import com.jiangdg.ausbc.render.RenderManager
import com.jiangdg.ausbc.render.effect.AbstractEffect
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.ausbc.utils.CameraUtils.isFilterDevice
import com.jiangdg.ausbc.utils.CameraUtils.isUsbCamera
import com.jiangdg.ausbc.utils.DeviceProfile
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.OpenGLUtils
import com.jiangdg.ausbc.utils.Utils
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.*
import com.jiangdg.usb.DeviceFilter
import com.jiangdg.uvc.UVCCamera
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.abs

/** Multi-road camera client
 *
 * @author Created by jiangdg on 2022/7/18
 *      Modified for v3.3.0 by jiangdg on 2023/1/15
 */
class MultiCameraClient(ctx: Context, callback: IDeviceConnectCallBack?) {
    private var mUsbMonitor: USBMonitor? = null
    private val mMainHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    init {
        mUsbMonitor = USBMonitor(ctx, object : USBMonitor.OnDeviceConnectListener {
            /**
             * Called by receive usb device inserted broadcast
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onAttach(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "attach device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onAttachDev(device)
                }
            }

            /**
             * Called by receive usb device pulled out broadcast
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onDetach(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "detach device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onDetachDec(device)
                }
            }

            /**
             * Called by granted permission
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?,
                createNew: Boolean
            ) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "connect device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onConnectDev(device, ctrlBlock)
                }
            }

            /**
             * Called by dis unauthorized permission
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "disconnect device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onDisConnectDec(device, ctrlBlock)
                }
            }


            /**
             * Called by dis unauthorized permission or request permission exception
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onCancel(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "cancel device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onCancelDev(device)
                }
            }
        })
    }

    /**
     * Register usb insert broadcast
     */
    fun register() {
        if (isMonitorRegistered()) {
            return
        }
        if (Utils.debugCamera) {
            Logger.i(TAG, "register...")
        }
        mUsbMonitor?.register()
    }

    /**
     * UnRegister usb insert broadcast
     */
    fun unRegister() {
        if (!isMonitorRegistered()) {
            return
        }
        if (Utils.debugCamera) {
            Logger.i(TAG, "unRegister...")
        }
        mUsbMonitor?.unregister()
    }

    /**
     * Request usb device permission
     *
     * @param device see [UsbDevice]
     * @return true ready to request permission
     */
    fun requestPermission(device: UsbDevice?): Boolean {
        if (!isMonitorRegistered()) {
            Logger.w(TAG, "Usb monitor haven't been registered.")
            return false
        }
        mUsbMonitor?.requestPermission(device)
        return true
    }

    /**
     * Uvc camera has permission
     *
     * @param device see [UsbDevice]
     * @return true permission granted
     */
    fun hasPermission(device: UsbDevice?) = mUsbMonitor?.hasPermission(device)

    /**
     * Get device list
     *
     * @param list filter regular
     * @return filter device list
     */
    fun getDeviceList(list: List<DeviceFilter>? = null): MutableList<UsbDevice>? {
        list?.let {
            addDeviceFilters(it)
        }
        return mUsbMonitor?.deviceList
    }

    /**
     * Add device filters
     *
     * @param list filter regular
     */
    fun addDeviceFilters(list: List<DeviceFilter>) {
        mUsbMonitor?.addDeviceFilter(list)
    }

    /**
     * Remove device filters
     *
     * @param list filter regular
     */
    fun removeDeviceFilters(list: List<DeviceFilter>) {
        mUsbMonitor?.removeDeviceFilter(list)
    }

    /**
     * Destroy usb monitor engine
     */
    fun destroy() {
        mUsbMonitor?.destroy()
    }

    fun openDebug(debug: Boolean) {
        Utils.debugCamera = debug
        USBMonitor.DEBUG = debug
        UVCCamera.DEBUG = debug
    }

    private fun isMonitorRegistered() = mUsbMonitor?.isRegistered == true


    /**
     * Camera strategy super class
     *
     * @property ctx context
     * @property device see [UsbDevice]
     * @constructor Create camera by inherit it
     */
    abstract class ICamera(val ctx: Context, val device: UsbDevice): Handler.Callback,
        H264EncodeProcessor.OnEncodeReadyListener {
        private var mMediaMuxer: Mp4Muxer? = null
        private var mEncodeDataCallBack: IEncodeDataCallBack? = null
        private var mCameraThread: HandlerThread? = null
        private var mAudioProcess: AbstractProcessor? = null
        private var mVideoProcess: AbstractProcessor? = null
        private var mRenderManager: RenderManager?  = null
        private var mCameraView: Any? = null
        private var mCameraStateCallback: ICameraStateCallBack? = null
        protected var mContext = ctx
        protected var mCameraRequest: CameraRequest? = null
        protected var mCameraHandler: Handler? = null
        protected var isPreviewed: Boolean = false
        protected var isNeedGLESRender: Boolean = false
        protected var mCtrlBlock: USBMonitor.UsbControlBlock? = null
        protected var mPreviewDataCbList = CopyOnWriteArrayList<IPreviewDataCallBack>()
        private val mCacheEffectList by lazy {
            arrayListOf<AbstractEffect>()
        }
        protected val mMainHandler: Handler by lazy {
            Handler(Looper.getMainLooper())
        }
        protected val mNV21DataQueue: LinkedBlockingDeque<ByteArray> by lazy {
            LinkedBlockingDeque(MAX_NV21_DATA)
        }
        protected val mSaveImageExecutor: ExecutorService by lazy {
            Executors.newFixedThreadPool(10)
        }
        protected val mDateFormat by lazy {
            SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
        }
        protected val mCameraDir by lazy {
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
        }

        init {
            // DISABLED: Tablet mode was for debugging only and causes resolution issues.
            // Always use phone mode (MJPEG first) which supports higher resolutions better.
            // Best-effort hint to the native UVC stack about tablet/phone profile.
            // If the current libUVCCamera.so does not yet contain the JNI bridge,
            // fail gracefully and log instead of crashing the app.
            try {
                UVCCamera.setTabletMode(false)  // Always use phone mode for better resolution support
            } catch (e: UnsatisfiedLinkError) {
                Logger.w(TAG, "nativeSetTabletMode not available in current libUVCCamera.so, skipping tablet hint", e)
            } catch (t: Throwable) {
                Logger.w(TAG, "Failed to propagate tablet mode to native UVC stack, continuing without it: ${t.message}")
            }
        }

        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                MSG_START_PREVIEW -> {
                    val previewWidth = mCameraRequest!!.previewWidth
                    val previewHeight = mCameraRequest!!.previewHeight
                    val renderMode = mCameraRequest!!.renderMode
                    val isRawPreviewData = mCameraRequest!!.isRawPreviewData
                    when (val cameraView = mCameraView) {
                        is IAspectRatio -> {
                            if (mCameraRequest!!.isAspectRatioShow) {
                                cameraView.setAspectRatio(previewWidth, previewHeight)
                            }
                            cameraView
                        }
                        else -> {
                            null
                        }
                    }.also { view->
                        isNeedGLESRender = isGLESRender(renderMode == CameraRequest.RenderMode.OPENGL)
                        if (! isNeedGLESRender && view != null) {
                            openCameraInternal(view)
                            return true
                        }
                        // use opengl render
                        // if surface is null, force off screen render whatever mode
                        // and use init preview size for render size
                        mCameraRequest!!.renderMode = CameraRequest.RenderMode.OPENGL
                        val screenWidth = view?.getSurfaceWidth() ?: previewWidth
                        val screenHeight = view?.getSurfaceHeight() ?: previewHeight
                        val surface = view?.getSurface()
                        val previewCb = if (isRawPreviewData) {
                            null
                        } else {
                            mPreviewDataCbList
                        }
                        mRenderManager = RenderManager(ctx, previewWidth, previewHeight, previewCb)
                        mRenderManager?.startRenderScreen(screenWidth, screenHeight, surface, object : RenderManager.CameraSurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture?) {
                                if (surfaceTexture == null) {
                                    closeCamera()
                                    postStateEvent(ICameraStateCallBack.State.ERROR, "create camera surface failed")
                                    return
                                }
                                openCameraInternal(surfaceTexture)
                            }
                        })
                        mRenderManager?.setRotateType(mCameraRequest!!.defaultRotateType)
                        if (mCacheEffectList.isNotEmpty()) {
                            mCacheEffectList.forEach { effect ->
                                mRenderManager?.addRenderEffect(effect)
                            }
                            return@also
                        }
                        mCameraRequest?.defaultEffect?.apply {
                            mRenderManager?.addRenderEffect(this)
                        }
                    }
                }
                MSG_STOP_PREVIEW -> {
                    closeCameraInternal()
                    mRenderManager?.getCacheEffectList()?.apply {
                        mCacheEffectList.clear()
                        mCacheEffectList.addAll(this)
                    }
                    mRenderManager?.stopRenderScreen()
                    mRenderManager = null
                }
                MSG_CAPTURE_IMAGE -> {
                    (msg.obj as Pair<*, *>).apply {
                        val path = first as? String
                        val cb = second as ICaptureCallBack
                        if (isNeedGLESRender && !mCameraRequest!!.isCaptureRawImage) {
                            mRenderManager?.saveImage(cb, path)
                            return@apply
                        }
                        captureImageInternal(path, second as ICaptureCallBack)
                    }
                }
                MSG_CAPTURE_VIDEO_START -> {
                    (msg.obj as Triple<*, *, *>).apply {
                        captureVideoStartInternal(first as? String, second as Long, third as ICaptureCallBack)
                    }
                }
                MSG_CAPTURE_VIDEO_STOP -> {
                    captureVideoStopInternal()
                }
                MSG_CAPTURE_STREAM_START -> {
                    captureStreamStartInternal()
                }
                MSG_CAPTURE_STREAM_STOP -> {
                    captureStreamStopInternal()
                }
            }
            return true
        }

        protected abstract fun <T> openCameraInternal(cameraView: T)
        protected abstract fun closeCameraInternal()
        protected abstract fun captureImageInternal(savePath: String?, callback: ICaptureCallBack)

        protected open fun getAudioStrategy(): IAudioStrategy? {
            return when(mCameraRequest?.audioSource) {
                CameraRequest.AudioSource.SOURCE_AUTO -> {
                    if (isMicSupported(device) && mCtrlBlock!=null) {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using device internal mic")
                        }
                        AudioStrategyUAC(mCtrlBlock!!)
                    } else {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using system mic")
                        }
                        AudioStrategySystem()
                    }
                }
                CameraRequest.AudioSource.SOURCE_DEV_MIC -> {
                    if (isMicSupported(device) && mCtrlBlock!=null) {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using device internal mic")
                        }
                        return AudioStrategyUAC(mCtrlBlock!!)
                    }
                    return null
                }
                CameraRequest.AudioSource.SOURCE_SYS_MIC -> {
                    if (Utils.debugCamera) {
                        Logger.i(TAG, "Audio record by using system mic")
                    }
                    AudioStrategySystem()
                }
                else -> {
                    null
                }
            }
        }

        /**
         * should use opengl, recommend
         *
         * @return default depend on device opengl version, >=2.0 is true
         */
        private fun isGLESRender(isGlesRenderOpen: Boolean): Boolean =isGlesRenderOpen && OpenGLUtils.isGlEsSupported(ctx)

        /**
         * Init encode processor
         *
         * @param previewWidth camera opened preview width
         * @param previewHeight camera opened preview height
         */
        protected fun initEncodeProcessor(previewWidth: Int, previewHeight: Int) {
            releaseEncodeProcessor()
            // create audio process
            getAudioStrategy()?.let { audio->
                AACEncodeProcessor(audio)
            }?.also { processor ->
                mAudioProcess = processor
            }
            // create video process
            mContext.resources.configuration.orientation.let { orientation ->
                orientation == Configuration.ORIENTATION_PORTRAIT
            }.also { isPortrait ->
                mVideoProcess = H264EncodeProcessor(previewWidth, previewHeight, isNeedGLESRender, isPortrait)
            }
        }

        /**
         * Release encode processor
         */
        protected fun releaseEncodeProcessor() {
            mVideoProcess?.stopEncode()
            mAudioProcess?.stopEncode()
            mVideoProcess = null
            mAudioProcess = null
        }

        /**
         * Put video data
         *
         * @param data NV21 raw data
         */
        protected fun putVideoData(data: ByteArray) {
            mVideoProcess?.putRawData(RawData(data, data.size))
        }

        /**
         * Start rec mp3
         *
         * @param mp3Path  mp3 save path
         * @param callBack record status, see [ICaptureCallBack]
         */
        fun captureAudioStart(callBack: ICaptureCallBack, mp3Path: String?=null) {
            if (! CameraUtils.hasAudioPermission(mContext)) {
                callBack.onError("Has no audio permission")
                return
            }
            if (! CameraUtils.hasStoragePermission(mContext)) {
                callBack.onError("Has no storage permission")
                return
            }
            val path = if (mp3Path.isNullOrEmpty()) {
                "${mContext.getExternalFilesDir(null)?.path}/${System.currentTimeMillis()}.mp3"
            } else {
                mp3Path
            }
            (mAudioProcess as? AACEncodeProcessor)?.recordMp3Start(path, callBack)
        }

        /**
         * Stop rec mp3
         */
        fun captureAudioStop() {
            (mAudioProcess as? AACEncodeProcessor)?.recordMp3Stop()
        }

        /**
         * Start play mic
         *
         * @param callBack play mic status in real-time, see [IPlayCallBack]
         */
        fun startPlayMic(callBack: IPlayCallBack?) {
            if (! CameraUtils.hasAudioPermission(mContext)) {
                callBack?.onError("Has no audio permission")
                return
            }
            (mAudioProcess as? AACEncodeProcessor)?.playAudioStart(callBack)
        }

        /**
         * Stop play mic
         */
        fun stopPlayMic() {
            (mAudioProcess as? AACEncodeProcessor)?.playAudioStop()
        }

        /**
         * Rotate camera render angle
         *
         * @param type rotate angle, null means rotating nothing
         * see [RotateType.ANGLE_90], [RotateType.ANGLE_270],...etc.
         */
        fun setRotateType(type: RotateType?) {
            mRenderManager?.setRotateType(type)
        }

        /**
         * Set render size
         *
         * @param width surface width
         * @param height surface height
         */
        fun setRenderSize(width: Int, height: Int) {
            mRenderManager?.setRenderSize(width, height)
        }

        /**
         * Add render effect.There is only one setting in the same category
         * <p>
         * The default effects:
         * @see [com.jiangdg.ausbc.render.effect.EffectBlackWhite]
         * @see [com.jiangdg.ausbc.render.effect.EffectZoom]
         * @see [com.jiangdg.ausbc.render.effect.EffectSoul]
         * <p>
         * Of course, you can also realize a custom effect by extending from [AbstractEffect]
         *
         * @param effect a effect
         */
        fun addRenderEffect(effect: AbstractEffect) {
            mRenderManager?.addRenderEffect(effect)
        }

        /**
         * Remove render effect
         *
         * @param effect a effect, extending from [AbstractEffect]
         */
        fun removeRenderEffect(effect: AbstractEffect) {
            val defaultId =  mCameraRequest?.defaultEffect?.getId()
            if (effect.getId() == defaultId) {
                mCameraRequest?.defaultEffect = null
            }
            mRenderManager?.removeRenderEffect(effect)
        }

        /**
         * Get default effect
         *
         * @return  default effect, extending from [AbstractEffect]
         */
        fun getDefaultEffect() = mCameraRequest?.defaultEffect

        /**
         * Update render effect
         *
         * @param classifyId effect classify id
         * @param effect new effect, null means set none
         */
        fun updateRenderEffect(classifyId: Int, effect: AbstractEffect?) {
            mRenderManager?.getCacheEffectList()?.find {
                it.getClassifyId() == classifyId
            }?.also {
                removeRenderEffect(it)
            }
            effect ?: return
            addRenderEffect(effect)
        }

        /**
         * Post camera state to main thread
         *
         * @param state see [ICameraStateCallBack.State]
         * @param msg detail msg
         */
        protected fun postStateEvent(state: ICameraStateCallBack.State, msg: String? = null) {
            mMainHandler.post {
                mCameraStateCallback?.onCameraState(this, state, msg)
            }
        }

        /**
         * Set usb control block, when the uvc device was granted permission
         *
         * @param ctrlBlock see [USBMonitor.OnDeviceConnectListener]#onConnectedDev
         */
        fun setUsbControlBlock(ctrlBlock: USBMonitor.UsbControlBlock?) {
            this.mCtrlBlock = ctrlBlock
        }

        /**
         * Get usb device information
         *
         * @return see [UsbDevice]
         */
        fun getUsbDevice() = device

        /**
         * Is mic supported
         *
         * @return true camera support mic
         */
        fun isMicSupported(device: UsbDevice?) = CameraUtils.isCameraContainsMic(device)


        /**
         * Get all preview sizes
         *
         * @param aspectRatio aspect ratio
         * @return [PreviewSize] list of camera
         */
        abstract fun getAllPreviewSizes(aspectRatio: Double? = null): MutableList<PreviewSize>

        /**
         * Open camera
         *
         * @param cameraView render surface view，support Surface or SurfaceTexture
         *                      or SurfaceView or TextureView or GLSurfaceView
         * @param cameraRequest camera request
         */
        fun <T> openCamera(cameraView: T? = null, cameraRequest: CameraRequest? = null) {
            mCameraView = cameraView ?: mCameraView
            mCameraRequest = cameraRequest ?: getDefaultCameraRequest()
            HandlerThread("camera-${System.currentTimeMillis()}").apply {
                start()
            }.let { thread ->
                this.mCameraThread = thread
                thread
            }.also {
                mCameraHandler = Handler(it.looper, this)
                mCameraHandler?.obtainMessage(MSG_START_PREVIEW)?.sendToTarget()
            }
        }

        /**
         * Close camera
         */
        fun closeCamera() {
            mCameraHandler?.obtainMessage(MSG_STOP_PREVIEW)?.sendToTarget()
            mCameraThread?.quitSafely()
            mCameraThread = null
            mCameraHandler = null
        }

        /**
         * check if camera opened
         *
         * @return camera open status, true or false
         */
        fun isCameraOpened() = isPreviewed

        /**
         * Get current camera request
         *
         * @return see [CameraRequest], can be null
         */
        fun getCameraRequest() = mCameraRequest

        /**
         * Capture image
         *
         * @param callBack capture a image status, see [ICaptureCallBack]
         * @param path image save path, default is DICM/Camera
         */
        fun captureImage(callBack: ICaptureCallBack, path: String? = null) {
            Pair(path, callBack).apply {
                mCameraHandler?.obtainMessage(MSG_CAPTURE_IMAGE, this)?.sendToTarget()
            }
        }

        /**
         * Capture video start
         *
         * @param callBack capture result callback, see [ICaptureCallBack]
         * @param path video save path, default is DICM/Camera
         * @param durationInSec video file auto divide duration is seconds
         */
        fun captureVideoStart(callBack: ICaptureCallBack, path: String? = null, durationInSec: Long = 0L) {
            Triple(path, durationInSec, callBack).apply {
                mCameraHandler?.obtainMessage(MSG_CAPTURE_VIDEO_START, this)?.sendToTarget()
            }
        }

        /**
         * Capture video stop
         */
        fun captureVideoStop() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_VIDEO_STOP)?.sendToTarget()
        }

        /**
         * Capture stream start
         *  Getting H.264 and AAC stream
         */
        fun captureStreamStart() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_STREAM_START)?.sendToTarget()
        }

        /**
         * Capture stream stop
         */
        fun captureStreamStop() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_STREAM_STOP)?.sendToTarget()
        }

        /**
         * Update resolution
         *
         * @param width camera preview width, see [PreviewSize]
         * @param height camera preview height, [PreviewSize]
         * @return result of operation
         */
        fun updateResolution(width: Int, height: Int) {
            Logger.d(TAG, "========================================")
            Logger.d(TAG, "MultiCameraClient.updateResolution() CALLED")
            Logger.d(TAG, "Requested: ${width}x${height}")
            Logger.d(TAG, "mCameraRequest is null: ${mCameraRequest == null}")
            Logger.d(TAG, "isStreaming: ${isStreaming()}")
            Logger.d(TAG, "isRecording: ${isRecording()}")
            
            if (mCameraRequest == null) {
                Logger.w(TAG, "updateResolution FAILED: mCameraRequest is null")
                return
            }
            if (isStreaming() || isRecording()) {
                Logger.e(TAG, "updateResolution FAILED: video recording in progress")
                return
            }
            mCameraRequest?.apply {
                Logger.d(TAG, "Current camera request:")
                Logger.d(TAG, "  previewWidth: $previewWidth")
                Logger.d(TAG, "  previewHeight: $previewHeight")
                
                if (previewWidth == width && previewHeight == height) {
                    Logger.d(TAG, "SKIPPED: Resolution already matches request")
                    return@apply
                }
                
                Logger.i(TAG, "updateResolution: Changing from ${previewWidth}x${previewHeight} to ${width}x${height}")
                Logger.d(TAG, "Closing camera...")
                closeCamera()
                
                Logger.d(TAG, "Scheduling camera reopen in 1000ms with new resolution")
                mMainHandler.postDelayed({
                    Logger.d(TAG, "Delayed task executing - updating camera request")
                    Logger.d(TAG, "  OLD previewWidth: $previewWidth")
                    Logger.d(TAG, "  OLD previewHeight: $previewHeight")
                    previewWidth = width
                    previewHeight = height
                    Logger.d(TAG, "  NEW previewWidth: $previewWidth")
                    Logger.d(TAG, "  NEW previewHeight: $previewHeight")
                    Logger.d(TAG, "Calling openCamera() with updated request")
                    openCamera(mCameraView, mCameraRequest)
                }, 1000)
            }
        }

        /**
         * Set camera state call back
         *
         * @param callback camera be opened or closed
         */
        fun setCameraStateCallBack(callback: ICameraStateCallBack?) {
            this.mCameraStateCallback = callback
        }

        /**
         * set encode data call back
         *
         * @param callBack camera encoded data call back, see [IEncodeDataCallBack]
         */
        fun setEncodeDataCallBack(callBack: IEncodeDataCallBack?) {
            this.mEncodeDataCallBack = callBack
        }

        /**
         * Add preview data call back
         * @param callBack preview data call back
         */
        fun addPreviewDataCallBack(callBack: IPreviewDataCallBack) {
            if (mPreviewDataCbList.contains(callBack)) {
                return
            }
            mPreviewDataCbList.add(callBack)
        }

        /**
         * Remove preview data call back
         *
         * @param callBack preview data call back
         */
        fun removePreviewDataCallBack(callBack: IPreviewDataCallBack) {
            if (! mPreviewDataCbList.contains(callBack)) {
                return
            }
            mPreviewDataCbList.remove(callBack)
        }

        fun getSuitableSize(maxWidth: Int, maxHeight: Int): PreviewSize {
            Logger.d(TAG, "getSuitableSize() called for ${maxWidth}x${maxHeight}")
            val sizeList = getAllPreviewSizes()
            
            if (sizeList.isNullOrEmpty()) {
                Logger.w(TAG, "  No sizes available, using default: ${DEFAULT_PREVIEW_WIDTH}x${DEFAULT_PREVIEW_HEIGHT}")
                return PreviewSize(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
            }
            
            Logger.d(TAG, "  Available sizes: ${sizeList.size}")
            
            // Sort sizes by total pixels (descending) to prefer higher resolutions
            val sortedSizes = sizeList.sortedByDescending { it.width * it.height }
            Logger.d(TAG, "  Sorted sizes (top 5): ${sortedSizes.take(5).map { "${it.width}x${it.height} (${it.width * it.height}px)" }.joinToString(", ")}")
            
            // 1. Find exact match
            sortedSizes.find {
                (it.width == maxWidth && it.height == maxHeight)
            }?.let {
                Logger.d(TAG, "  ✓ Found exact match: ${it.width}x${it.height}")
                return it
            }
            
            // 2. Find highest resolution with same aspect ratio that fits within requested bounds
            val aspectRatio = maxWidth.toFloat() / maxHeight
            Logger.d(TAG, "  Requested aspect ratio: ${String.format("%.2f", aspectRatio)}")
            sortedSizes.find {
                val w = it.width
                val h = it.height
                val ratio = w.toFloat() / h
                ratio == aspectRatio && w <= maxWidth && h <= maxHeight
            }?.let {
                Logger.d(TAG, "  ✓ Found same aspect ratio within bounds: ${it.width}x${it.height}")
                return it
            }
            
            // 3. Find highest resolution with same aspect ratio (may exceed requested bounds)
            sortedSizes.find {
                val w = it.width
                val h = it.height
                val ratio = w.toFloat() / h
                ratio == aspectRatio
            }?.let {
                Logger.d(TAG, "  ✓ Found same aspect ratio (may exceed bounds): ${it.width}x${it.height}")
                return it
            }
            
            // 4. Find closest aspect ratio - prefer higher resolution
            // Calculate aspect ratio difference and prefer higher resolution when similar
            var bestSize = sortedSizes[0] // Start with highest resolution
            var minAspectDiff = Float.MAX_VALUE
            
            sortedSizes.forEach { size ->
                val sizeRatio = size.width.toFloat() / size.height
                val aspectDiff = abs(aspectRatio - sizeRatio)
                // Prefer higher resolution if aspect ratio difference is similar
                if (aspectDiff < minAspectDiff || 
                    (aspectDiff == minAspectDiff && (size.width * size.height) > (bestSize.width * bestSize.height))) {
                    minAspectDiff = aspectDiff
                    bestSize = size
                }
            }
            
            Logger.d(TAG, "  → Selected closest aspect ratio: ${bestSize.width}x${bestSize.height} (aspect diff: ${String.format("%.3f", minAspectDiff)})")
            return bestSize
        }

        fun isPreviewSizeSupported(previewSize: PreviewSize): Boolean {
            val allSizes = getAllPreviewSizes()
            val found = allSizes.find {
                it.width == previewSize.width && it.height == previewSize.height
            }
            val isSupported = found != null
            Logger.d(TAG, "isPreviewSizeSupported(${previewSize.width}x${previewSize.height}) = $isSupported")
            Logger.d(TAG, "  Total available sizes: ${allSizes.size}")
            if (!isSupported && allSizes.isNotEmpty()) {
                Logger.d(TAG, "  Available sizes: ${allSizes.take(5).map { "${it.width}x${it.height}" }.joinToString(", ")}${if (allSizes.size > 5) "..." else ""}")
            }
            return isSupported
        }

        fun isRecording() = mMediaMuxer?.isMuxerStarter() == true

        fun isStreaming() = mVideoProcess?.isEncoding() == true || mAudioProcess?.isEncoding() == true

        private fun captureVideoStartInternal(path: String?, durationInSec: Long, callBack: ICaptureCallBack) {
            if (! isCameraOpened()) {
                Logger.e(TAG ,"capture video failed, camera not opened")
                return
            }
            if (isRecording()) {
                Logger.w(TAG, "capturing video already running")
                return
            }
            captureStreamStartInternal()
            Mp4Muxer(mContext, callBack, path, durationInSec, mAudioProcess==null).apply {
                mVideoProcess?.setMp4Muxer(this, true)
                mAudioProcess?.setMp4Muxer(this, false)
            }.also { muxer ->
                mMediaMuxer = muxer
            }
            Logger.i(TAG, "capturing video start")
        }

        private fun captureVideoStopInternal() {
            captureStreamStopInternal()
            mMediaMuxer?.release()
            mMediaMuxer = null
            Logger.i(TAG, "capturing video stop")
        }

        private fun captureStreamStartInternal() {
            if (! isCameraOpened()) {
                Logger.e(TAG ,"capture stream failed, camera not opened")
                return
            }
            if (isStreaming()) {
                Logger.w(TAG, "capturing stream already running")
                return
            }
            (mVideoProcess as? H264EncodeProcessor)?.apply {
                if (mVideoProcess?.isEncoding() == true) {
                    return@apply
                }
                startEncode()
                setEncodeDataCallBack(mEncodeDataCallBack)
                setOnEncodeReadyListener(this@ICamera)
            }
            (mAudioProcess as? AACEncodeProcessor)?.apply {
                if (mAudioProcess?.isEncoding() == true) {
                    return@apply
                }
                startEncode()
                setEncodeDataCallBack(mEncodeDataCallBack)
            }
            Logger.i(TAG, "capturing stream start")
        }

        private fun captureStreamStopInternal() {
            mRenderManager?.stopRenderCodec()
            (mVideoProcess as? H264EncodeProcessor)?.apply {
                stopEncode()
                setEncodeDataCallBack(null)
            }
            (mAudioProcess as? AACEncodeProcessor)?.apply {
                stopEncode()
                setEncodeDataCallBack(null)
            }
            Logger.i(TAG, "capturing stream stop")
        }

        override fun onReady(surface: Surface?) {
            if (surface == null) {
                Logger.e(TAG, "start encode failed, input surface is null")
                return
            }
            mCameraRequest?.apply {
                mRenderManager?.startRenderCodec(surface, previewWidth, previewHeight)
            }
        }

        private fun getDefaultCameraRequest(): CameraRequest {
            return CameraRequest.Builder()
                .setPreviewWidth(1280)
                .setPreviewHeight(720)
                .create()
        }
    }

    companion object {
        private const val TAG = "MultiCameraClient"
        private const val MSG_START_PREVIEW = 0x01
        private const val MSG_STOP_PREVIEW = 0x02
        private const val MSG_CAPTURE_IMAGE = 0x03
        private const val MSG_CAPTURE_VIDEO_START = 0x04
        private const val MSG_CAPTURE_VIDEO_STOP = 0x05
        private const val MSG_CAPTURE_STREAM_START = 0x06
        private const val MSG_CAPTURE_STREAM_STOP = 0x07
        // Use higher default resolution - only as absolute last resort if camera reports no sizes
        // Prefer to use highest available resolution from camera instead
        private const val DEFAULT_PREVIEW_WIDTH = 1920
        private const val DEFAULT_PREVIEW_HEIGHT = 1080
        const val MAX_NV21_DATA = 5
        const val CAPTURE_TIMES_OUT_SEC = 3L
    }
}