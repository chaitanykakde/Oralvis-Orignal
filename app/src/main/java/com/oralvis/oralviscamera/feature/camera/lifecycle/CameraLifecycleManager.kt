package com.oralvis.oralviscamera.feature.camera.lifecycle

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.oralvis.oralviscamera.feature.camera.state.CameraStateStore

/**
 * Owns camera lifecycle logic extracted from MainActivity (Phase 5A).
 * Method bodies copied verbatim; USB/guided invoked via callbacks.
 */
class CameraLifecycleManager(
    private val store: CameraStateStore
) {
    /**
     * SURFACETEXTURE READINESS GATE - Match demo behavior.
     * Body copied verbatim from MainActivity.
     */
    fun initializeCameraIfReady(
        isSurfaceTextureReady: () -> Boolean,
        ensureUsbMonitorRegistered: () -> Unit,
        openCamera: (MultiCameraClient.ICamera, CameraRequest) -> Unit
    ) {
        if (!isSurfaceTextureReady()) {
            android.util.Log.d("CameraGate", "Camera open deferred - SurfaceTexture not ready")
            return
        }

        ensureUsbMonitorRegistered()

        // Run deferred openCamera if permission was granted before SurfaceTexture was ready
        store.mPendingCameraOpen?.let { (cam, req) ->
            store.mPendingCameraOpen = null
            android.util.Log.d("CameraGate", "Executed deferred openCamera (SurfaceTexture became ready)")
            openCamera(cam, req)
        }
    }

    /**
     * FIRST-FRAME SAFETY GUARANTEE - Match demo behavior.
     * Body copied verbatim from MainActivity.
     */
    fun onFirstFrameReceived(
        getCurrentMode: () -> String,
        applyModePreset: (String) -> Unit,
        isGuidedInitialized: () -> Boolean,
        initializeGuidedIfNeeded: () -> Unit,
        updateCameraControlValues: () -> Unit
    ) {
        android.util.Log.d("CameraPipeline", "🎯 FIRST FRAME RECEIVED - safe to initialize heavy components")

        // CRITICAL: Apply mode preset ONLY after first frame confirms streaming is stable
        try {
            if (getCurrentMode() == "Normal") {
                android.util.Log.d("CameraPipeline", "Applying Normal mode preset after first frame (streaming stable)")
                applyModePreset("Normal")
            }
        } catch (e: Exception) {
            android.util.Log.e("CameraPipeline", "Failed to apply mode preset: ${e.message}", e)
        }

        // DEFERRAL: Initialize heavy components now that camera is proven working
        try {
            if (!isGuidedInitialized()) {
                android.util.Log.d("CameraPipeline", "Initializing GuidedCaptureManager (with OpenCV)")
                initializeGuidedIfNeeded()
            }
        } catch (e: Exception) {
            android.util.Log.e("CameraPipeline", "Failed to initialize guided capture: ${e.message}", e)
        }

        // CONTROL TRANSFER TIMING: Safe to update camera parameters now
        updateCameraControlValues()

        android.util.Log.d("CameraPipeline", "✅ Camera pipeline fully initialized and stable")
    }

    /**
     * Returns the camera state callback. OPENED/CLOSED update store then invoke callbacks.
     * Order and behavior unchanged.
     */
    fun createStateCallback(
        runOnUiThread: (Runnable) -> Unit,
        onOpened: (MultiCameraClient.ICamera) -> Unit,
        onClosed: (MultiCameraClient.ICamera) -> Unit,
        onError: (String?) -> Unit
    ): ICameraStateCallBack {
        return object : ICameraStateCallBack {
            override fun onCameraState(
                self: MultiCameraClient.ICamera,
                code: ICameraStateCallBack.State,
                msg: String?
            ) {
                runOnUiThread(Runnable {
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> {
                            android.util.Log.d("CameraState", "Camera opened - setting isCameraReady = true")
                            store.isCameraReady = true
                            onOpened(self)
                        }
                        ICameraStateCallBack.State.CLOSED -> {
                            android.util.Log.d("CameraState", "Camera closed - setting isCameraReady = false")
                            store.isCameraReady = false
                            store.hasReceivedFirstFrame = false
                            onClosed(self)
                        }
                        ICameraStateCallBack.State.ERROR -> {
                            onError(msg)
                        }
                    }
                })
            }
        }
    }
}
