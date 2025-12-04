package com.oralvis.oralviscamera

import android.content.Context
import android.content.SharedPreferences

class ClinicManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("clinic_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_CLINIC_ID = "clinic_id"
        private const val KEY_CLINIC_NAME = "clinic_name"
    }
    
    fun getClinicId(): String? {
        return prefs.getString(KEY_CLINIC_ID, null)
    }
    
    fun saveClinicId(clinicId: String) {
        prefs.edit().putString(KEY_CLINIC_ID, clinicId).apply()
    }

    fun getClinicName(): String? {
        return prefs.getString(KEY_CLINIC_NAME, null)
    }

    fun saveClinicName(name: String) {
        prefs.edit().putString(KEY_CLINIC_NAME, name).apply()
    }
    
    fun hasClinicId(): Boolean {
        return getClinicId() != null
    }

    fun clearClinicId() {
        prefs.edit().remove(KEY_CLINIC_ID).apply()
        prefs.edit().remove(KEY_CLINIC_NAME).apply()
    }
}

