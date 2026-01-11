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
import com.oralvis.oralviscamera.gallery.SequenceCard
import com.oralvis.oralviscamera.gallery.SequenceCardAdapter
import com.oralvis.oralviscamera.SessionMediaGridAdapter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

class GalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGalleryNewBinding
    private lateinit var mediaRepository: com.oralvis.oralviscamera.database.MediaRepository
    private lateinit var sequenceAdapter: SequenceCardAdapter
    private lateinit var themeManager: ThemeManager
    
    // Tab indices: 0 = Upper Arch, 1 = Lower Arch, 2 = Other
    private var currentArchTab = 1 // Default to Lower Arch
    
    // All media for current patient
    private var allMedia: List<com.oralvis.oralviscamera.database.MediaRecordV2> = emptyList()
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
                showDiscardConfirmation(card)
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
        binding.mediaRecyclerView.visibility = View.VISIBLE
        binding.reportsContent.visibility = View.GONE

        // Re-group media for the new tab selection
        if (allMedia.isNotEmpty()) {
            android.util.Log.d("TAB_DEBUG", "Re-grouping media for tab $currentArchTab")
            val sequenceCards = groupMediaIntoSequences(allMedia)

            android.util.Log.d("TAB_DEBUG", "Tab $currentArchTab: created ${sequenceCards.size} sequence cards")

            if (sequenceCards.isNotEmpty()) {
                sequenceAdapter.updateSequenceCards(sequenceCards)
                binding.mediaRecyclerView.visibility = View.VISIBLE
                binding.mediaEmptyState.visibility = View.GONE
            } else {
                sequenceAdapter.updateSequenceCards(emptyList())
                binding.mediaRecyclerView.visibility = View.GONE
                binding.mediaEmptyState.visibility = View.VISIBLE
            }
        } else {
            android.util.Log.d("TAB_DEBUG", "No media available to re-group for tab $currentArchTab - showing empty state")
            sequenceAdapter.updateSequenceCards(emptyList())
            binding.mediaRecyclerView.visibility = View.GONE
            binding.mediaEmptyState.visibility = View.VISIBLE
            // Update empty state text to be session-specific
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
        val patient = GlobalPatientManager.getCurrentPatient()
        if (patient == null) {
            Toast.makeText(this, "No patient selected", Toast.LENGTH_SHORT).show()
            return
        }

        // Show progress indication
        binding.btnSaveAndSync.isEnabled = false
        binding.btnSaveAndSync.text = "Syncing..."

        lifecycleScope.launch {
            try {
                // Perform two-phase sync
                val result = SyncOrchestrator.syncPatientMediaTwoPhase(
                    context = this@GalleryActivity,
                    patient = patient,
                    onProgress = { current, total ->
                        // Update progress for upload phase
                        runOnUiThread {
                            binding.btnSaveAndSync.text = "Uploading... $current/$total"
                        }
                    },
                    onPhaseChange = {
                        // Update UI when moving to download phase
                        runOnUiThread {
                            binding.btnSaveAndSync.text = "Downloading..."
                        }
                    }
                )

                runOnUiThread {
                    if (result.success) {
                        val uploadCount = result.uploadResult?.successCount ?: 0
                        val downloadCount = result.downloadResult?.successCount ?: 0

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

                        // Gallery will automatically refresh via Flow observation
                    } else {
                        Toast.makeText(
                            this@GalleryActivity,
                            "Sync failed: ${result.error ?: "Unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    // Reset button state
                    binding.btnSaveAndSync.isEnabled = true
                    binding.btnSaveAndSync.text = "Save & Sync"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@GalleryActivity,
                        "Sync error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()

                    // Reset button state
                    binding.btnSaveAndSync.isEnabled = true
                    binding.btnSaveAndSync.text = "Save & Sync"
                }
            }
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
        val patientId = GlobalPatientManager.getCurrentPatientId()
        android.util.Log.d("GALLERY_DEBUG", "observeMediaForCurrentPatient called")
        android.util.Log.d("GALLERY_DEBUG", "GlobalPatientManager.getCurrentPatientId() = $patientId")
        android.util.Log.d("GALLERY_DEBUG", "GlobalPatientManager.hasPatientSelected() = ${GlobalPatientManager.hasPatientSelected()}")

        if (patientId == null) {
            android.util.Log.d("GalleryActivity", "No patient selected, redirecting to patient selection")
            redirectToPatientSelection()
            return
        }

        // Additional validation: check if patient exists in database
        lifecycleScope.launch {
            try {
                val patient = GlobalPatientManager.getCurrentPatient()
                android.util.Log.d("GALLERY_DEBUG", "GlobalPatientManager.getCurrentPatient() = $patient")
                if (patient != null) {
                    android.util.Log.d("GALLERY_DEBUG", "Patient details: id=${patient.id}, code=${patient.code}, name=${patient.displayName}")
                }

                if (patient == null) {
                    android.util.Log.e("GalleryActivity", "Invalid patientId $patientId, redirecting to patient selection")
                    redirectToPatientSelection()
                    return@launch
                }

                android.util.Log.d("GalleryActivity", "Starting session media observation for patient: ${patient.displayName} (id=$patientId)")
                observeMediaForCurrentSession()
            } catch (e: Exception) {
                android.util.Log.e("GalleryActivity", "Error validating patient: ${e.message}")
                redirectToPatientSelection()
            }
        }
    }

    private fun observeMediaForCurrentSession() {
        val patientId = GlobalPatientManager.getCurrentPatientId()
        val sessionId = SessionManager(this).getCurrentSessionId()

        android.util.Log.d("GALLERY_DEBUG", "observeMediaForCurrentSession called with patientId: $patientId, sessionId: $sessionId")

        if (patientId == null || sessionId == null) {
            android.util.Log.d("GalleryActivity", "No active patient or session, showing empty gallery")
            showSessionEmptyState()
            return
        }

        // Use repeatOnLifecycle to properly observe the Flow
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    android.util.Log.d("GalleryActivity", "Collecting session media flow for patientId: $patientId, sessionId: $sessionId")
                    android.util.Log.d("SESSION_DEBUG", "Fetching session media for patientId=$patientId, sessionId=$sessionId")
                    mediaRepository.getMediaForCurrentSession(patientId, sessionId).collect { mediaList ->
                        android.util.Log.d("GALLERY_DEBUG", "Session flow collected - mediaList.size = ${mediaList.size}")
                        android.util.Log.d("GalleryActivity", "Received session media list update: ${mediaList.size} items")
                        android.util.Log.d("SESSION_MEDIA", "Gallery received ${mediaList.size} media records for sessionId=$sessionId")

                        // Detailed logging of each media item
                        mediaList.forEachIndexed { index, media ->
                            android.util.Log.d("GALLERY_DEBUG", "SessionMedia[$index]: mediaId=${media.mediaId}, sessionId=${media.sessionId}, patientId=${media.patientId}, state=${media.state}, fileName=${media.fileName}")
                        }

                        allMedia = mediaList

                        // Update tab content with the new media (this will handle grouping and adapter updates)
                        updateTabContent()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GalleryActivity", "Error observing session media: ${e.message}")
                    binding.mediaRecyclerView.visibility = View.GONE
                    binding.mediaEmptyState.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showSessionEmptyState() {
        android.util.Log.d("GalleryActivity", "Showing session empty state")
        allMedia = emptyList()
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
    
    /**
     * Group media into sequence cards based on arch tab selection.
     */
    private fun groupMediaIntoSequences(mediaList: List<com.oralvis.oralviscamera.database.MediaRecordV2>): List<SequenceCard> {
        android.util.Log.d("GROUP_DEBUG", "groupMediaIntoSequences called with ${mediaList.size} media items")
        android.util.Log.d("GROUP_DEBUG", "Current arch tab: $currentArchTab")

        // DEBUG: Log all media with their properties
        mediaList.forEachIndexed { index, media ->
            android.util.Log.d("GROUP_DEBUG", "Media[$index]: mediaId=${media.mediaId}, fileName=${media.fileName}, arch=${media.dentalArch}, mode=${media.mode}, sessionId=${media.sessionId}, guidedId=${media.guidedSessionId}, seqNum=${media.sequenceNumber}, state=${media.state}")
        }

        // 2️⃣ Log BEFORE TAB FILTERING
        android.util.Log.d("MEDIA_DEBUG", "Before filtering: total media = ${mediaList.size}")
        val archGroups = mediaList.groupBy { it.dentalArch ?: "UNGUIDED" }
        archGroups.forEach { (arch, list) ->
            android.util.Log.d("MEDIA_DEBUG", "Arch=$arch count=${list.size}")
        }

        // DEBUG: Log current tab and media details
        android.util.Log.d("FILTER_DEBUG", "=== FILTERING DEBUG ===")
        android.util.Log.d("FILTER_DEBUG", "Current tab: $currentArchTab")
        android.util.Log.d("FILTER_DEBUG", "Total media before filtering: ${mediaList.size}")

        // Log arch distribution
        val archCounts = mediaList.groupBy { it.dentalArch ?: "NULL" }
        archCounts.forEach { (arch, list) ->
            android.util.Log.d("FILTER_DEBUG", "Arch '$arch': ${list.size} items")
        }

        // Filter by current arch tab
        // For UPPER/LOWER tabs: show guided media with that arch, plus unguided media (assigned to LOWER by default)
        // For OTHER tab: show media with other arch values or special cases
        val filteredMedia = when (currentArchTab) {
            0 -> {
                // UPPER tab: only guided UPPER media (unguided go to LOWER)
                val filtered = mediaList.filter { it.dentalArch == "UPPER" }
                android.util.Log.d("FILTER_DEBUG", "UPPER tab result: ${filtered.size} items")
                filtered
            }
            1 -> {
                // LOWER tab: guided LOWER media + all unguided media (default location)
                val guidedLower = mediaList.filter { it.dentalArch == "LOWER" }
                val unguided = mediaList.filter { it.dentalArch == null }
                val filtered = guidedLower + unguided
                android.util.Log.d("FILTER_DEBUG", "LOWER tab: guided=${guidedLower.size}, unguided=${unguided.size}, total=${filtered.size} items")
                filtered
            }
            2 -> {
                // OTHER tab: media with non-standard arch values
                val filtered = mediaList.filter { it.dentalArch != "UPPER" && it.dentalArch != "LOWER" && it.dentalArch != null }
                android.util.Log.d("FILTER_DEBUG", "OTHER tab result: ${filtered.size} items")
                filtered
            }
            else -> {
                android.util.Log.d("FILTER_DEBUG", "Unknown tab $currentArchTab, returning empty")
                emptyList()
            }
        }

        android.util.Log.d("FILTER_DEBUG", "Final filtered count: ${filteredMedia.size}")
        android.util.Log.d("FILTER_DEBUG", "=== END FILTERING DEBUG ===")

        // 3️⃣ Log AFTER TAB FILTERING
        android.util.Log.d(
            "MEDIA_DEBUG",
            "After filtering for tab=$currentArchTab, count=${filteredMedia.size}"
        )
        filteredMedia.forEach { media ->
            android.util.Log.d(
                "MEDIA_DEBUG",
                "FILTERED mediaId=${media.mediaId}, arch=${media.dentalArch}, cloud=${media.cloudFileName}, fileName=${media.fileName}"
            )
        }

        if (filteredMedia.isEmpty()) {
            android.util.Log.d("GalleryActivity", "No media found for current arch tab")
            return emptyList()
        }
        
        // Group by guidedSessionId, dentalArch, and sequenceNumber
        val sequenceMap = mutableMapOf<String, MutableMap<Int, SequenceCard>>()

        // First, handle guided media (local captured with dentalArch, sequenceNumber, and guidedSessionId)
        // Cloud media (DOWNLOADED state) is treated as unguided even if it has sequenceNumber
        val guidedMedia = filteredMedia.filter { it.dentalArch != null && it.sequenceNumber != null && it.guidedSessionId != null && it.state != MediaState.DOWNLOADED }
        val unguidedMedia = filteredMedia.filter { it.dentalArch == null || it.state == MediaState.DOWNLOADED }

        // Process guided media (normal guided capture)
        guidedMedia.forEach { media ->
            val arch = media.dentalArch!!
            val sessionId = media.guidedSessionId ?: "legacy_${media.sessionId}"
            val sequenceNum = media.sequenceNumber!!

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

        // Process unguided media (group by session + arch and pair normal + fluorescence optimally)
        val unguidedGroups = unguidedMedia.groupBy { media ->
            val arch = media.dentalArch ?: "LOWER"
            "unguided_session_${media.sessionId}|$arch"
        }

        // Process each session+arch group to create optimally paired sequence cards
        unguidedGroups.forEach { (groupKey, mediaList) ->
            val arch = groupKey.substringAfterLast("|")
            val sequenceNum = 1 // All unguided pairs get sequence number 1

            android.util.Log.d("GalleryActivity", "Processing unguided group: $groupKey with ${mediaList.size} media items")

            // Separate normal and fluorescence images
            val normalImages = mediaList.filter { it.mode == "Normal" }.sortedBy { it.captureTime }
            val fluorescenceImages = mediaList.filter { it.mode == "Fluorescence" }.sortedBy { it.captureTime }

            // Create paired cards - pair each normal with each fluorescence optimally
            val pairedCount = minOf(normalImages.size, fluorescenceImages.size)
            var pairIndex = 0

            // Create complete pairs (normal + fluorescence)
            for (i in 0 until pairedCount) {
                val cardKey = "$groupKey|paired_$i"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = sequenceNum + i,
                    dentalArch = arch,
                    guidedSessionId = null,
                    rgbImage = normalImages[i],
                    fluorescenceImage = fluorescenceImages[i]
                )

                sequenceMapForSession[sequenceNum + i] = card
                android.util.Log.d("GalleryActivity", "Created complete pair: ${card.getTitle()}, normal=${card.rgbImage?.fileName}, fluoro=${card.fluorescenceImage?.fileName}")
                pairIndex++
            }

            // Handle remaining unpaired images
            val remainingNormals = normalImages.drop(pairedCount)
            val remainingFluorescences = fluorescenceImages.drop(pairedCount)

            // Create cards for remaining normal images (with null fluorescence)
            remainingNormals.forEachIndexed { index, normalMedia ->
                val cardKey = "$groupKey|unpaired_normal_$index"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = sequenceNum + pairIndex + index,
                    dentalArch = arch,
                    guidedSessionId = null,
                    rgbImage = normalMedia,
                    fluorescenceImage = null
                )

                sequenceMapForSession[sequenceNum + pairIndex + index] = card
                android.util.Log.d("GalleryActivity", "Created unpaired normal card: ${card.getTitle()}, normal=${card.rgbImage?.fileName}")
            }

            // Create cards for remaining fluorescence images (with null normal)
            remainingFluorescences.forEachIndexed { index, fluoroMedia ->
                val cardKey = "$groupKey|unpaired_fluoro_$index"
                sequenceMap[cardKey] = mutableMapOf()
                val sequenceMapForSession = sequenceMap[cardKey]!!

                val card = SequenceCard(
                    sequenceNumber = sequenceNum + pairIndex + remainingNormals.size + index,
                    dentalArch = arch,
                    guidedSessionId = null,
                    rgbImage = null,
                    fluorescenceImage = fluoroMedia
                )

                sequenceMapForSession[sequenceNum + pairIndex + remainingNormals.size + index] = card
                android.util.Log.d("GalleryActivity", "Created unpaired fluoro card: ${card.getTitle()}, fluoro=${card.fluorescenceImage?.fileName}")
            }
        }
        
        // Flatten and sort by sequence number
        val allSequences = sequenceMap.values.flatMap { it.values }

        // Log sequence card creation summary
        val guidedCount = guidedMedia.size
        val unguidedCount = unguidedMedia.size
        android.util.Log.d(
            "MEDIA_DEBUG",
            "Created ${allSequences.size} sequence cards from ${filteredMedia.size} filtered media items (guided: $guidedCount, unguided: $unguidedCount)"
        )
        allSequences.forEachIndexed { index, card ->
            android.util.Log.d(
                "MEDIA_DEBUG",
                "SEQUENCE_CARD[$index] seq=${card.sequenceNumber}, arch=${card.dentalArch}, rgbId=${card.rgbImage?.mediaId}, fluoId=${card.fluorescenceImage?.mediaId}, guided=${card.guidedSessionId != null}"
            )
        }

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
                // TODO: Implement proper media deletion via MediaRepository
                // Delete RGB image if exists
                card.rgbImage?.let { media ->
                    // mediaRepository.deleteMedia(media.mediaId)
                    File(media.filePath ?: "").delete()
                }

                // Delete Fluorescence image if exists
                card.fluorescenceImage?.let { media ->
                    // mediaRepository.deleteMedia(media.mediaId)
                    File(media.filePath ?: "").delete()
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GalleryActivity, "Sequence pair discarded", Toast.LENGTH_SHORT).show()
                    // Media observation will handle updates automatically
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
