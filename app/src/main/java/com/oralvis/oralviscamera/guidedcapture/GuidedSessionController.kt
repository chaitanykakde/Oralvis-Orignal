package com.oralvis.oralviscamera.guidedcapture

/**
 * INTERNAL: High-level guided capture session controller.
 * Do NOT import directly - use GuidedController from feature.guided.
 *
 * Mirrors the Python SessionManager state machine semantics but does not
 * perform any I/O or DB work directly. Those responsibilities are delegated
 * to an external SessionBridge implementation on Android.
 */
internal class GuidedSessionController(
    private val sessionBridge: SessionBridge,
    private val autoCaptureController: AutoCaptureController,
    private val audioManager: GuidedAudioManager? = null
) {

    interface Listener {
        fun onUiStateUpdated(
            state: ScanningState,
            mainText: String,
            buttonText: String,
            progressText: String
        )

        fun onFlashRequested()
        
        fun onSessionComplete()
    }

    var listener: Listener? = null

    var state: ScanningState = ScanningState.READY_TO_SCAN_LOWER
        private set

    // Sequence counters per arch for guided runs
    private var lowerSequence: Int = 0
    private var upperSequence: Int = 0

    // Identifies this guided run so recapture can target the correct rows
    private var guidedSessionId: String? = null

    init {
        autoCaptureController.onCaptureTriggered = {
            handleAutoCapture()
        }
    }

    fun startGuidedSession() {
        // Ensure Android-level session and guidedSessionId exist
        guidedSessionId = sessionBridge.ensureGuidedSessionId()
        lowerSequence = 0
        upperSequence = 0

        state = ScanningState.READY_TO_SCAN_LOWER
        autoCaptureController.isProcessingActive = false
        notifyUi()
    }

    fun stopGuidedSession() {
        autoCaptureController.isProcessingActive = false
        guidedSessionId = null
    }

    fun onMainActionClicked() {
        when (state) {
            ScanningState.READY_TO_SCAN_LOWER -> {
                state = ScanningState.SCANNING_LOWER
                android.util.Log.d("GuidedSessionController", "Starting LOWER scan - state set to SCANNING_LOWER")
                // notifyUi() will update MotionAnalyzer.scanningState via listener
                notifyUi()
                // Set isProcessingActive AFTER UI state is updated (which updates MotionAnalyzer)
                autoCaptureController.isProcessingActive = true
                android.util.Log.d("GuidedSessionController", "isProcessingActive set to true for LOWER scan")
            }

            ScanningState.SCANNING_LOWER -> {
                state = ScanningState.READY_TO_SCAN_UPPER
                android.util.Log.d("GuidedSessionController", "Finishing LOWER scan - state set to READY_TO_SCAN_UPPER")
                autoCaptureController.isProcessingActive = false
                notifyUi()
            }

            ScanningState.READY_TO_SCAN_UPPER -> {
                state = ScanningState.SCANNING_UPPER
                android.util.Log.d("GuidedSessionController", "Starting UPPER scan - state set to SCANNING_UPPER")
                // notifyUi() will update MotionAnalyzer.scanningState via listener
                notifyUi()
                // Set isProcessingActive AFTER UI state is updated (which updates MotionAnalyzer)
                autoCaptureController.isProcessingActive = true
                android.util.Log.d("GuidedSessionController", "isProcessingActive set to true for UPPER scan")
            }

            ScanningState.SCANNING_UPPER -> {
                state = ScanningState.COMPLETE
                android.util.Log.d("GuidedSessionController", "Finishing UPPER scan - state set to COMPLETE")
                autoCaptureController.isProcessingActive = false
                sessionBridge.onGuidedSessionComplete(guidedSessionId)
                notifyUi()
            }

            ScanningState.COMPLETE -> {
                // Session is complete - notify listener to stop the guided capture manager
                // Session will only restart when user clicks "Start Session" button again
                listener?.onSessionComplete()
                return // Don't update UI or continue processing
            }
        }
    }

    fun onRecaptureClicked() {
        val currentGuidedId = guidedSessionId ?: return

        when (state) {
            ScanningState.READY_TO_SCAN_UPPER -> {
                // Recapture lower arch: delete all LOWER captures for this guided session
                sessionBridge.onRecaptureLower(currentGuidedId)
                lowerSequence = 0
                state = ScanningState.READY_TO_SCAN_LOWER
                autoCaptureController.isProcessingActive = false
            }

            ScanningState.COMPLETE -> {
                // Recapture upper arch: delete all UPPER captures for this guided session
                sessionBridge.onRecaptureUpper(currentGuidedId)
                upperSequence = 0
                state = ScanningState.READY_TO_SCAN_UPPER
                autoCaptureController.isProcessingActive = false
            }

            else -> {
                // Recapture should not be visible / clickable in other states
                return
            }
        }
        notifyUi()
    }

    /**
     * Handle manual capture during guided session.
     * This allows users to manually trigger captures during guided sessions,
     * and they will be properly tagged with dentalArch, sequenceNumber, and guidedSessionId.
     */
    fun handleManualCapture(): Boolean {
        val currentGuidedId = guidedSessionId ?: run {
            android.util.Log.w("GuidedSessionController", "handleManualCapture: guidedSessionId is null, not in guided session")
            return false
        }

        android.util.Log.d("GuidedSessionController", "handleManualCapture: state=$state, guidedSessionId=$currentGuidedId")

        val (arch: String, sequence: Int) = when (state) {
            ScanningState.SCANNING_LOWER -> {
                lowerSequence += 1
                android.util.Log.d("GuidedSessionController", "handleManualCapture: SCANNING_LOWER, sequence=$lowerSequence")
                Pair(SessionBridge.DENTAL_ARCH_LOWER, lowerSequence)
            }

            ScanningState.SCANNING_UPPER -> {
                upperSequence += 1
                android.util.Log.d("GuidedSessionController", "handleManualCapture: SCANNING_UPPER, sequence=$upperSequence")
                Pair(SessionBridge.DENTAL_ARCH_UPPER, upperSequence)
            }

            ScanningState.READY_TO_SCAN_LOWER -> {
                // Allow manual capture in ready state, increment sequence for lower arch
                lowerSequence += 1
                android.util.Log.d("GuidedSessionController", "handleManualCapture: READY_TO_SCAN_LOWER, sequence=$lowerSequence")
                Pair(SessionBridge.DENTAL_ARCH_LOWER, lowerSequence)
            }

            ScanningState.READY_TO_SCAN_UPPER -> {
                // Allow manual capture in ready state, increment sequence for upper arch
                upperSequence += 1
                android.util.Log.d("GuidedSessionController", "handleManualCapture: READY_TO_SCAN_UPPER, sequence=$upperSequence")
                Pair(SessionBridge.DENTAL_ARCH_UPPER, upperSequence)
            }

            else -> {
                // Manual capture not allowed in COMPLETE state
                android.util.Log.w("GuidedSessionController", "handleManualCapture: state=$state, manual capture not allowed")
                return false
            }
        }

        android.util.Log.d("GuidedSessionController", "handleManualCapture: calling onGuidedCaptureRequested with arch=$arch, sequence=$sequence")
        listener?.onFlashRequested()
        sessionBridge.onGuidedCaptureRequested(
            guidedSessionId = currentGuidedId,
            dentalArch = arch,
            sequenceNumber = sequence
        )
        return true
    }

    private fun handleAutoCapture() {
        val currentGuidedId = guidedSessionId ?: run {
            android.util.Log.w("GuidedSessionController", "handleAutoCapture: guidedSessionId is null, returning")
            return
        }

        android.util.Log.d("GuidedSessionController", "handleAutoCapture: state=$state, guidedSessionId=$currentGuidedId")

        val arch: String = when (state) {
            ScanningState.SCANNING_LOWER -> {
                lowerSequence += 1
                android.util.Log.d("GuidedSessionController", "handleAutoCapture: SCANNING_LOWER, sequence=$lowerSequence")
                SessionBridge.DENTAL_ARCH_LOWER
            }

            ScanningState.SCANNING_UPPER -> {
                upperSequence += 1
                android.util.Log.d("GuidedSessionController", "handleAutoCapture: SCANNING_UPPER, sequence=$upperSequence")
                SessionBridge.DENTAL_ARCH_UPPER
            }

            else -> {
                // Auto capture should not fire in non-scanning states
                android.util.Log.w("GuidedSessionController", "handleAutoCapture: state=$state is not a scanning state, returning")
                return
            }
        }

        val sequence = if (arch == SessionBridge.DENTAL_ARCH_LOWER) {
            lowerSequence
        } else {
            upperSequence
        }

        android.util.Log.d("GuidedSessionController", "handleAutoCapture: calling onGuidedCaptureRequested with arch=$arch, sequence=$sequence")
        listener?.onFlashRequested()
        sessionBridge.onGuidedCaptureRequested(
            guidedSessionId = currentGuidedId,
            dentalArch = arch,
            sequenceNumber = sequence
        )
    }

    private fun notifyUi() {
        val (mainText, buttonText, progressText) = when (state) {
            ScanningState.READY_TO_SCAN_LOWER -> Triple(
                "Place the scanner at the leftmost tooth of your Lower arch and Start.",
                "Start Lower Scan",
                "1/2"
            )

            ScanningState.SCANNING_LOWER -> Triple(
                "Move along the Lower arch and Complete the scan at the rightmost tooth.",
                "Finish Lower Scan",
                "1/2"
            )

            ScanningState.READY_TO_SCAN_UPPER -> Triple(
                "Excellent. Now, place the scanner at the leftmost tooth of your Upper arch and Start.",
                "Start Upper Scan",
                "2/2"
            )

            ScanningState.SCANNING_UPPER -> Triple(
                "Move along the Upper arch and Complete the scan at the rightmost tooth.",
                "Finish Upper Scan",
                "2/2"
            )

            ScanningState.COMPLETE -> Triple(
                "Scan Complete!",
                "Finish Session",
                "Done"
            )
        }

        listener?.onUiStateUpdated(state, mainText, buttonText, progressText)
        android.util.Log.e("Guided", "Guided State = $state")
        audioManager?.onStateChanged(state)
    }
}


