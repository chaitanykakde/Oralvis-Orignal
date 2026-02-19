package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaState
import com.oralvis.oralviscamera.databinding.ActivityGalleryNewBinding
import com.oralvis.oralviscamera.session.SessionManager
import com.oralvis.oralviscamera.gallery.OtherMediaAdapter
import com.oralvis.oralviscamera.gallery.SequenceCard
import com.oralvis.oralviscamera.gallery.SequenceCardAdapter
import com.oralvis.oralviscamera.SessionMediaGridAdapter
import com.oralvis.oralviscamera.feature.gallery.flow.GalleryFlowCoordinator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

class GalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGalleryNewBinding
    private lateinit var mediaRepository: com.oralvis.oralviscamera.database.MediaRepository
    private lateinit var sequenceAdapter: SequenceCardAdapter
    private lateinit var otherMediaAdapter: OtherMediaAdapter
    private lateinit var themeManager: ThemeManager
    private lateinit var galleryFlowCoordinator: GalleryFlowCoordinator
    
    // Tab indices: 0 = Upper Arch, 1 = Lower Arch, 2 = Other
    private var currentArchTab = 1 // Default to Lower Arch
    
    private var currentPatientObserver: androidx.lifecycle.Observer<com.oralvis.oralviscamera.database.Patient?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize GlobalPatientManager
        GlobalPatientManager.initialize(this)
        LocalPatientIdManager.initialize(this)
        
        binding = ActivityGalleryNewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mediaRepository = com.oralvis.oralviscamera.database.MediaRepository(this)
        themeManager = ThemeManager(this)
        
        galleryFlowCoordinator = GalleryFlowCoordinator(
            context = this,
            lifecycleOwner = this,
            mediaRepository = mediaRepository,
            onUpdateTabContent = { mediaList ->
                updateTabContentInternal(mediaList)
            },
            onShowNoPatientState = {
                showNoPatientState()
            },
            onShowSessionEmptyState = {
                showSessionEmptyState()
            },
            onRedirectToPatientSelection = {
                redirectToPatientSelection()
            },
            onSyncProgress = { current, total ->
                runOnUiThread {
                    binding.btnSaveAndSync.text = "Uploading... $current/$total"
                }
            },
            onSyncPhaseChange = {
                runOnUiThread {
                    binding.btnSaveAndSync.text = "Downloading..."
                }
            },
            onSyncComplete = { success, uploadCount, downloadCount, error ->
                runOnUiThread {
                    if (success) {
                        val message = when {
                            uploadCount > 0 && downloadCount > 0 ->
                                "Sync complete: $uploadCount uploaded, $downloadCount downloaded"
                            uploadCount > 0 ->
                                "Uploaded $uploadCount media file(s) successfully"
                            downloadCount > 0 ->
                                "Downloaded $downloadCount media file(s) successfully"
                            else ->
                                "No media to sync for this patient"
                        }
                        Toast.makeText(this@GalleryActivity, message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this@GalleryActivity,
                            "Sync failed: ${error ?: "Unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    binding.btnSaveAndSync.isEnabled = true
                    binding.btnSaveAndSync.text = "Save & Sync"
                }
            }
        )
        
        setupRecycler()
        setupTabs()
        setupActions()
        setupFooterButtons()
        observeCurrentPatient()
        applyTheme()
    }
    
    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun setupRecycler() {
        sequenceAdapter = SequenceCardAdapter(
            sequenceCards = emptyList(),
            onRgbImageClick = { card ->
                card.rgbImage?.let { media ->
                    media.filePath?.let { path ->
                        openMediaPreview(path, media.mediaType == "Video")
                    }
                }
            },
            onFluorescenceImageClick = { card ->
                card.fluorescenceImage?.let { media ->
                    media.filePath?.let { path ->
                        openMediaPreview(path, media.mediaType == "Video")
                    }
                }
            },
            onDiscardClick = { card ->
                galleryFlowCoordinator.showDiscardConfirmation(card, false) {}
            }
        )
        otherMediaAdapter = OtherMediaAdapter(
            items = emptyList(),
            onImageClick = { media ->
                media.filePath?.let { path ->
                    openMediaPreview(path, media.mediaType == "Video")
                }
            },
            onDiscardClick = { media ->
                val card = SequenceCard(
                    sequenceNumber = 1,
                    dentalArch = "OTHER",
                    guidedSessionId = null,
                    rgbImage = if (media.mode == "Normal") media else null,
                    fluorescenceImage = if (media.mode == "Fluorescence") media else null
                )
                galleryFlowCoordinator.showDiscardConfirmation(card, true) {}
            }
        )
        // Use GridLayoutManager with 2 columns for compact display
        binding.mediaRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        binding.mediaRecyclerView.adapter = sequenceAdapter
    }
    
    private fun setupTabs() {
        android.util.Log.d("TAB_DEBUG", "Setting up tabs")
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val newTab = tab?.position ?: 1
                android.util.Log.d("TAB_DEBUG", "Tab selected: $newTab (was $currentArchTab)")
                currentArchTab = newTab
                updateTabContent()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
                android.util.Log.d("TAB_DEBUG", "Tab unselected: ${tab?.position}")
            }
            override fun onTabReselected(tab: TabLayout.Tab?) {
                android.util.Log.d("TAB_DEBUG", "Tab reselected: ${tab?.position}")
            }
        })

        // Set initial tab to Lower Arch
        android.util.Log.d("TAB_DEBUG", "Setting initial tab to Lower Arch")
        binding.tabLayout.getTabAt(1)?.select()
        updateTabContent()
    }
    
    private fun updateTabContent() {
        updateTabContentInternal(galleryFlowCoordinator.allMedia)
    }
    
    private fun updateTabContentInternal(mediaList: List<com.oralvis.oralviscamera.database.MediaRecordV2>) {
        binding.mediaRecyclerView.visibility = View.VISIBLE
        binding.reportsContent.visibility = View.GONE

        if (currentArchTab == 2) {
            // Other tab: single-image list, no pairing — use dedicated adapter and layout
            val otherList = mediaList.filter { it.dentalArch != "UPPER" && it.dentalArch != "LOWER" }
                .sortedBy { it.captureTime }
            binding.mediaRecyclerView.adapter = otherMediaAdapter
            otherMediaAdapter.updateList(otherList)
            if (otherList.isNotEmpty()) {
                binding.mediaRecyclerView.visibility = View.VISIBLE
                binding.mediaEmptyState.visibility = View.GONE
            } else {
                binding.mediaRecyclerView.visibility = View.GONE
                binding.mediaEmptyState.visibility = View.VISIBLE
                binding.mediaEmptyState.findViewById<TextView>(android.R.id.text1)?.text = "No media in Other yet"
            }
            return
        }

        // Upper / Lower tabs: sequence cards with pairing
        if (mediaList.isNotEmpty()) {
            android.util.Log.d("TAB_DEBUG", "Re-grouping media for tab $currentArchTab")
            val sequenceCards = galleryFlowCoordinator.groupMediaIntoSequences(mediaList, currentArchTab)
            android.util.Log.d("TAB_DEBUG", "Tab $currentArchTab: created ${sequenceCards.size} sequence cards")
            binding.mediaRecyclerView.adapter = sequenceAdapter
            if (sequenceCards.isNotEmpty()) {
                sequenceAdapter.updateSequenceCards(sequenceCards, currentArchTab)
                binding.mediaRecyclerView.visibility = View.VISIBLE
                binding.mediaEmptyState.visibility = View.GONE
            } else {
                sequenceAdapter.updateSequenceCards(emptyList(), currentArchTab)
                binding.mediaRecyclerView.visibility = View.GONE
                binding.mediaEmptyState.visibility = View.VISIBLE
            }
        } else {
            android.util.Log.d("TAB_DEBUG", "No media available for tab $currentArchTab - showing empty state")
            binding.mediaRecyclerView.adapter = sequenceAdapter
            sequenceAdapter.updateSequenceCards(emptyList(), currentArchTab)
            binding.mediaRecyclerView.visibility = View.GONE
            binding.mediaEmptyState.visibility = View.VISIBLE
            binding.mediaEmptyState.findViewById<TextView>(android.R.id.text1)?.text = "No media captured in this session yet"
        }
    }
    
    private fun setupFooterButtons() {
        binding.btnGetReport.setOnClickListener {
            Toast.makeText(this, "Get Report feature coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveAndSync.setOnClickListener {
            // Trigger cloud sync using existing logic
            performCloudSync()
        }
    }

    private fun performCloudSync() {
        binding.btnSaveAndSync.isEnabled = false
        binding.btnSaveAndSync.text = "Syncing..."
        galleryFlowCoordinator.performCloudSync()
    }

    private fun setupActions() {
        binding.navCamera.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
        
        binding.navGallery.setOnClickListener {
            // Already on gallery screen
        }
        
        binding.navFindPatients.setOnClickListener {
            val intent = Intent(this, FindPatientsActivity::class.java)
            startActivity(intent)
        }
        
        binding.navPatient.setOnClickListener {
            openPatientDialogForSelection()
        }
    }
    
    private fun observeCurrentPatient() {
        // Remove existing observer if it exists
        currentPatientObserver?.let {
            GlobalPatientManager.currentPatient.removeObserver(it)
        }

        // Create new observer
        currentPatientObserver = androidx.lifecycle.Observer<com.oralvis.oralviscamera.database.Patient?> { patient ->
            if (patient != null) {
                updatePatientInfo(patient)
                observeMediaForCurrentSession()
            } else {
                showNoPatientState()
            }
        }

        // Add the observer
        GlobalPatientManager.currentPatient.observe(this, currentPatientObserver!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove the observer to prevent memory leaks and duplicate observer crashes
        currentPatientObserver?.let {
            GlobalPatientManager.currentPatient.removeObserver(it)
        }
    }
    
    private fun updatePatientInfo(patient: com.oralvis.oralviscamera.database.Patient) {
        binding.patientName.text = patient.displayName
        val localId = LocalPatientIdManager.getLocalId(patient.id)
        binding.patientId.text = "ID: $localId"
        binding.patientAge.text = "Age: ${patient.age ?: "N/A"}"
        binding.patientPhone.text = patient.phone ?: ""
        binding.patientInfoCard.visibility = View.VISIBLE
    }
    
    private fun showNoPatientState() {
        binding.patientInfoCard.visibility = View.GONE
        binding.mediaRecyclerView.visibility = View.GONE
        binding.mediaEmptyState.visibility = View.VISIBLE
        Toast.makeText(this, "Please select a patient first", Toast.LENGTH_SHORT).show()
    }
    
    private fun observeMediaForCurrentPatient() {
        galleryFlowCoordinator.observeMediaForCurrentPatient()
    }

    private fun observeMediaForCurrentSession() {
        galleryFlowCoordinator.observeMediaForCurrentSession()
    }

    private fun showSessionEmptyState() {
        android.util.Log.d("GalleryActivity", "Showing session empty state")
        binding.mediaRecyclerView.visibility = View.GONE
        binding.mediaEmptyState.visibility = View.VISIBLE
        // Update empty state text to be session-specific
        binding.mediaEmptyState.findViewById<TextView>(android.R.id.text1)?.text = "No media captured in this session yet"
    }

    private fun redirectToPatientSelection() {
        android.util.Log.d("GalleryActivity", "Redirecting to patient selection due to invalid/no patient")
        val intent = Intent(this, FindPatientsActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish() // Close gallery since patient selection is required
    }

    private fun openMediaPreview(media: com.oralvis.oralviscamera.database.MediaRecord) {
        try {
            val intent = Intent(this, com.oralvis.oralviscamera.MediaViewerActivity::class.java).apply {
                putExtra("media_path", media.filePath)
                putExtra("is_video", media.mediaType == "Video")
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("GalleryActivity", "Cannot open media preview: ${e.message}")
            Toast.makeText(this, "Cannot open media preview", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openPatientDialogForSelection() {
        supportFragmentManager.setFragmentResultListener(
            PatientSessionDialogFragment.REQUEST_KEY,
            this
        ) { _, bundle ->
            val patientId = bundle.getLong(PatientSessionDialogFragment.KEY_PATIENT_ID, -1L)
            if (patientId == -1L) return@setFragmentResultListener

            lifecycleScope.launch {
                val database = MediaDatabase.getDatabase(this@GalleryActivity)
                val patient = database.patientDao().getPatientById(patientId)
                if (patient != null) {
                    withContext(Dispatchers.Main) {
                        GlobalPatientManager.setCurrentPatient(this@GalleryActivity, patient)
                    }
                }
            }
        }

        PatientSessionDialogFragment().show(
            supportFragmentManager,
            "PatientSessionDialog"
        )
    }
    
    private fun openMediaPreview(filePath: String, isVideo: Boolean) {
        try {
            val intent = Intent(this, MediaViewerActivity::class.java).apply {
                putExtra("media_path", filePath)
                putExtra("is_video", isVideo)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open media preview", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun applyTheme() {
        val backgroundColor = themeManager.getBackgroundColor(this)
        val surfaceColor = themeManager.getSurfaceColor(this)
        val textPrimary = themeManager.getTextPrimaryColor(this)
        val textSecondary = themeManager.getTextSecondaryColor(this)
        
        // Main background
        binding.main.setBackgroundColor(backgroundColor)
        
        // Navigation rail
        binding.navigationRailCard.setCardBackgroundColor(surfaceColor)
        
        // Update nav icons and text
        binding.navLogo.setColorFilter(textPrimary)
        binding.navGallery.setColorFilter(textPrimary) // Selected
        binding.navCamera.setColorFilter(textSecondary)
        binding.navFindPatients.setColorFilter(textSecondary)
        binding.navPatient.setColorFilter(textSecondary)
        
        // Nav text labels
        val navLayout = binding.navigationRailCard.getChildAt(0) as? ViewGroup
        navLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is TextView) {
                    child.setTextColor(textSecondary)
                }
            }
            // Update selected label (Gallery)
            val galleryLabel = layout.getChildAt(4) as? TextView
            galleryLabel?.setTextColor(textPrimary)
        }
    }
}
