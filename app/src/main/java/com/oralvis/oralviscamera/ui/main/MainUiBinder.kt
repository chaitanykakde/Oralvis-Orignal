package com.oralvis.oralviscamera.ui.main

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.oralvis.oralviscamera.databinding.ActivityMainBinding

/**
 * MainUiBinder
 *
 * Phase 1 (structure-only) helper that encapsulates
 * simple, view-only wiring for MainActivity without
 * changing any business logic or behavior.
 *
 * Rules for this class:
 * - No business rules, camera, USB, or session logic.
 * - Only view binding / insets / visibility helpers that
 *   are delegated to from MainActivity.
 */
class MainUiBinder(
    private val binding: ActivityMainBinding
) {

    /**
     * Apply edge-to-edge window insets to the root container.
     * Behavior identical to the original inline code in MainActivity.
     */
    fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v: View, insets: WindowInsetsCompat ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    /**
     * Wire bottom navigation bar click listeners.
     * All behavior is delegated to the provided callbacks.
     */
    fun bindBottomNavigation(
        onNavCameraClicked: () -> Unit,
        onNavGalleryClicked: () -> Unit,
        onNavFindPatientsClicked: () -> Unit,
        onNavPatientClicked: () -> Unit
    ) {
        binding.navCamera.setOnClickListener {
            onNavCameraClicked()
        }
        binding.navGallery.setOnClickListener {
            onNavGalleryClicked()
        }
        binding.navFindPatients.setOnClickListener {
            onNavFindPatientsClicked()
        }
        binding.navPatient.setOnClickListener {
            onNavPatientClicked()
        }
    }

    /**
     * Wire capture and record buttons, delegating behavior to callbacks.
     */
    fun bindCaptureControls(
        onCaptureClicked: () -> Unit,
        onRecordClicked: () -> Unit
    ) {
        binding.btnCapture.setOnClickListener {
            onCaptureClicked()
        }
        binding.btnRecord.setOnClickListener {
            onRecordClicked()
        }
    }

    /**
     * Wire Start Session / Start Guided Session buttons with a shared callback.
     */
    fun bindStartSessionButtons(
        onStartSessionClicked: () -> Unit
    ) {
        val listener = View.OnClickListener {
            onStartSessionClicked()
        }
        binding.btnStartSession.setOnClickListener(listener)
        binding.btnStartGuidedSession.setOnClickListener(listener)
    }

    /**
     * Wire debug settings button to a delegated callback.
     */
    fun bindDebugSettingsButton(
        onDebugSettingsClicked: () -> Unit
    ) {
        binding.btnDebugSettings.setOnClickListener {
            onDebugSettingsClicked()
        }
    }

    /**
     * Wire camera core UI controls that only manipulate the settings panel UI.
     * Behavior is delegated entirely to the provided callbacks.
     */
    fun bindCameraCoreUi(
        onSettingsClicked: () -> Unit,
        onCloseSettingsClicked: () -> Unit,
        onSettingsScrimClicked: () -> Unit,
        onEditPatientClicked: () -> Unit
    ) {
        binding.btnSettings.setOnClickListener {
            onSettingsClicked()
        }
        binding.btnCloseSettings.setOnClickListener {
            onCloseSettingsClicked()
        }
        binding.settingsScrim.setOnClickListener {
            onSettingsScrimClicked()
        }
        binding.btnEditPatient.setOnClickListener {
            onEditPatientClicked()
        }
    }

    /**
     * Setup guided session specific UI: visibility toggles and recycler setup.
     * The recycler configuration is delegated to the provided callback.
     */
    fun setupGuidedSessionUi(
        onSetupSessionMediaRecycler: () -> Unit
    ) {
        binding.sessionButtonsContainer.visibility = View.VISIBLE
        binding.btnStartSession.visibility = View.GONE
        onSetupSessionMediaRecycler()
    }
}

