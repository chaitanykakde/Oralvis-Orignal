package com.oralvis.oralviscamera.feature.usb

// NOTE: Phase 2 USB extraction. Interface unchanged; package only.

interface CameraCommandReceiver {
    fun triggerCapture(): Boolean
    fun switchToNormalMode(): Boolean
    fun switchToFluorescenceMode(): Boolean
    fun isGuidedSessionActive(): Boolean
}

