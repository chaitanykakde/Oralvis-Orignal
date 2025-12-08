package com.oralvis.oralviscamera

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages local incremental patient IDs (P001, P002, etc.) for display purposes.
 * The global patient ID (stored in patient.code) is still used for cloud sync.
 */
object LocalPatientIdManager {
    private const val PREFS_NAME = "local_patient_id_prefs"
    private const val KEY_NEXT_ID = "next_local_patient_id"
    private const val KEY_PREFIX = "patient_local_id_"
    
    private var prefs: SharedPreferences? = null
    
    /**
     * Initialize the LocalPatientIdManager with application context.
     */
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    
    /**
     * Get or create a local ID for a patient.
     * @param patientId The database ID of the patient
     * @return Local ID in format P001, P002, etc.
     */
    fun getLocalId(patientId: Long): String {
        val prefs = prefs ?: return "P${String.format("%03d", patientId.toInt())}"
        
        // Check if local ID already exists for this patient
        val existingLocalId = prefs.getString("$KEY_PREFIX$patientId", null)
        if (existingLocalId != null) {
            return existingLocalId
        }
        
        // Generate new local ID
        val nextId = prefs.getInt(KEY_NEXT_ID, 1)
        val localId = "P${String.format("%03d", nextId)}"
        
        // Store mapping and increment counter
        prefs.edit()
            .putString("$KEY_PREFIX$patientId", localId)
            .putInt(KEY_NEXT_ID, nextId + 1)
            .apply()
        
        return localId
    }
    
    /**
     * Get the local ID for a patient if it exists, otherwise return null.
     */
    fun getLocalIdOrNull(patientId: Long): String? {
        val prefs = prefs ?: return null
        return prefs.getString("$KEY_PREFIX$patientId", null)
    }
    
    /**
     * Clear all local ID mappings (useful for testing or reset)
     */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}

