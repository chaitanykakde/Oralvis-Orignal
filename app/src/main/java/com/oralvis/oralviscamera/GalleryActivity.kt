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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.databinding.ActivityGalleryNewBinding
import com.oralvis.oralviscamera.gallery.SequenceCard
import com.oralvis.oralviscamera.gallery.SequenceCardAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

class GalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGalleryNewBinding
    private lateinit var mediaDao: com.oralvis.oralviscamera.database.MediaDao
    private lateinit var sequenceAdapter: SequenceCardAdapter
    private lateinit var themeManager: ThemeManager
    
    // Tab indices: 0 = Upper Arch, 1 = Lower Arch, 2 = Other
    private var currentArchTab = 1 // Default to Lower Arch
    
    // All media for current patient
    private var allMedia: List<com.oralvis.oralviscamera.database.MediaRecord> = emptyList()

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
        
        val database = MediaDatabase.getDatabase(this)
        mediaDao = database.mediaDao()
        themeManager = ThemeManager(this)
        
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
        loadMediaForCurrentPatient()
    }

    private fun setupRecycler() {
        sequenceAdapter = SequenceCardAdapter(
            sequenceCards = emptyList(),
            onRgbImageClick = { card ->
                card.rgbImage?.let { media ->
                    openMediaPreview(media.filePath, media.mediaType == "Video")
                }
            },
            onFluorescenceImageClick = { card ->
                card.fluorescenceImage?.let { media ->
                    openMediaPreview(media.filePath, media.mediaType == "Video")
                }
            },
            onDiscardClick = { card ->
                showDiscardConfirmation(card)
            }
        )
        binding.mediaRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.mediaRecyclerView.adapter = sequenceAdapter
    }
    
    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentArchTab = tab?.position ?: 1
                updateTabContent()
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        
        // Set initial tab to Lower Arch
        binding.tabLayout.getTabAt(1)?.select()
        updateTabContent()
    }
    
    private fun updateTabContent() {
        binding.mediaRecyclerView.visibility = View.VISIBLE
        binding.reportsContent.visibility = View.GONE
        loadMediaForCurrentPatient()
    }
    
    private fun setupFooterButtons() {
        binding.btnGetReport.setOnClickListener {
            Toast.makeText(this, "Get Report feature coming soon", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnSaveAndSync.setOnClickListener {
            // Trigger cloud sync
            Toast.makeText(this, "Save and Sync feature coming soon", Toast.LENGTH_SHORT).show()
        }
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
        GlobalPatientManager.currentPatient.observe(this) { patient ->
            if (patient != null) {
                updatePatientInfo(patient)
                loadMediaForCurrentPatient()
            } else {
                showNoPatientState()
            }
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
    
    private fun loadMediaForCurrentPatient() {
        val patientId = GlobalPatientManager.getCurrentPatientId()
        if (patientId == null) {
            showNoPatientState()
            return
        }
        
        lifecycleScope.launch {
            try {
                mediaDao.getMediaByPatient(patientId).collectLatest { mediaList ->
                    allMedia = mediaList
                    withContext(Dispatchers.Main) {
                        val sequenceCards = groupMediaIntoSequences(mediaList)
                        if (sequenceCards.isNotEmpty()) {
                            sequenceAdapter.updateSequenceCards(sequenceCards)
                            binding.mediaRecyclerView.visibility = View.VISIBLE
                            binding.mediaEmptyState.visibility = View.GONE
                        } else {
                            sequenceAdapter.updateSequenceCards(emptyList())
                            binding.mediaRecyclerView.visibility = View.GONE
                            binding.mediaEmptyState.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GalleryActivity", "Error loading media: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.mediaRecyclerView.visibility = View.GONE
                    binding.mediaEmptyState.visibility = View.VISIBLE
                }
            }
        }
    }
    
    /**
     * Group media into sequence cards based on arch tab selection.
     */
    private fun groupMediaIntoSequences(mediaList: List<com.oralvis.oralviscamera.database.MediaRecord>): List<SequenceCard> {
        // Filter by current arch tab
        val filteredMedia = when (currentArchTab) {
            0 -> mediaList.filter { it.dentalArch == "UPPER" }
            1 -> mediaList.filter { it.dentalArch == "LOWER" }
            2 -> mediaList.filter { it.dentalArch == null } // Other = no dental arch (legacy/manual)
            else -> emptyList()
        }
        
        if (filteredMedia.isEmpty()) {
            return emptyList()
        }
        
        // Group by guidedSessionId, dentalArch, and sequenceNumber
        val sequenceMap = mutableMapOf<String, MutableMap<Int, SequenceCard>>()
        
        filteredMedia.forEach { media ->
            val arch = media.dentalArch ?: "OTHER"
            val sessionId = media.guidedSessionId ?: "legacy_${media.sessionId}"
            val sequenceNum = media.sequenceNumber ?: 1
            
            val key = "$sessionId|$arch"
            
            if (!sequenceMap.containsKey(key)) {
                sequenceMap[key] = mutableMapOf()
            }
            
            val sequenceMapForSession = sequenceMap[key]!!
            
            if (!sequenceMapForSession.containsKey(sequenceNum)) {
                sequenceMapForSession[sequenceNum] = SequenceCard(
                    sequenceNumber = sequenceNum,
                    dentalArch = arch,
                    guidedSessionId = media.guidedSessionId,
                    rgbImage = null,
                    fluorescenceImage = null
                )
            }
            
            val card = sequenceMapForSession[sequenceNum]!!
            when (media.mode) {
                "Normal" -> {
                    sequenceMapForSession[sequenceNum] = card.copy(rgbImage = media)
                }
                "Fluorescence" -> {
                    sequenceMapForSession[sequenceNum] = card.copy(fluorescenceImage = media)
                }
            }
        }
        
        // Flatten and sort by sequence number
        val allSequences = sequenceMap.values.flatMap { it.values }
        return allSequences.sortedBy { it.sequenceNumber }
    }
    
    private fun showDiscardConfirmation(card: SequenceCard) {
        AlertDialog.Builder(this)
            .setTitle("Discard Pair")
            .setMessage("Are you sure you want to discard this sequence pair? This action cannot be undone.")
            .setPositiveButton("Discard") { _, _ ->
                discardSequencePair(card)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun discardSequencePair(card: SequenceCard) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Delete RGB image if exists
                card.rgbImage?.let { media ->
                    mediaDao.deleteMediaById(media.id)
                    File(media.filePath).delete()
                }
                
                // Delete Fluorescence image if exists
                card.fluorescenceImage?.let { media ->
                    mediaDao.deleteMediaById(media.id)
                    File(media.filePath).delete()
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GalleryActivity, "Sequence pair discarded", Toast.LENGTH_SHORT).show()
                    // Reload media to refresh the list
                    loadMediaForCurrentPatient()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GalleryActivity, "Error discarding pair: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
