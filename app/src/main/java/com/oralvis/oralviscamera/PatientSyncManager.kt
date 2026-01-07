package com.oralvis.oralviscamera

import android.content.Context
import android.util.Log
import com.oralvis.oralviscamera.api.ApiClient
import com.oralvis.oralviscamera.api.PatientDto
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.Patient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages patient synchronization from cloud to local database.
 * Cloud is the source of truth - local DB is a rebuildable cache.
 */
object PatientSyncManager {
    private const val TAG = "PatientSyncManager"


    /**
     * Sync all patients for the current clinic from cloud to local DB.
     * This replaces all local synced patients with the current cloud state.
     *
     * @param context Application context
     * @return Result indicating success/failure
     */
    suspend fun syncPatients(context: Context): SyncResult = withContext(Dispatchers.IO) {
        try {
            val loginManager = LoginManager(context)
            val clientId = loginManager.getClientId()

            if (clientId == null) {
                Log.w(TAG, "Cannot sync patients: no client ID available")
                return@withContext SyncResult(false, 0, "No client ID available")
            }

            // Call cloud API to get all patients for this clinic
            android.util.Log.d("PatientSync", "Using PROD API for patient list sync")
            val response = ApiClient.apiService.getPatients(
                clinicId = clientId
            )

            if (!response.isSuccessful) {
                val errorMsg = "API call failed: ${response.code()} ${response.message()}"
                Log.e(TAG, errorMsg)
                return@withContext SyncResult(false, 0, errorMsg)
            }

            val patientDtos = response.body()
            if (patientDtos.isNullOrEmpty()) {
                Log.d(TAG, "No patients found in cloud")
                // Still clear local and return success
                val database = MediaDatabase.getDatabase(context)
                database.patientDao().clearAll()
                return@withContext SyncResult(true, 0, null)
            }

            // Sync patients one by one to handle existing records properly
            val database = MediaDatabase.getDatabase(context)
            val patientDao = database.patientDao()

            var syncedCount = 0
            for (dto in patientDtos) {
                // Check if patient already exists by cloudPatientId
                val existingPatient = patientDao.getPatientByCode(dto.patientId)

                val patientToSave = if (existingPatient != null) {
                    // Update existing patient with cloud data
                    existingPatient.copy(
                        firstName = dto.name,
                        age = dto.age,
                        phone = dto.phoneNumber,
                        mobile = dto.phoneNumber
                    )
                } else {
                    // Create new patient
                    Patient(
                        code = dto.patientId,
                        firstName = dto.name,
                        lastName = "",
                        title = null,
                        gender = null,
                        age = dto.age,
                        isPregnant = false,
                        diagnosis = null,
                        appointmentTime = null,
                        checkInStatus = null,
                        phone = dto.phoneNumber,
                        email = null,
                        otp = null,
                        mobile = dto.phoneNumber,
                        dob = null,
                        addressLine1 = null,
                        addressLine2 = null,
                        area = null,
                        city = null,
                        pincode = null
                    )
                }

                patientDao.insert(patientToSave)
                syncedCount++

                Log.d(TAG, "Synced patient ${dto.patientId}: ${if (existingPatient != null) "updated existing" else "created new"} with localId=${patientDao.getPatientByCode(dto.patientId)?.id}")
            }

            Log.d(TAG, "Successfully synced $syncedCount patients")
            return@withContext SyncResult(true, syncedCount, null)

        } catch (e: Exception) {
            val errorMsg = "Exception during patient sync: ${e.message}"
            Log.e(TAG, errorMsg, e)
            return@withContext SyncResult(false, 0, errorMsg)
        }
    }

    data class SyncResult(
        val success: Boolean,
        val patientCount: Int,
        val error: String?
    )
}
