package com.oralvis.oralviscamera.session

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class SessionManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
    private val currentSessionKey = "current_session_id"
    private val currentPatientKey = "current_patient_id"
    
    fun getCurrentSessionId(): String? {
        return prefs.getString(currentSessionKey, null)
    }
    
    fun getCurrentSessionIdOrCreate(): String {
        val sessionId = prefs.getString(currentSessionKey, null)
        return if (sessionId != null) {
            sessionId
        } else {
            val newSessionId = generateSessionId()
            prefs.edit().putString(currentSessionKey, newSessionId).apply()
            newSessionId
        }
    }
    
    fun startNewSession(): String {
        val newSessionId = generateSessionId()
        prefs.edit().putString(currentSessionKey, newSessionId).apply()
        return newSessionId
    }
    
    fun setCurrentSession(sessionId: String) {
        prefs.edit().putString(currentSessionKey, sessionId).apply()
    }
    
    private fun generateSessionId(): String {
        val timestamp = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        return "session_${timestamp}_$uuid"
    }
    
    fun clearCurrentSession() {
        prefs.edit().remove(currentSessionKey).apply()
        prefs.edit().remove(currentPatientKey).apply()
    }
    
    fun createSessionIfNeeded(): String {
        val existingSessionId = getCurrentSessionId()
        return if (existingSessionId != null) {
            existingSessionId
        } else {
            val newSessionId = generateSessionId()
            prefs.edit().putString(currentSessionKey, newSessionId).apply()
            newSessionId
        }
    }

    fun setCurrentPatientId(patientId: Long) {
        prefs.edit().putLong(currentPatientKey, patientId).apply()
    }

    fun getCurrentPatientId(): Long? {
        val stored = prefs.getLong(currentPatientKey, -1)
        return if (stored == -1L) null else stored
    }
}
