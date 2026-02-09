package com.oralvis.oralviscamera.feature.guided

// Phase 3 Guided Capture Isolation.
// This is a thin delegating wrapper around GuidedCaptureManager with no logic changes.

import android.content.Context
import android.view.ViewGroup
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.oralvis.oralviscamera.guidedcapture.GuidedCaptureManager
import com.oralvis.oralviscamera.guidedcapture.SessionBridge

class GuidedController(
    private val context: Context,
    private val rootContainer: ViewGroup,
    private val sessionBridge: SessionBridge
) {

    private var guidedCaptureManager: GuidedCaptureManager? = null

    /**
     * Match original initializeGuidedCapture() semantics:
     * - Only create GuidedCaptureManager once
     * - Log messages preserved at call sites
     */
    fun initializeIfNeeded(onInitialized: (() -> Unit)? = null) {
        if (guidedCaptureManager != null) {
            onInitialized?.invoke()
            return
        }
        guidedCaptureManager = GuidedCaptureManager(
            context = context,
            rootContainer = rootContainer,
            sessionBridge = sessionBridge
        )
        onInitialized?.invoke()
    }

    fun isInitialized(): Boolean = guidedCaptureManager != null

    /**
     * Enable guided capture. Preview callback attach is done by PreviewCallbackRouter (Phase 5B).
     */
    fun enable(camera: MultiCameraClient.ICamera?) {
        val manager = guidedCaptureManager ?: return
        manager.enable()
        // Camera addPreviewDataCallBack is performed by PreviewCallbackRouter
    }

    /**
     * Returns the guided preview callback for router to add/remove. GuidedController does not touch camera APIs.
     */
    fun getPreviewCallback(): IPreviewDataCallBack? = guidedCaptureManager

    fun isGuidedActive(): Boolean {
        return guidedCaptureManager?.isEnabled == true
    }

    fun handleManualCapture(): Boolean {
        val manager = guidedCaptureManager ?: return false
        return manager.handleManualCapture()
    }
}

