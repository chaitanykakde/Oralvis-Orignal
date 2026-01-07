package com.oralvis.oralviscamera

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.oralvis.oralviscamera.database.Patient
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Global singleton to manage the currently selected patient across the entire app.
 * When a patient is selected, a session is automatically started.
 */
object GlobalPatientManager {
    private const val PREFS_NAME = "global_patient_prefs"
    private const val KEY_PATIENT_ID = "current_patient_id"
    
    private var prefs: SharedPreferences? = null
    private val _currentPatient = MutableLiveData<Patient?>()
    val currentPatient: LiveData<Patient?> = _currentPatient
    
    private var sessionManager: SessionManager? = null
    
    /**
     * Initialize the GlobalPatientManager with application context.
     * Should be called in Application class or early in app lifecycle.
     */
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sessionManager = SessionManager(context)
            loadCurrentPatient(context)
        }
    }
    
    /**
     * Get the currently selected patient ID, or null if none selected.
     */
    fun getCurrentPatientId(): Long? {
        val patientId = prefs?.getLong(KEY_PATIENT_ID, -1L) ?: -1L
        return if (patientId == -1L) null else patientId
    }
    
    /**
     * Get the currently selected patient object, or null if none selected.
     */
    fun getCurrentPatient(): Patient? {
        return _currentPatient.value
    }
    
    /**
     * Set the current patient. This will automatically start a new session.
     * @param context Application context
     * @param patient The patient to set as current
     */
    fun setCurrentPatient(context: Context, patient: Patient) {
        prefs?.edit()?.putLong(KEY_PATIENT_ID, patient.id)?.apply()
        _currentPatient.value = patient

        // Log patient resolution for debugging
        android.util.Log.d("GlobalPatientManager", "Resolved patient cloudId=${patient.code} → localId=${patient.id}")

        // Auto-start session when patient is selected
        sessionManager?.let { manager ->
            val sessionId = manager.startNewSession()
            manager.setCurrentPatientId(patient.id)
            manager.setCurrentSession(sessionId)
        }
    }
    
    /**
     * Clear the current patient selection.
     */
    fun clearCurrentPatient() {
        prefs?.edit()?.remove(KEY_PATIENT_ID)?.apply()
        _currentPatient.value = null
        sessionManager?.clearCurrentSession()
    }
    
    /**
     * Check if a patient is currently selected.
     */
    fun hasPatientSelected(): Boolean {
        return getCurrentPatientId() != null
    }
    
    /**
     * Load the current patient from SharedPreferences and update LiveData.
     * CRITICAL: Never silently clear invalid patient IDs.
     */
    private fun loadCurrentPatient(context: Context) {
        val patientId = getCurrentPatientId()
        if (patientId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val database = MediaDatabase.getDatabase(context)
                    val patient = database.patientDao().getPatientById(patientId)
                    withContext(Dispatchers.Main) {
                        if (patient != null) {
                            _currentPatient.value = patient
                            android.util.Log.d("GlobalPatientManager", "Successfully loaded patient: ${patient.displayName} (id=$patientId)")
                        } else {
                            // CRITICAL: Patient ID exists in SharedPreferences but not in DB
                            android.util.Log.e("GlobalPatientManager",
                                "CRITICAL ERROR: Patient ID $patientId stored in SharedPreferences " +
                                "but patient not found in database. This will cause gallery to show no media. " +
                                "User must re-select patient.")

                            // DO NOT clear silently - this would mask the problem
                            // Let the app handle invalid patientId gracefully
                            _currentPatient.value = null
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GlobalPatientManager", "Exception loading patient $patientId", e)
                    _currentPatient.value = null
                }
            }
        } else {
            android.util.Log.d("GlobalPatientManager", "No patient ID stored in SharedPreferences")
            _currentPatient.value = null
        }
    }
    
    /**
     * Refresh the current patient from database.
     * Useful when patient data might have been updated.
     */
    fun refreshCurrentPatient(context: Context) {
        loadCurrentPatient(context)
    }
}

