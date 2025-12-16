package com.oralvis.oralviscamera.guidedcapture

/**
 * High-level guided capture session controller.
 *
 * Mirrors the Python SessionManager state machine semantics but does not
 * perform any I/O or DB work directly. Those responsibilities are delegated
 * to an external SessionBridge implementation on Android.
 */
class GuidedSessionController(
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
                autoCaptureController.isProcessingActive = true
            }

            ScanningState.SCANNING_LOWER -> {
                state = ScanningState.READY_TO_SCAN_UPPER
                autoCaptureController.isProcessingActive = false
            }

            ScanningState.READY_TO_SCAN_UPPER -> {
                state = ScanningState.SCANNING_UPPER
                autoCaptureController.isProcessingActive = true
            }

            ScanningState.SCANNING_UPPER -> {
                state = ScanningState.COMPLETE
                autoCaptureController.isProcessingActive = false
                sessionBridge.onGuidedSessionComplete(guidedSessionId)
            }

            ScanningState.COMPLETE -> {
                // Full reset to beginning of lower scan for the SAME guidedSessionId.
                // A brand new guidedSessionId will be allocated only when the user
                // presses the global "Start Session" button again.
                lowerSequence = 0
                upperSequence = 0
                state = ScanningState.READY_TO_SCAN_LOWER
                autoCaptureController.isProcessingActive = false
            }
        }
        notifyUi()
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

    private fun handleAutoCapture() {
        val currentGuidedId = guidedSessionId ?: return

        val arch: String = when (state) {
            ScanningState.SCANNING_LOWER -> {
                lowerSequence += 1
                SessionBridge.DENTAL_ARCH_LOWER
            }

            ScanningState.SCANNING_UPPER -> {
                upperSequence += 1
                SessionBridge.DENTAL_ARCH_UPPER
            }

            else -> {
                // Auto capture should not fire in non-scanning states
                return
            }
        }

        val sequence = if (arch == SessionBridge.DENTAL_ARCH_LOWER) {
            lowerSequence
        } else {
            upperSequence
        }

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
                "Place scanner at LEFT of LOWER arch.",
                "Start Lower Scan",
                "1/2"
            )

            ScanningState.SCANNING_LOWER -> Triple(
                "Move along LOWER arch to the right.",
                "Finish Lower Scan",
                "1/2"
            )

            ScanningState.READY_TO_SCAN_UPPER -> Triple(
                "Place scanner at LEFT of UPPER arch.",
                "Start Upper Scan",
                "2/2"
            )

            ScanningState.SCANNING_UPPER -> Triple(
                "Move along UPPER arch to the right.",
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


