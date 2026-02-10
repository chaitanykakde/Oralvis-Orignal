package com.oralvis.oralviscamera.feature.guided

/**
 * Android-side bridge between the guided capture engine and the existing
 * session / media / camera infrastructure.
 *
 * This is intentionally an interface so MainActivity (or a helper class) can
 * provide a concrete implementation that:
 *  - Ensures a Session row exists for the selected patient
 *  - Invokes the existing still-capture pipeline
 *  - Creates MediaRecord rows with dentalArch, sequenceNumber, guidedSessionId
 *  - Deletes rows+files when recapturing lower/upper arches
 */
interface SessionBridge {

    /**
     * Ensure that a logical "guided session id" exists for the current Android session.
     * This id is used purely to group media rows for recapture semantics.
     *
     * Implementations are free to derive this from:
     *  - Current SessionManager sessionId
     *  - A random UUID
     *  - Or a combination thereof
     */
    fun ensureGuidedSessionId(): String

    /**
     * Request a guided still capture for the current arch.
     * Implementations must route this to the existing still-capture pipeline.
     */
    fun onGuidedCaptureRequested(
        guidedSessionId: String,
        dentalArch: String,
        sequenceNumber: Int
    )

    /**
     * Called when the guided session is fully complete (both arches).
     * Implementations may persist any additional metadata if needed.
     */
    fun onGuidedSessionComplete(guidedSessionId: String?)

    /**
     * Delete all LOWER-arch guided media for the given guided session id.
     */
    fun onRecaptureLower(guidedSessionId: String)

    /**
     * Delete all UPPER-arch guided media for the given guided session id.
     */
    fun onRecaptureUpper(guidedSessionId: String)

    companion object {
        const val DENTAL_ARCH_LOWER = "LOWER"
        const val DENTAL_ARCH_UPPER = "UPPER"
    }
}
