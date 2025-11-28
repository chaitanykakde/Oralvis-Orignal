package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
        
        setupRecycler()
        setupActions()
        observeSessions()
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
}
