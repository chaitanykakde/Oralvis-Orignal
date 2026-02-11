package com.oralvis.oralviscamera.feature.session.flow

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.oralvis.oralviscamera.GlobalPatientManager
import com.oralvis.oralviscamera.MainActivity
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.Session
import com.oralvis.oralviscamera.databinding.ActivityMainBinding
import com.oralvis.oralviscamera.feature.camera.preview.PreviewCallbackRouter
import com.oralvis.oralviscamera.feature.camera.state.CameraStateStore
import com.oralvis.oralviscamera.feature.guided.GuidedController
import com.oralvis.oralviscamera.feature.session.SessionController
import com.oralvis.oralviscamera.feature.guided.SessionBridge
import com.oralvis.oralviscamera.session.SessionMedia
import com.oralvis.oralviscamera.session.SessionMediaAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * SessionFlowCoordinator (Session & Patient Authority Extraction)
 *
 * Owns:
 * - Session start / finish policy
 * - Patient selection & validation
 * - Session media lifecycle (add/remove/cleanup)
 * - Empty-session cleanup
 * - Session ↔ guided coordination
 *
 * Logic bodies are moved verbatim from MainActivity; behavior must remain unchanged.
 */
class SessionFlowCoordinator(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val sessionController: SessionController,
    private val mediaDatabase: MediaDatabase,
    private val previewCallbackRouter: PreviewCallbackRouter,
    private val cameraStateStore: CameraStateStore,
    private val guidedControllerProvider: () -> GuidedController?,
    private val initializeGuidedCapture: () -> Unit,
    private val sessionMediaList: MutableList<SessionMedia>,
    private val sessionMediaAdapterProvider: () -> SessionMediaAdapter?,
    private val nextSessionMediaId: () -> Long,
    private val getSelectedPatient: () -> com.oralvis.oralviscamera.database.Patient?,
    private val setSelectedPatient: (com.oralvis.oralviscamera.database.Patient?) -> Unit,
    private val getGlobalPatientId: () -> String?,
    private val setGlobalPatientId: (String?) -> Unit,
    private val isUsbPermissionPending: () -> Boolean
) {

    // ==== Entry points from MainActivity ====
    // Ensures we only auto-prompt for a patient once per activity lifecycle
    private var hasPromptedForInitialPatient = false

    fun onStartSessionClicked() {
        android.util.Log.e("Guided", "Start Session CLICKED")

        // Check if there's a valid patient from the database (not deleted)
        val currentPatient = GlobalPatientManager.getCurrentPatient()
        if (currentPatient == null || currentPatient.id == -1L) {
            android.util.Log.e("Guided", "No valid patient selected - opening patient selection dialog")
            // Open patient selection dialog to choose or create a patient
            openPatientDialogForSession()
            return
        }

        // Verify the patient still exists in database
        activity.lifecycleScope.launch {
            try {
                val patientExists = mediaDatabase.patientDao().getPatientById(currentPatient.id)
                if (patientExists == null) {
                    android.util.Log.e("Guided", "Patient was deleted - opening patient selection dialog")
                    withContext(Dispatchers.Main) {
                        // Clear global state since patient no longer exists
                        GlobalPatientManager.clearCurrentPatient()
                        setSelectedPatient(null)
                        setGlobalPatientId(null)
                        // Open patient selection dialog
                        openPatientDialogForSession()
                    }
                    return@launch
                }

                // Patient exists, proceed with session
                withContext(Dispatchers.Main) {
                    setSelectedPatient(currentPatient)
                    activity.updatePatientInfoDisplay()
                    binding.patientInfoCard.visibility = View.VISIBLE
                    proceedWithSessionStart()
                }
            } catch (e: Exception) {
                android.util.Log.e("Guided", "Error checking patient existence: ${e.message}")
                withContext(Dispatchers.Main) {
                    openPatientDialogForSession()
                }
            }
        }
    }

    fun checkAndPromptForPatientSelection() {
        // FIX 2: Defer patient picker until USB permission is resolved
        if (isUsbPermissionPending()) return

        // Only auto-prompt once per lifecycle to avoid duplicate dialogs
        if (hasPromptedForInitialPatient) return

        // Only prompt if no patient is currently selected
        if (!GlobalPatientManager.hasPatientSelected()) {
            hasPromptedForInitialPatient = true
            // Use post to ensure UI is fully ready
            Handler(Looper.getMainLooper()).post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    android.util.Log.d("PatientSelection", "No patient selected - prompting user")
                    openPatientDialogForSession()
                }
            }
        }
    }

    fun showPatientSelectionPrompt() {
        activity.runOnUiThread {
            try {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, "Please select a patient first", Toast.LENGTH_SHORT).show()
                    // Don't automatically open dialog for USB commands to avoid lifecycle issues
                }
            } catch (e: Exception) {
                android.util.Log.e("UsbCommand", "Error showing patient selection prompt: ${e.message}")
            }
        }
    }

    fun proceedWithSessionStart() {
        var guidedController = guidedControllerProvider()
        if (guidedController == null || !guidedController.isInitialized()) {
            android.util.Log.e("Guided", "GuidedCaptureManager is null, initializing now")
            initializeGuidedCapture()
            guidedController = guidedControllerProvider()
        }
        android.util.Log.e("Guided", "PHASE B: Starting guided capture session")
        previewCallbackRouter.enableGuided(cameraStateStore.mCurrentCamera)

        // Setup guided session UI (session media recycler, session buttons)
        // Camera core UI already initialized in onCreate via setupCameraCoreUI()
        android.util.Log.d("GuidedSessionUI", "About to call setupGuidedSessionUI() from proceedWithSessionStart")
        activity.setupGuidedSessionUI()
        android.util.Log.d("GuidedSessionUI", "setupGuidedSessionUI() completed")
    }

    fun openPatientDialogForSession() {
        val fragmentManager = activity.supportFragmentManager
        fragmentManager.setFragmentResultListener(
            com.oralvis.oralviscamera.PatientSessionDialogFragment.REQUEST_KEY,
            activity
        ) { _, bundle ->
            val patientId = bundle.getLong(com.oralvis.oralviscamera.PatientSessionDialogFragment.KEY_PATIENT_ID, -1L)
            if (patientId == -1L) return@setFragmentResultListener

            activity.lifecycleScope.launch {
                val patient = mediaDatabase.patientDao().getPatientById(patientId)
                if (patient != null) {
                    withContext(Dispatchers.Main) {
                        // Clear any previous session state before starting new session
                        clearSessionState()
                        // NOTE: Camera should remain connected - do NOT call clearCameraPreview()

                        android.util.Log.d("CAMERA_LIFE", "Patient selected = ${patient.code}, camera should remain connected")
                        android.util.Log.d("CAMERA_LIFE", "Dialog dismissed")
                        android.util.Log.d("CAMERA_LIFE", "Camera still active = ${cameraStateStore.mCurrentCamera != null}")

                        GlobalPatientManager.setCurrentPatient(activity, patient)
                        setGlobalPatientId(patient.code)
                        setSelectedPatient(patient)
                        activity.updatePatientInfoDisplay()
                        binding.patientInfoCard.visibility = View.VISIBLE
                    }
                }
            }
        }
        activity.showDialogSafely(
            com.oralvis.oralviscamera.PatientSessionDialogFragment(),
            "PatientSessionDialog"
        )
    }

    fun saveCurrentSession() {
        if (sessionMediaList.isEmpty()) {
            Toast.makeText(activity, "No media to save. Capture some photos or videos first.", Toast.LENGTH_SHORT).show()
            return
        }

        val patient = getSelectedPatient()
        if (patient == null) {
            Toast.makeText(activity, "No patient selected", Toast.LENGTH_SHORT).show()
            return
        }

        // Save session logic
        activity.lifecycleScope.launch {
            try {
                val sessionId = sessionController.getCurrentSessionId()
                if (sessionId != null) {
                    // Update session with completion info
                    val session = mediaDatabase.sessionDao().getBySessionId(sessionId)
                    if (session != null) {
                        val updatedSession = session.copy(
                            completedAt = Date(),
                            mediaCount = sessionMediaList.size
                        )
                        mediaDatabase.sessionDao().update(updatedSession)
                    }

                    // Show success message
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            activity,
                            "Session saved! ${sessionMediaList.size} items captured for ${patient.displayName}",
                            Toast.LENGTH_LONG
                        ).show()

                        // Clear session and reset UI to initial state
                        sessionController.clearCurrentSession()
                        setSelectedPatient(null)
                        sessionMediaList.clear()

                        android.util.Log.d("SessionFinish", "Clearing session media UI - list size: ${sessionMediaList.size}")

                        val sessionMediaAdapter = sessionMediaAdapterProvider()

                        // Clear RecyclerView completely and ensure UI reset
                        // First, temporarily detach adapter to ensure clean reset
                        binding.sessionMediaRecycler.adapter = null

                        // Clear the list and adapter
                        sessionMediaList.clear()
                        sessionMediaAdapter?.submitList(emptyList())
                        binding.sessionMediaCount.text = "0 items"

                        // Reattach adapter with empty list
                        binding.sessionMediaRecycler.adapter = sessionMediaAdapter

                        // Force UI visibility reset - hide RecyclerView first, then show empty state
                        binding.sessionMediaRecycler.visibility = View.GONE
                        binding.emptyMediaState.visibility = View.VISIBLE

                        // Force layout refresh
                        binding.sessionMediaCard.requestLayout()
                        binding.sessionMediaRecycler.requestLayout()

                        android.util.Log.d(
                            "SessionFinish",
                            "Session media UI cleared - RecyclerView hidden: ${binding.sessionMediaRecycler.visibility == View.GONE}, Empty state visible: ${binding.emptyMediaState.visibility == View.VISIBLE}"
                        )
                        android.util.Log.d(
                            "SessionFinish",
                            "Adapter item count: ${sessionMediaAdapter?.itemCount ?: -1}, List size: ${sessionMediaList.size}"
                        )

                        // Hide patient info card
                        binding.patientInfoCard.visibility = View.GONE

                        // NOTE: Camera should remain connected - do NOT call clearCameraPreview()

                        // Reset UI to Start Session state
                        GlobalPatientManager.clearCurrentPatient()
                        setSelectedPatient(null)
                        setGlobalPatientId(null)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "No active session found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Error saving session: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun cleanupEmptySession() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentSessionId = sessionController.getCurrentSessionId()
                if (currentSessionId != null) {
                    // Check if session has any media
                    val mediaCount = mediaDatabase.mediaDao().getMediaCountBySession(currentSessionId)
                    if (mediaCount == 0) {
                        // Session is empty, remove it from database
                        val session = mediaDatabase.sessionDao().getBySessionId(currentSessionId)
                        if (session != null) {
                            mediaDatabase.sessionDao().delete(session)
                            android.util.Log.d("SessionManager", "Cleaned up empty session: $currentSessionId")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SessionManager", "Failed to cleanup empty session: ${e.message}")
            }
        }
    }

    fun clearSessionState() {
        sessionMediaList.clear()
        val sessionMediaAdapter = sessionMediaAdapterProvider()
        sessionMediaAdapter?.submitList(emptyList())
        binding.sessionMediaCount.text = "0 items"

        // Show empty state
        binding.emptyMediaState.visibility = View.VISIBLE
        binding.sessionMediaRecycler.visibility = View.GONE

        // Clear session data
        setSelectedPatient(null)
        setGlobalPatientId(null)

        // Hide patient info
        binding.patientInfoCard.visibility = View.GONE
    }

    fun addSessionMedia(filePath: String, isVideo: Boolean) {
        val media = SessionMedia(
            id = nextSessionMediaId(),
            filePath = filePath,
            isVideo = isVideo
        )
        sessionMediaList.add(0, media) // Add to beginning
        updateSessionMediaUI()

        // Scroll to show the new item
        binding.sessionMediaRecycler.smoothScrollToPosition(0)
    }

    fun removeSessionMedia(media: SessionMedia) {
        // Remove from list
        sessionMediaList.remove(media)
        updateSessionMediaUI()

        // Delete the file
        try {
            val file = java.io.File(media.filePath)
            if (file.exists()) {
                file.delete()
                Toast.makeText(activity, "Media removed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("SessionMedia", "Failed to delete file: ${e.message}")
        }
    }

    fun updateSessionMediaUI() {
        val sessionMediaAdapter = sessionMediaAdapterProvider()
        sessionMediaAdapter?.submitList(sessionMediaList.toList())

        // Update media count
        binding.sessionMediaCount.text = sessionMediaList.size.toString()

        // Show/hide empty state
        if (sessionMediaList.isEmpty()) {
            binding.emptyMediaState.visibility = View.VISIBLE
            binding.sessionMediaRecycler.visibility = View.GONE
        } else {
            binding.emptyMediaState.visibility = View.GONE
            binding.sessionMediaRecycler.visibility = View.VISIBLE
        }
    }

    fun clearSessionMediaPreview() {
        android.util.Log.d("SessionMedia", "Clearing session media preview after guided session completion")

        // Clear the session media list (this affects only the camera preview)
        sessionMediaList.clear()

        // Update UI to show empty state
        updateSessionMediaUI()

        android.util.Log.d("SessionMedia", "Session media preview cleared - media remains in gallery database")
    }
}

