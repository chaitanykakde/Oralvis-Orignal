package com.oralvis.oralviscamera.feature.camera.preview

import android.graphics.SurfaceTexture
import android.view.TextureView
import com.oralvis.oralviscamera.feature.camera.state.CameraStateStore

/**
 * Owns SurfaceTexture listener and surface-ready state (Phase 5B).
 * Optional onSurfaceReadyForUsb: call requestPermissionForAlreadyAttachedDevices() here so USB
 * permission is requested only after the preview surface exists (matches reference app flow).
 */
class PreviewSurfaceManager(
    private val store: CameraStateStore,
    private val onInitializeCameraIfReady: () -> Unit,
    private val onFirstFrameReceived: () -> Unit,
    private val onSurfaceReadyForUsb: (() -> Unit)? = null
) {
    /**
     * Attach the SurfaceTexture listener to the TextureView.
     * Call once (e.g. from MainActivity onCreate).
     */
    fun attachTo(textureView: TextureView) {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                android.util.Log.d("SurfaceTexture", "SurfaceTexture available - camera can now be initialized")
                store.isSurfaceTextureReady = true
                onSurfaceReadyForUsb?.invoke()
                onInitializeCameraIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                android.util.Log.d("SurfaceTexture", "SurfaceTexture size changed: ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                android.util.Log.d("SurfaceTexture", "SurfaceTexture destroyed")
                store.isSurfaceTextureReady = false
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // First frame arrived - this is our signal that camera is working
                if (!store.hasReceivedFirstFrame) {
                    store.hasReceivedFirstFrame = true
                    android.util.Log.d("CameraPipeline", "✅ FIRST FRAME ARRIVED - pipeline working")
                    onFirstFrameReceived()
                }
            }
        }
    }
}
