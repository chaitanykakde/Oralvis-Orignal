package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.Patient
import com.oralvis.oralviscamera.databinding.ActivityFindPatientsBinding
import com.oralvis.oralviscamera.home.PatientListAdapter
import androidx.fragment.app.setFragmentResultListener
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class FindPatientsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFindPatientsBinding
    private lateinit var patientDao: com.oralvis.oralviscamera.database.PatientDao
    private lateinit var mediaDao: com.oralvis.oralviscamera.database.MediaDao
    private lateinit var patientAdapter: PatientListAdapter
    private lateinit var galleryAdapter: SessionMediaGridAdapter
    private lateinit var themeManager: ThemeManager
    
    private var allPatients: List<Patient> = emptyList()
    private var currentSearchQuery: String = ""
    private var selectedPatient: Patient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize GlobalPatientManager
        GlobalPatientManager.initialize(this)
        LocalPatientIdManager.initialize(this)
        
        binding = ActivityFindPatientsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        val database = MediaDatabase.getDatabase(this)
        patientDao = database.patientDao()
        mediaDao = database.mediaDao()
        themeManager = ThemeManager(this)
        
        setupRecyclers()
        setupActions()
        setupSearch()
        observePatients()
        observeCurrentPatient()
        applyTheme()
    }
    
    override fun onResume() {
        super.onResume()
        applyTheme()
        // Refresh gallery if patient is already selected
        selectedPatient?.let { loadPatientGallery(it) }
    }

    private fun setupRecyclers() {
        // Patient list adapter
        patientAdapter = PatientListAdapter { patient ->
            // View patient data without changing global selection
            selectPatient(patient, updateGlobal = false)
        }
        binding.patientsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.patientsRecyclerView.adapter = patientAdapter
        
        // Gallery adapter
        galleryAdapter = SessionMediaGridAdapter(emptyList()) { media ->
            openMediaPreview(media.filePath, media.mediaType == "Video")
        }
        binding.galleryRecyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.galleryRecyclerView.adapter = galleryAdapter
    }

    private fun setupActions() {
        binding.navCamera.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
        
        binding.navGallery.setOnClickListener {
            if (GlobalPatientManager.hasPatientSelected()) {
                val intent = Intent(this, GalleryActivity::class.java)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Please select a patient first", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.navFindPatients.setOnClickListener {
            // Already on Find Patients screen
        }
        
        binding.navPatient.setOnClickListener {
            // Open patient selection dialog
            openPatientDialogForSelection()
        }
    }

    private fun setupSearch() {
        binding.searchPatientsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim().orEmpty()
                applyPatientFilter()
            }
        })
    }

    private fun observePatients() {
        lifecycleScope.launch {
            patientDao.observePatients().collectLatest { list ->
                allPatients = list
                applyPatientFilter()
            }
        }
    }
    
    private fun observeCurrentPatient() {
        GlobalPatientManager.currentPatient.observe(this) { patient ->
            // Reflect global patient selection in UI, but don't change local viewing
            // The global selection is shown for reference, but clicking patients in list
            // only views their data without changing global selection
            if (patient != null) {
                // Update UI to show which patient is globally selected
                // But keep viewing the currently selected patient in the list
            }
        }
    }

    private fun applyPatientFilter() {
        val filtered = if (currentSearchQuery.isEmpty()) {
            allPatients
        } else {
            allPatients.filter { 
                it.displayName.contains(currentSearchQuery, ignoreCase = true) ||
                it.code.contains(currentSearchQuery, ignoreCase = true) ||
                it.phone?.contains(currentSearchQuery, ignoreCase = true) == true
            }
        }
        patientAdapter.submitList(filtered)
        binding.patientsEmptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }
    
    private fun selectPatient(patient: Patient, updateGlobal: Boolean = false) {
        selectedPatient = patient
        
        // Update global patient manager (this will auto-start session)
        // Only update global when explicitly requested (e.g., from patient dialog)
        if (updateGlobal) {
            GlobalPatientManager.setCurrentPatient(this, patient)
            Toast.makeText(this, "Patient selected: ${patient.displayName}", Toast.LENGTH_SHORT).show()
        }
        
        // Update gallery header
        binding.galleryHeader.text = "${patient.displayName} - Media Gallery"
        
        // Load gallery for this patient (for viewing only, doesn't change global selection)
        loadPatientGallery(patient)
        
        // Highlight selected patient in list (optional - could add visual feedback)
    }
    
    private fun loadPatientGallery(patient: Patient) {
        lifecycleScope.launch {
            try {
                mediaDao.getMediaByPatient(patient.id).collectLatest { mediaList ->
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (mediaList.isNotEmpty()) {
                            galleryAdapter = SessionMediaGridAdapter(mediaList) { media ->
                                openMediaPreview(media.filePath, media.mediaType == "Video")
                            }
                            binding.galleryRecyclerView.adapter = galleryAdapter
                            binding.galleryRecyclerView.visibility = View.VISIBLE
                            binding.galleryEmptyState.visibility = View.GONE
                        } else {
                            binding.galleryRecyclerView.visibility = View.GONE
                            binding.galleryEmptyState.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FindPatients", "Error loading gallery: ${e.message}")
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    binding.galleryRecyclerView.visibility = View.GONE
                    binding.galleryEmptyState.visibility = View.VISIBLE
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
                val patient = patientDao.getPatientById(patientId)
                if (patient != null) {
                    withContext(Dispatchers.Main) {
                        // Use GlobalPatientManager to set patient (this auto-starts session)
                        GlobalPatientManager.setCurrentPatient(this@FindPatientsActivity, patient)
                        // Update UI will happen automatically via observeCurrentPatient
                        selectPatient(patient, updateGlobal = false) // Update local UI
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
        binding.navFindPatients.setColorFilter(textPrimary) // Selected
        binding.navCamera.setColorFilter(textSecondary)
        binding.navGallery.setColorFilter(textSecondary)
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
            // Update selected label
            val patientLabel = layout.getChildAt(6) as? TextView // Patient label position
            patientLabel?.setTextColor(textPrimary)
        }
        
        // Cards
        binding.galleryCard.setCardBackgroundColor(cardColor)
        binding.patientSelectionCard.setCardBackgroundColor(cardColor)
    }
}

