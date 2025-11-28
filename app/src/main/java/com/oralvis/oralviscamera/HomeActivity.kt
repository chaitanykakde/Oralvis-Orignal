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
    
    override fun onResume() {
        super.onResume()
        // Reapply theme when returning from other activities to sync theme changes
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
        
        // ============= MAIN BACKGROUND =============
        binding.main.setBackgroundColor(backgroundColor)
        
        // ============= NAVIGATION RAIL =============
        binding.navigationRailCard.setCardBackgroundColor(surfaceColor)
        
        // Update all nav icons and text
        binding.navLogo.setColorFilter(textPrimary)
        binding.navHome.setColorFilter(textPrimary) // Selected item
        binding.navCamera.setColorFilter(textSecondary)
        binding.navPatients.setColorFilter(textSecondary)
        binding.navSettings.setColorFilter(textSecondary)
        binding.navTheme.setColorFilter(textSecondary)
        
        // Nav text labels
        val navLayout = binding.navigationRailCard.getChildAt(0) as? ViewGroup
        navLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is TextView && child.id != View.NO_ID) {
                    child.setTextColor(textSecondary)
                }
            }
        }
        binding.navThemeText?.setTextColor(textSecondary)
        
        // ============= HEADER CARD =============
        binding.headerCard.setCardBackgroundColor(cardColor)
        
        // Get header layout
        val headerLayout = binding.headerCard.getChildAt(0) as? ViewGroup
        
        // Search bar (first child in header layout)
        val searchBarCard = headerLayout?.getChildAt(0) as? com.google.android.material.card.MaterialCardView
        searchBarCard?.let { card ->
            // Set search bar background
            val searchBg = if (themeManager.isDarkTheme) {
                Color.parseColor("#1E293B") // Darker background
            } else {
                Color.parseColor("#F4F7FB") // Light gray
            }
            card.setCardBackgroundColor(searchBg)
            
            // Update search icon and text
            val searchLayout = card.getChildAt(0) as? ViewGroup
            searchLayout?.let { layout ->
                for (i in 0 until layout.childCount) {
                    when (val child = layout.getChildAt(i)) {
                        is ImageView -> child.setColorFilter(textSecondary)
                        is TextView -> child.setTextColor(textSecondary)
                    }
                }
            }
        }
        
        // Notification bell (second child)
        binding.notificationBell?.setColorFilter(textSecondary)
        
        // Profile section (third child - LinearLayout)
        binding.profileName.setTextColor(textPrimary)
        binding.profileTitle.setTextColor(textSecondary)
        binding.profileImage?.setColorFilter(textSecondary)
        
        // Profile dropdown arrow
        val profileLayout = headerLayout?.getChildAt(2) as? ViewGroup
        profileLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is ImageView && i == layout.childCount - 1) {
                    child.setColorFilter(textSecondary)
                }
            }
        }
        
        // ============= PATIENTS CARD =============
        binding.patientsCard.setCardBackgroundColor(cardColor)
        
        // Patients header and "View All" button
        val patientsLayout = binding.patientsCard.getChildAt(0) as? ViewGroup
        patientsLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is ViewGroup) {
                    for (j in 0 until child.childCount) {
                        when (val grandChild = child.getChildAt(j)) {
                            is TextView -> {
                                if (grandChild.id == binding.viewAllPatients?.id) {
                                    grandChild.setTextColor(Color.parseColor("#10B981")) // Keep green
                                } else if (grandChild.text.contains("Patients")) {
                                    grandChild.setTextColor(textPrimary)
                                } else {
                                    grandChild.setTextColor(textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Empty state
        val emptyIcon = binding.emptyStateLayout.getChildAt(0) as? ImageView
        emptyIcon?.setColorFilter(textSecondary)
        
        for (i in 0 until binding.emptyStateLayout.childCount) {
            val child = binding.emptyStateLayout.getChildAt(i)
            if (child is TextView) {
                if (i == 1) {
                    child.setTextColor(textPrimary) // "No Patients Yet" title
                } else {
                    child.setTextColor(textSecondary) // Description
                }
            }
        }
        
        // ============= CALENDAR WIDGET =============
        val calendarWidget = binding.widgetsColumn.getChildAt(0) as? com.google.android.material.card.MaterialCardView
        calendarWidget?.let { widget ->
            widget.setCardBackgroundColor(cardColor)
            
            val calendarLayout = widget.getChildAt(0) as? ViewGroup
            calendarLayout?.let { layout ->
                // Update header
                val calendarHeader = layout.getChildAt(0) as? ViewGroup
                calendarHeader?.let { header ->
                    for (i in 0 until header.childCount) {
                        when (val child = header.getChildAt(i)) {
                            is ImageView -> child.setColorFilter(textPrimary)
                            is TextView -> {
                                if (child.text.contains("Calendar")) {
                                    child.setTextColor(textPrimary)
                                } else {
                                    child.setTextColor(Color.parseColor("#10B981")) // "Open →"
                                }
                            }
                        }
                    }
                }
                
                // Update CalendarView
                val calendarView = layout.getChildAt(1) as? android.widget.CalendarView
                calendarView?.let { calendar ->
                    if (themeManager.isDarkTheme) {
                        calendar.setBackgroundColor(cardColor)
                    } else {
                        calendar.setBackgroundColor(Color.WHITE)
                    }
                }
            }
        }
        
        // ============= REPORTS WIDGET =============
        val reportsWidget = binding.widgetsColumn.getChildAt(1) as? com.google.android.material.card.MaterialCardView
        reportsWidget?.let { widget ->
            widget.setCardBackgroundColor(cardColor)
            
            val reportsLayout = widget.getChildAt(0) as? ViewGroup
            reportsLayout?.let { layout ->
                // Update header
                val reportsHeader = layout.getChildAt(0) as? ViewGroup
                reportsHeader?.let { header ->
                    for (i in 0 until header.childCount) {
                        when (val child = header.getChildAt(i)) {
                            is ImageView -> child.setColorFilter(textPrimary)
                            is TextView -> {
                                if (child.text.contains("Reports")) {
                                    child.setTextColor(textPrimary)
                                } else {
                                    child.setTextColor(Color.parseColor("#10B981")) // "View All →"
                                }
                            }
                        }
                    }
                }
                
                // Update report rows
                val reportsContent = layout.getChildAt(1) as? ViewGroup
                reportsContent?.let { content ->
                    for (i in 0 until content.childCount) {
                        val reportRow = content.getChildAt(i) as? ViewGroup
                        reportRow?.let { row ->
                            for (j in 0 until row.childCount) {
                                val child = row.getChildAt(j)
                                if (child is TextView) {
                                    child.setTextColor(textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // RecyclerView background (for patient list items)
        binding.recyclerViewPatients.setBackgroundColor(Color.TRANSPARENT)
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
