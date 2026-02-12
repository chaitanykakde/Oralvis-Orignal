package com.oralvis.oralviscamera.feature.camera.startup

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.oralvis.oralviscamera.feature.camera.lifecycle.CameraLifecycleManager
import com.oralvis.oralviscamera.feature.camera.preview.PreviewCallbackRouter
import com.oralvis.oralviscamera.feature.camera.preview.PreviewSurfaceManager
import com.oralvis.oralviscamera.feature.camera.state.CameraStateStore

/**
 * Authority coordinator for Camera + Preview startup sequencing.
 *
 * Behavior has been moved here from MainActivity without modification.
 * This class coordinates camera lifecycle and preview wiring via its dependencies
 * and injected callbacks; it does not reference UI widgets or guided/session
 * components directly.
 */
class CameraStartupCoordinator(
    private val cameraLifecycleManager: CameraLifecycleManager,
    private val cameraStateStore: CameraStateStore,
    private val previewSurfaceManager: PreviewSurfaceManager,
    private val previewCallbackRouter: PreviewCallbackRouter,
    private val ensureUsbMonitorRegistered: () -> Unit,
    private val openCamera: (MultiCameraClient.ICamera, CameraRequest) -> Unit,
    private val getCurrentMode: () -> String,
    private val applyModePreset: (String) -> Unit,
    private val isGuidedInitialized: () -> Boolean,
    private val initializeGuidedIfNeeded: () -> Unit,
    private val updateCameraControlValuesFromMode: () -> Unit,
    private val cleanupCameraClient: () -> Unit
) {

    fun onCreate() {
        // No-op for now; camera startup logic is driven by surface and callbacks.
    }

    fun onResume() {
        // No-op for now; camera-specific resume work is handled via callbacks.
    }

    fun onPause() {
        // No-op for now; camera close is driven by USB / state callbacks.
    }

    fun onDestroy() {
        // Phase 5B: Unregister preview callbacks and clean up camera client.
        previewCallbackRouter.detachFromCamera(cameraStateStore.mCurrentCamera)
        cleanupCameraClient()
    }

    fun onSurfaceReady() {
        // SURFACETEXTURE READINESS GATE - Match demo behavior (Phase 5A).
        cameraLifecycleManager.initializeCameraIfReady(
            isSurfaceTextureReady = { cameraStateStore.isSurfaceTextureReady },
            ensureUsbMonitorRegistered = { ensureUsbMonitorRegistered() },
            openCamera = { cam, req -> openCamera(cam, req) }
        )
    }

    fun onFirstFrameReceived() {
        // FIRST-FRAME SAFETY GUARANTEE (Phase 5A).
        cameraLifecycleManager.onFirstFrameReceived(
            getCurrentMode = { getCurrentMode() },
            applyModePreset = { mode -> applyModePreset(mode) },
            isGuidedInitialized = { isGuidedInitialized() },
            initializeGuidedIfNeeded = { initializeGuidedIfNeeded() },
            updateCameraControlValues = { updateCameraControlValuesFromMode() }
        )
    }

    /**
     * PHASE A — CAMERA ACTIVATION (Phase 5B: delegated to PreviewCallbackRouter).
     */
    fun activateCameraPipeline() {
        if (cameraStateStore.mCurrentCamera == null) {
            android.util.Log.w(
                "CameraActivation",
                "Cannot activate camera pipeline - cameraStateStore.mCurrentCamera is null"
            )
            return
        }
        previewCallbackRouter.onCameraOpened(cameraStateStore.mCurrentCamera!!)
    }
}
