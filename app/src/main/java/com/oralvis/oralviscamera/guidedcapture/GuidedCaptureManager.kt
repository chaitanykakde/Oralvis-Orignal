package com.oralvis.oralviscamera.guidedcapture

import android.content.Context
import android.view.ViewGroup
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.oralvis.oralviscamera.R

/**
 * Convenience orchestrator that wires together:
 *  - MotionAnalyzer
 *  - AutoCaptureController
 *  - GuidedSessionController
 *  - GuidanceOverlayView + CaptureFlashController
 *
 * Host (MainActivity) is responsible for:
 *  - Providing a SessionBridge implementation
 *  - Registering this as IPreviewDataCallBack to receive NV21 frames
 *  - Forwarding button clicks (or allowing the overlay to do so)
 */
class GuidedCaptureManager(
    private val context: Context,
    private val rootContainer: ViewGroup,
    private val sessionBridge: SessionBridge
) : GuidedSessionController.Listener, IPreviewDataCallBack {

    private val motionAnalyzer = MotionAnalyzer()
    private val autoCaptureController = AutoCaptureController()
    private val audioManager = GuidedAudioManager(context)
    private val guidedSessionController = GuidedSessionController(sessionBridge, autoCaptureController, audioManager)
    private val flashController = CaptureFlashController()
    private val overlayView: GuidanceOverlayView = GuidanceOverlayView(context)

    var isEnabled: Boolean = false
        private set

    init {
        guidedSessionController.listener = this

        motionAnalyzer.onMotionStateUpdated = { motionState ->
            autoCaptureController.onMotionStateUpdated(motionState)
        }

        autoCaptureController.onGuidanceUpdated = { guidance ->
            overlayView.guidanceResult = guidance
        }

        overlayView.listener = object : GuidanceOverlayView.Listener {
            override fun onMainButtonClicked() {
                guidedSessionController.onMainActionClicked()
            }

            override fun onRecaptureButtonClicked() {
                guidedSessionController.onRecaptureClicked()
            }
        }

        // Arch icons can be replaced with specific lower/upper assets later.
        overlayView.lowerArchIcon = null
        overlayView.upperArchIcon = null
    }

    fun enable() {
        if (isEnabled) return
        isEnabled = true
        android.util.Log.e("Guided", "GuidedCaptureManager ENABLED (creating overlay, starting motion & session)")
        if (overlayView.parent == null) {
            rootContainer.addView(
                overlayView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            android.util.Log.e("Guided", "Guided Overlay ATTACHED")
        }
        // Keep the overlay fully transparent so the camera feed is clean.
        // All UI elements (bars, panels, target box) are drawn explicitly
        // inside GuidanceOverlayView without any solid tint layer.
        overlayView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        overlayView.bringToFront()
        android.util.Log.e("Guided", "Guided Overlay VISIBLE & FRONT")

        // Enable auto-capture now that motion analysis is implemented
        autoCaptureController.enableAutoCapture = true
        android.util.Log.e("Guided", "AutoCapture ENABLED")

        motionAnalyzer.start()
        android.util.Log.e("Guided", "MotionAnalyzer STARTED")
        guidedSessionController.startGuidedSession()
        android.util.Log.e("Guided", "GuidedSessionController STARTED")
    }

    fun disable() {
        if (!isEnabled) return
        isEnabled = false
        android.util.Log.e("Guided", "GuidedCaptureManager DISABLE called")
        
        // Note: Preview callback unregistration should be handled by MainActivity
        // when camera closes or when this manager is disabled
        
        motionAnalyzer.stop()
        guidedSessionController.stopGuidedSession()
        rootContainer.removeView(overlayView)
        audioManager.release()
    }

    /**
     * IPreviewDataCallBack implementation: receives NV21 frames from camera.
     * Only processes frames when enabled and in scanning states.
     */
    override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
        if (!isEnabled) return
        
        // Only process NV21 format (not RGBA)
        if (format != IPreviewDataCallBack.DataFormat.NV21) {
            // Log occasionally for debugging
            if (System.currentTimeMillis() % 5000 < 100) {
                android.util.Log.d("Guided", "Skipping non-NV21 frame: $format")
            }
            return
        }
        
        if (data == null) {
            android.util.Log.w("Guided", "onPreviewData: data is null!")
            return
        }
        
        // Log frame reception (throttled to avoid spam)
        val now = System.currentTimeMillis()
        if (now % 2000 < 100) { // Log occasionally
            android.util.Log.d("Guided", "Received NV21 frame: ${width}x${height}, size=${data.size}")
        }
        
        // Update flash controller (for visual feedback)
        flashController.onFrameRendered()
        overlayView.showFlash = flashController.isActive
        
        // Pass frame to MotionAnalyzer (it will handle sampling and gating)
        motionAnalyzer.onFrame(data, width, height)
    }

    override fun onUiStateUpdated(
        state: ScanningState,
        mainText: String,
        buttonText: String,
        progressText: String
    ) {
        overlayView.scanningState = state
        overlayView.mainText = mainText
        overlayView.buttonText = buttonText
        overlayView.progressText = progressText
        
        // Update MotionAnalyzer with current scanning state (for gating)
        motionAnalyzer.scanningState = state
        android.util.Log.d("Guided", "Updated MotionAnalyzer scanningState to: $state")
    }

    override fun onFlashRequested() {
        flashController.triggerFlash()
        overlayView.showFlash = true
    }
}


