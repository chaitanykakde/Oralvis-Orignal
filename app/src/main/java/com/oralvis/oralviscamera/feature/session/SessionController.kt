package com.oralvis.oralviscamera.feature.session

import com.oralvis.oralviscamera.session.SessionManager

/**
 * SessionController
 *
 * Phase 1 (structure-only) pass-through controller that wraps SessionManager.
 * This class must not introduce new logic; it simply forwards calls.
 *
 * Activities can depend on this controller instead of talking directly to
 * SessionManager, preparing a clean seam for future MVVM/ViewModel layers.
 */
class SessionController(
    private val sessionManager: SessionManager
) {

    fun getCurrentSessionId(): String? = sessionManager.getCurrentSessionId()

    fun getCurrentSessionIdOrCreate(): String =
        sessionManager.getCurrentSessionIdOrCreate()

    fun startNewSession(): String =
        sessionManager.startNewSession()

    fun setCurrentSession(sessionId: String) =
        sessionManager.setCurrentSession(sessionId)

    fun clearCurrentSession() =
        sessionManager.clearCurrentSession()

    fun createSessionIfNeeded(): String =
        sessionManager.createSessionIfNeeded()
}

