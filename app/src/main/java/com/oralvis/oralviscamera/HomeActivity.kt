package com.oralvis.oralviscamera

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.PatientDao
import com.oralvis.oralviscamera.databinding.ActivityHomeBinding
import com.oralvis.oralviscamera.home.PatientListAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var patientDao: PatientDao
    private lateinit var adapter: PatientListAdapter
    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        patientDao = MediaDatabase.getDatabase(this).patientDao()
        themeManager = ThemeManager(this)
        
        setupRecycler()
        setupActions()
        observeSessions()
        applyTheme()
    }

    private fun setupRecycler() {
        adapter = PatientListAdapter { patient ->
            openPatient(patient.id)
        }
        binding.recyclerViewPatients.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewPatients.adapter = adapter
    }

    private fun setupActions() {
        binding.btnAddPatient.setOnClickListener {
            showAddPatientBottomSheet()
        }

        binding.navHome.setOnClickListener {
            // Already on home; no action needed
        }

        binding.navCamera.setOnClickListener {
            openCamera()
            finish()
        }

        binding.navSettings.setOnClickListener {
            // TODO: Open settings
            Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
        }
        
        binding.navTheme.setOnClickListener {
            themeManager.toggleTheme()
            applyTheme()
            Toast.makeText(this, 
                if (themeManager.isDarkTheme) "Dark theme enabled" else "Light theme enabled", 
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddPatientBottomSheet() {
        val bottomSheet = AddPatientBottomSheet()
        bottomSheet.show(supportFragmentManager, AddPatientBottomSheet.TAG)
    }
    

    private fun observeSessions() {
        lifecycleScope.launch {
            patientDao.observePatients().collectLatest { list ->
                adapter.submitList(list)
                binding.emptyStateLayout.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openPatient(patientId: Long) {
        val intent = Intent(this, PatientSessionsActivity::class.java)
        intent.putExtra("PATIENT_ID", patientId)
        startActivity(intent)
    }

    private fun openCamera() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }
    
    private fun applyTheme() {
        val backgroundColor = themeManager.getBackgroundColor(this)
        val surfaceColor = themeManager.getSurfaceColor(this)
        val cardColor = themeManager.getCardColor(this)
        val textPrimary = themeManager.getTextPrimaryColor(this)
        val textSecondary = themeManager.getTextSecondaryColor(this)
        val borderColor = themeManager.getBorderColor(this)
        
        // Apply to main background
        binding.main.setBackgroundColor(backgroundColor)
        
        // Apply to navigation rail
        binding.navigationRailCard.setCardBackgroundColor(surfaceColor)
        val navLayout = binding.navigationRailCard.getChildAt(0) as? ViewGroup
        navLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is TextView) {
                    child.setTextColor(textSecondary)
                } else if (child is ImageView) {
                    child.setColorFilter(textSecondary)
                }
            }
        }
        
        // Highlight selected nav item (Home)
        binding.navHome.setColorFilter(textPrimary)
        
        // Apply to header card
        binding.headerCard.setCardBackgroundColor(cardColor)
        
        // Apply to search bar
        val searchCard = binding.headerCard.findViewById<View>(R.id.headerCard)?.let { header ->
            (header as? ViewGroup)?.getChildAt(0) as? ViewGroup
        }?.getChildAt(0) as? ViewGroup
        
        // Apply to profile section
        binding.profileName.setTextColor(textPrimary)
        binding.profileTitle.setTextColor(textSecondary)
        
        // Apply to patients card
        binding.patientsCard.setCardBackgroundColor(cardColor)
        updateTextColorsInView(binding.patientsCard, textPrimary, textSecondary)
        
        // Apply to widgets
        val widgetsColumn = binding.widgetsColumn
        for (i in 0 until widgetsColumn.childCount) {
            val widget = widgetsColumn.getChildAt(i)
            if (widget is com.google.android.material.card.MaterialCardView) {
                widget.setCardBackgroundColor(cardColor)
                updateTextColorsInView(widget, textPrimary, textSecondary)
            }
        }
        
        // Update empty state
        updateTextColorsInView(binding.emptyStateLayout, textPrimary, textSecondary)
        
        // Update add patient button colors (keep gradient but adjust if needed)
        // Button already has gradient, so we keep it as is
    }
    
    private fun updateTextColorsInView(viewGroup: ViewGroup, primaryColor: Int, secondaryColor: Int) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            when (child) {
                is TextView -> {
                    // Update text colors based on style
                    if (child.textSize > 16f || child.typeface?.isBold == true) {
                        child.setTextColor(primaryColor)
                    } else {
                        child.setTextColor(secondaryColor)
                    }
                }
                is ImageView -> {
                    // Skip images with specific drawables, only update tint
                }
                is ViewGroup -> {
                    updateTextColorsInView(child, primaryColor, secondaryColor)
                }
            }
        }
    }
}
