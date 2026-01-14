package com.oralvis.oralviscamera.usbserial

/**
 * Interface for dispatching camera commands from USB hardware to the app.
 * MainActivity implements this to provide controlled access to camera operations.
 */
interface CameraCommandReceiver {
    /**
     * Trigger a photo capture.
     * @return true if command was accepted and will be executed, false if rejected
     */
    fun triggerCapture(): Boolean
    
    /**
     * Switch camera to Normal (RGB) mode.
     * @return true if command was accepted, false if rejected (e.g., guided session active)
     */
    fun switchToNormalMode(): Boolean
    
    /**
     * Switch camera to Fluorescence (UV) mode.
     * @return true if command was accepted, false if rejected (e.g., guided session active)
     */
    fun switchToFluorescenceMode(): Boolean
    
    /**
     * Check if a guided capture session is currently active.
     * Used to determine command behavior (e.g., block mode switches during guided session).
     * @return true if guided session is active
     */
    fun isGuidedSessionActive(): Boolean
}
