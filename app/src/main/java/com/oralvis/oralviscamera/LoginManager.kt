package com.oralvis.oralviscamera

import android.content.Context
import android.content.SharedPreferences

/**
 * Login state manager - completely independent of ClinicId.
 * 
 * Responsibilities:
 * - Store login state using SharedPreferences
 * - Manage client ID after successful login
 * 
 * Rules:
 * - Must NOT reference ClinicManager
 * - Must NOT read or write ClinicId
 * - Must be completely independent
 */
class LoginManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_CLIENT_ID = "client_id"
    }
    
    /**
     * Check if user is currently logged in.
     */
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }
    
    /**
     * Save login success state and client ID.
     * Called after successful login API response.
     */
    fun saveLoginSuccess(clientId: String) {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_CLIENT_ID, clientId)
            .apply()
    }
    
    /**
     * Get the logged-in client ID.
     * Returns null if not logged in.
     */
    fun getClientId(): String? {
        return if (isLoggedIn()) {
            prefs.getString(KEY_CLIENT_ID, null)
        } else {
            null
        }
    }
    
    /**
     * Clear login state (logout).
     * Does NOT affect ClinicId or any clinic-related data.
     */
    fun clearLogin() {
        prefs.edit()
            .remove(KEY_IS_LOGGED_IN)
            .remove(KEY_CLIENT_ID)
            .apply()
    }
}

