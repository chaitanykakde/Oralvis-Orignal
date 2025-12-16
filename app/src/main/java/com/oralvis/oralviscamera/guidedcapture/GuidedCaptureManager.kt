package com.oralvis.oralviscamera.guidedcapture

import android.content.Context
import android.view.ViewGroup
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
 *  - Feeding frame ticks into onFrame()
 *  - Forwarding button clicks (or allowing the overlay to do so)
 */
class GuidedCaptureManager(
    private val context: Context,
    private val rootContainer: ViewGroup,
    private val sessionBridge: SessionBridge
) : GuidedSessionController.Listener {

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

        // Enable auto-capture for demo builds. MotionAnalyzer currently emits
        // neutral motion; once real optical flow is wired, this will drive the
        // full three-gate capture logic.
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
        motionAnalyzer.stop()
        guidedSessionController.stopGuidedSession()
        rootContainer.removeView(overlayView)
        audioManager.release()
    }

    /**
     * To be called from the camera preview loop whenever a new frame is available.
     * The first implementation only forwards a logical tick; optical flow will be
     * implemented later.
     */
    fun onFrame() {
        if (!isEnabled) return
        android.util.Log.v("Guided", "onFrame tick")
        flashController.onFrameRendered()
        overlayView.showFlash = flashController.isActive
        motionAnalyzer.onFrame()
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
    }

    override fun onFlashRequested() {
        flashController.triggerFlash()
        overlayView.showFlash = true
    }
}


