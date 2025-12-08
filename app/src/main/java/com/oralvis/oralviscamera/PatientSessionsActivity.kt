package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.oralvis.oralviscamera.databinding.ActivityPatientSessionsBinding
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.Session
import com.oralvis.oralviscamera.database.Patient
import kotlinx.coroutines.launch

class PatientSessionsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityPatientSessionsBinding
    private lateinit var mediaDatabase: MediaDatabase
    private lateinit var sessionsAdapter: PatientSessionsAdapter
    private var patientId: Long = -1
    private var patient: Patient? = null
    private val sessionsList = mutableListOf<Session>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientSessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        LocalPatientIdManager.initialize(this)
        mediaDatabase = MediaDatabase.getInstance(this)
        patientId = intent.getLongExtra("PATIENT_ID", -1)
        
        if (patientId == -1L) {
            Toast.makeText(this, "Invalid patient", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        setupUI()
        loadPatientData()
    }
    
    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Setup RecyclerView
        sessionsAdapter = PatientSessionsAdapter(sessionsList) { session ->
            // Open session detail
            val intent = Intent(this, SessionDetailActivity::class.java)
            intent.putExtra("SESSION_ID", session.id)
            startActivity(intent)
        }
        
        binding.rvSessions.apply {
            layoutManager = LinearLayoutManager(this@PatientSessionsActivity)
            adapter = sessionsAdapter
        }
    }
    
    private fun loadPatientData() {
        lifecycleScope.launch {
            try {
                // Load patient details
                patient = mediaDatabase.patientDao().getPatientById(patientId)
                patient?.let { pat ->
                    // Update UI with patient details
                    binding.txtPatientName.text = pat.displayName
                    val localId = LocalPatientIdManager.getLocalId(pat.id)
                    binding.txtPatientInfo.text = "ID: $localId"
                    
                    // Load sessions for this patient
                    mediaDatabase.sessionDao().getSessionsByPatient(patientId).collect { sessions ->
                        sessionsList.clear()
                        sessionsList.addAll(sessions)
                        sessionsAdapter.notifyDataSetChanged()
                        
                        // Show/hide empty state
                        if (sessionsList.isEmpty()) {
                            binding.rvSessions.visibility = View.GONE
                            binding.txtEmptyState.visibility = View.VISIBLE
                        } else {
                            binding.rvSessions.visibility = View.VISIBLE
                            binding.txtEmptyState.visibility = View.GONE
                            binding.txtSessionCount.text = "${sessionsList.size} sessions"
                        }
                    }
                } ?: run {
                    Toast.makeText(this@PatientSessionsActivity, "Patient not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PatientSessionsActivity, "Error loading patient: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

