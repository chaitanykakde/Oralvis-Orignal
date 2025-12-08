package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.databinding.ActivityGalleryNewBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class GalleryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGalleryNewBinding
    private lateinit var mediaDao: com.oralvis.oralviscamera.database.MediaDao
    private lateinit var galleryAdapter: SessionMediaGridAdapter
    private lateinit var themeManager: ThemeManager
    
    private var currentTab = 0 // 0 = Media Gallery, 1 = Reports

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
        observeCurrentPatient()
        applyTheme()
    }
    
    override fun onResume() {
        super.onResume()
        applyTheme()
        loadMediaForCurrentPatient()
    }

    private fun setupRecycler() {
        galleryAdapter = SessionMediaGridAdapter(emptyList()) { media ->
            openMediaPreview(media.filePath, media.mediaType == "Video")
        }
        binding.mediaRecyclerView.layoutManager = GridLayoutManager(this, 4)
        binding.mediaRecyclerView.adapter = galleryAdapter
    }
    
    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                updateTabContent()
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        
        // Set initial tab
        updateTabContent()
    }
    
    private fun updateTabContent() {
        if (currentTab == 0) {
            // Media Gallery tab
            binding.mediaRecyclerView.visibility = View.VISIBLE
            binding.reportsContent.visibility = View.GONE
            loadMediaForCurrentPatient()
        } else {
            // Reports tab
            binding.mediaRecyclerView.visibility = View.GONE
            binding.reportsContent.visibility = View.VISIBLE
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
            // Open patient selection dialog
            openPatientDialogForSelection()
        }
    }
    
    private fun observeCurrentPatient() {
        GlobalPatientManager.currentPatient.observe(this) { patient ->
            if (patient != null) {
                updatePatientInfo(patient)
                if (currentTab == 0) {
                    loadMediaForCurrentPatient()
                }
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
                    withContext(Dispatchers.Main) {
                        if (mediaList.isNotEmpty()) {
                            galleryAdapter = SessionMediaGridAdapter(mediaList) { media ->
                                openMediaPreview(media.filePath, media.mediaType == "Video")
                            }
                            binding.mediaRecyclerView.adapter = galleryAdapter
                            binding.mediaRecyclerView.visibility = View.VISIBLE
                            binding.mediaEmptyState.visibility = View.GONE
                        } else {
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
                        // Use GlobalPatientManager to set patient (this auto-starts session)
                        GlobalPatientManager.setCurrentPatient(this@GalleryActivity, patient)
                        // Update UI will happen automatically via observeCurrentPatient
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
        val cardColor = themeManager.getCardColor(this)
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
            val galleryLabel = layout.getChildAt(4) as? TextView // Gallery label position
            galleryLabel?.setTextColor(textPrimary)
        }
        
        // Patient info card is now a LinearLayout with transparent background
        // No need to set background color
    }
}

