package com.oralvis.oralviscamera

import android.content.Context
import android.util.Log
import com.oralvis.oralviscamera.cloud.CloudSyncService
import com.oralvis.oralviscamera.database.Patient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates two-phase media sync: Local→Cloud then Cloud→Local.
 *
 * Phase 1: Upload local media to cloud (must succeed)
 * Phase 2: Download missing cloud media to local (only if Phase 1 succeeds)
 *
 * This ensures data consistency and prevents sync loops.
 */
object SyncOrchestrator {
    private const val TAG = "SyncOrchestrator"

    /**
     * Execute two-phase sync for a patient.
     *
     * @param context Application context
     * @param patient Patient to sync media for
     * @param onProgress Progress callback for Phase 1 (upload)
     * @param onPhaseChange Callback when moving to Phase 2
     * @return Overall sync result
     */
    suspend fun syncPatientMediaTwoPhase(
        context: Context,
        patient: Patient,
        onProgress: ((Int, Int) -> Unit)? = null,
        onPhaseChange: (() -> Unit)? = null
    ): TwoPhaseSyncResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting two-phase sync for patient ${patient.code}")

            // PHASE 1: Local → Cloud (upload)
            Log.d(TAG, "Phase 1: Starting Local → Cloud upload")
            val uploadResult = CloudSyncService.syncPatientMedia(
                context = context,
                patient = patient,
                onProgress = onProgress
            )

            // Check if upload phase succeeded (no errors and at least some successes or no files to upload)
            if (uploadResult.errorCount > 0) {
                val errorMsg = "Upload phase failed: ${uploadResult.error ?: "Unknown error"}"
                Log.e(TAG, errorMsg)
                return@withContext TwoPhaseSyncResult(
                    success = false,
                    uploadResult = uploadResult,
                    downloadResult = null,
                    error = errorMsg
                )
            }

            Log.d(TAG, "Phase 1 completed successfully: ${uploadResult.successCount} uploads")

            // Notify phase change
            onPhaseChange?.invoke()

            // PHASE 2: Cloud → Local (download)
            Log.d(TAG, "Phase 2: Starting Cloud → Local download")
            val downloadResult = CloudMediaSyncManager.syncCloudMediaToLocal(
                context = context,
                patientId = patient.id,
                patientCode = patient.code,
                onProgress = null // Could add progress for downloads if needed
            )

            if (!downloadResult.success) {
                Log.w(TAG, "Download phase failed: ${downloadResult.error}")
                // Download failure is not fatal - upload succeeded
            } else {
                Log.d(TAG, "Phase 2 completed: ${downloadResult.successCount} downloads, ${downloadResult.errorCount} errors")
            }

            return@withContext TwoPhaseSyncResult(
                success = true, // Overall success if upload worked
                uploadResult = uploadResult,
                downloadResult = downloadResult,
                error = null
            )

        } catch (e: Exception) {
            val errorMsg = "Exception during two-phase sync: ${e.message}"
            Log.e(TAG, errorMsg, e)
            return@withContext TwoPhaseSyncResult(
                success = false,
                uploadResult = null,
                downloadResult = null,
                error = errorMsg
            )
        }
    }

    data class TwoPhaseSyncResult(
        val success: Boolean,
        val uploadResult: CloudSyncService.SyncResult?,
        val downloadResult: CloudMediaSyncManager.CloudSyncResult?,
        val error: String?
    )
}
