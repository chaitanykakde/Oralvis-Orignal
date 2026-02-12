package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import android.util.Log
import com.oralvis.oralviscamera.core.identity.ClinicIdentityManager
import com.oralvis.oralviscamera.database.MediaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        proceedAfterStorageCheck()
    }

    private fun proceedAfterStorageCheck() {
        Handler(Looper.getMainLooper()).post {
            val loginManager = LoginManager(this)

            // Ensure fresh session start - clear any lingering patient session state
            GlobalPatientManager.initialize(this)
            GlobalPatientManager.clearCurrentPatient()

            // CRITICAL: Startup integrity check for patient persistence
            performStartupIntegrityCheck()

            // CRITICAL: Auto-repair patient duplicates
            performPatientDuplicateRepair()

            // Check if user is logged in
            if (loginManager.isLoggedIn()) {
                // User is logged in → sync patients, then go to patient selection
                performPatientSyncForLoggedInUser()
                val intent = Intent(this, PatientSelectionActivity::class.java)
                startActivity(intent)
            } else {
                // User not logged in → go to login screen.
                val intent = Intent(this, LoginActivity::class.java)
                startActivity(intent)
            }
            finish()
        }
    }

    private fun performStartupIntegrityCheck() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = MediaDatabase.getDatabase(this@SplashActivity)
                val patientDao = database.patientDao()
                val mediaDao = database.mediaDao()

                // Check current patient validity
                val currentPatientId = GlobalPatientManager.getCurrentPatientId()
                if (currentPatientId != null) {
                    val patient = patientDao.getPatientById(currentPatientId)
                    if (patient == null) {
                        Log.w("StartupIntegrity", "WARNING: Stored patient ID $currentPatientId not found in database")
                        // Don't clear here - let GlobalPatientManager handle it gracefully
                    } else {
                        Log.d("StartupIntegrity", "Patient integrity OK: ${patient.displayName} (id=$currentPatientId)")
                    }
                } else {
                    Log.d("StartupIntegrity", "No patient currently selected")
                }

                // Log media distribution for debugging (READ-ONLY)
                try {
                    val allMedia = mediaDao.getAllMedia().firstOrNull()
                    val mediaDistribution = allMedia?.groupBy { it.patientId }
                    if (mediaDistribution != null && mediaDistribution.isNotEmpty()) {
                        Log.d("StartupIntegrity", "Media distribution by patientId:")
                        mediaDistribution.forEach { (patientId, media) ->
                            Log.d("StartupIntegrity", "  patientId=$patientId → ${media.size} media items")
                        }
                    } else {
                        Log.d("StartupIntegrity", "No media found in database")
                    }
                } catch (e: Exception) {
                    Log.e("StartupIntegrity", "Error checking media distribution", e)
                }
            } catch (e: Exception) {
                Log.e("StartupIntegrity", "Error during startup integrity check", e)
            }
        }
    }

    private fun performPatientDuplicateRepair() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = MediaDatabase.getDatabase(this@SplashActivity)
                val patientDao = database.patientDao()
                val mediaDao = database.mediaDao()

                // Get all patients grouped by code (cloudPatientId)
                val allPatients = patientDao.getPatientsOnce()
                val patientsByCode = allPatients.groupBy { it.code }

                // Find codes with multiple patients
                val duplicateCodes = patientsByCode.filter { it.value.size > 1 }

                if (duplicateCodes.isEmpty()) {
                    Log.d("PatientDuplicateRepair", "No duplicate patients found")
                    return@launch
                }

                Log.w("PatientDuplicateRepair", "Found ${duplicateCodes.size} patient codes with duplicates")

                for ((code, patients) in duplicateCodes) {
                    if (patients.isEmpty()) continue

                    // Sort by ID (oldest first) - keep the lowest ID as canonical
                    val sortedPatients = patients.sortedBy { it.id }
                    val canonicalPatient = sortedPatients.first()
                    val duplicatePatients = sortedPatients.drop(1)

                    Log.w("PatientDuplicateRepair", "Repairing duplicates for code=$code:")
                    Log.w("PatientDuplicateRepair", "  Canonical patient: id=${canonicalPatient.id}")
                    Log.w("PatientDuplicateRepair", "  Duplicate patients: ${duplicatePatients.map { it.id }}")

                    // Reassign all media from duplicate patients to canonical patient
                    for (duplicatePatient in duplicatePatients) {
                        // Count media before reassignment
                        val mediaBefore = mediaDao.getMediaByPatient(duplicatePatient.id).firstOrNull()?.size ?: 0
                        if (mediaBefore > 0) {
                            Log.w("PatientDuplicateRepair", "  Reassigning $mediaBefore media items from patient ${duplicatePatient.id} to ${canonicalPatient.id}")

                            // Reassign media to canonical patient
                            mediaDao.reassignMediaToPatient(duplicatePatient.id, canonicalPatient.id)

                            // Verify reassignment
                            val mediaAfter = mediaDao.getMediaByPatient(duplicatePatient.id).firstOrNull()?.size ?: 0
                            Log.w("PatientDuplicateRepair", "  Media reassignment complete: $mediaAfter items remain under old patient")
                        }

                        // Delete duplicate patient
                        patientDao.delete(duplicatePatient)
                        Log.w("PatientDuplicateRepair", "  Deleted duplicate patient id=${duplicatePatient.id}")
                    }

                    Log.w("PatientDuplicateRepair", "  Kept canonical patient: ${canonicalPatient.displayName} (id=${canonicalPatient.id})")
                }

                Log.w("PatientDuplicateRepair", "Duplicate repair completed")

            } catch (e: Exception) {
                Log.e("PatientDuplicateRepair", "Error during patient duplicate repair", e)
            }
        }
    }

    private fun performPatientSyncForLoggedInUser() {
        // Perform patient sync in background for already logged-in users
        // This ensures patient list is up-to-date when entering the app
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = PatientSyncManager.syncPatients(this@SplashActivity)
                if (result.success) {
                    Log.d("SplashActivity", "Patient sync successful: ${result.patientCount} patients")
                } else {
                    Log.w("SplashActivity", "Patient sync failed: ${result.error}")
                    // Don't show error - sync will retry on manual refresh
                }
            } catch (e: Exception) {
                Log.e("SplashActivity", "Exception during patient sync", e)
            }
        }
    }
}
