package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.api.ApiClient
import com.oralvis.oralviscamera.api.PatientDto
import com.oralvis.oralviscamera.databinding.ActivityPatientDashboardBinding
import com.oralvis.oralviscamera.databinding.ItemPatientBinding
import com.oralvis.oralviscamera.database.MediaDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PatientDashboardActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityPatientDashboardBinding
    private lateinit var loginManager: LoginManager
    private var selectedPatient: PatientDto? = null
    private lateinit var patientsAdapter: PatientsAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivityPatientDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        LocalPatientIdManager.initialize(this)
        loginManager = LoginManager(this)
        
        // Initialize adapter
        patientsAdapter = PatientsAdapter { patient ->
            selectedPatient = patient
            patientsAdapter.setSelectedPatient(patient)
        }
        
        setupUI()
        loadPatients()
    }
    
    private fun setupUI() {
        // New Patient Section
        binding.btnCreateAndStart.setOnClickListener {
            createAndStartSession()
        }
        
        // Existing Patients Section
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchPatients(s.toString())
            }
        })
        
        binding.recyclerViewPatients.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewPatients.adapter = patientsAdapter
        
        binding.btnContinueWithSelected.setOnClickListener {
            continueWithSelected()
        }

        // Logout: clear login and go back to login screen
        binding.btnLogout.setOnClickListener {
            loginManager.clearLogin()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }
    
    private fun createAndStartSession() {
        val name = binding.inputPatientName.text.toString().trim()
        val ageText = binding.inputAge.text.toString().trim()
        val phoneNumber = binding.inputPhoneNumber.text.toString().trim()
        
        // Validation
        if (name.isEmpty()) {
            binding.inputPatientName.error = "Patient name is required"
            return
        }
        if (ageText.isEmpty()) {
            binding.inputAge.error = "Age is required"
            return
        }
        if (phoneNumber.isEmpty()) {
            binding.inputPhoneNumber.error = "Phone number is required"
            return
        }
        
        val age = try {
            ageText.toInt()
        } catch (e: NumberFormatException) {
            binding.inputAge.error = "Invalid age"
            return
        }
        
        // Generate Global Patient ID
        val globalPatientId = PatientIdGenerator.generateGlobalPatientId(name, age, phoneNumber)
        
        // Create PatientDto
        val patientDto = PatientDto(
            patientId = globalPatientId,
            name = name,
            age = age,
            phoneNumber = phoneNumber
        )
        
        // Upsert to backend
        binding.btnCreateAndStart.isEnabled = false
        binding.progressNewPatient.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val clientId = loginManager.getClientId()
                if (clientId == null) {
                    Toast.makeText(
                        this@PatientDashboardActivity,
                        "Client ID not found. Please login again.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val response = ApiClient.apiService.upsertPatient(
                    ApiClient.API_PATIENT_SYNC_ENDPOINT,
                    clientId,
                    patientDto
                )
                
                if (response.isSuccessful && response.body() != null) {
                    // Navigate to Camera screen with patient data
                    navigateToCamera(globalPatientId, clientId)
                } else {
                    Toast.makeText(
                        this@PatientDashboardActivity,
                        "Failed to create patient: ${response.message()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@PatientDashboardActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnCreateAndStart.isEnabled = true
                binding.progressNewPatient.visibility = View.GONE
            }
        }
    }
    
    private fun loadPatients() {
        lifecycleScope.launch {
            try {
                val clientId = loginManager.getClientId()
                if (clientId == null) {
                    return@launch
                }
                
                val response = ApiClient.apiService.searchPatients(
                    ApiClient.API_PATIENT_SYNC_ENDPOINT,
                    clientId,
                    null
                )
                
                if (response.isSuccessful && response.body() != null) {
                    patientsAdapter.submitList(response.body()!!)
                }
            } catch (e: Exception) {
                // Silent fail - just show empty list
            }
        }
    }
    
    private fun searchPatients(query: String) {
        lifecycleScope.launch {
            try {
                val clientId = loginManager.getClientId()
                if (clientId == null) {
                    return@launch
                }
                
                // Add small delay to avoid too many API calls
                delay(300)
                
                val response = ApiClient.apiService.searchPatients(
                    ApiClient.API_PATIENT_SYNC_ENDPOINT,
                    clientId,
                    if (query.isBlank()) null else query
                )
                
                if (response.isSuccessful && response.body() != null) {
                    patientsAdapter.submitList(response.body()!!)
                }
            } catch (e: Exception) {
                // Silent fail
            }
        }
    }
    
    private fun continueWithSelected() {
        val patient = selectedPatient
        if (patient == null) {
            Toast.makeText(this, "Please select a patient", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clientId = loginManager.getClientId()
        if (clientId == null) {
            Toast.makeText(this, "Client ID not found. Please login.", Toast.LENGTH_SHORT).show()
            return
        }
        
        navigateToCamera(patient.patientId, clientId)
    }
    
    private fun navigateToCamera(globalPatientId: String, clientId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("GLOBAL_PATIENT_ID", globalPatientId)
            putExtra("CLINIC_ID", clientId) // Using Client ID from login
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }
    
    // Adapter for patient list
    private class PatientsAdapter(
        private val onPatientSelected: (PatientDto) -> Unit
    ) : RecyclerView.Adapter<PatientsAdapter.PatientViewHolder>() {
        
        private var patients: List<PatientDto> = emptyList()
        private var selectedPatient: PatientDto? = null
        
        fun submitList(newList: List<PatientDto>) {
            patients = newList
            notifyDataSetChanged()
        }
        
        fun setSelectedPatient(patient: PatientDto) {
            val previousSelected = selectedPatient
            selectedPatient = patient
            previousSelected?.let { notifyItemChanged(patients.indexOf(it)) }
            patient.let { notifyItemChanged(patients.indexOf(it)) }
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientViewHolder {
            val binding = ItemPatientBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PatientViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: PatientViewHolder, position: Int) {
            holder.bind(patients[position], patients[position] == selectedPatient)
        }
        
        override fun getItemCount(): Int = patients.size
        
        inner class PatientViewHolder(
            private val binding: ItemPatientBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            
            fun bind(patient: PatientDto, isSelected: Boolean) {
                binding.txtPatientName.text = patient.name
                
                // Try to find database patient by code (global ID) to get local ID
                // For now, show global ID - local ID will be assigned when patient is saved to database
                binding.txtPatientId.text = "ID: ${patient.patientId}"
                
                // Try to look up local ID asynchronously
                val context = binding.root.context
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val database = MediaDatabase.getDatabase(context)
                        val dbPatient = database.patientDao().getPatientByCode(patient.patientId)
                        if (dbPatient != null) {
                            val localId = LocalPatientIdManager.getLocalId(dbPatient.id)
                            withContext(Dispatchers.Main) {
                                binding.txtPatientId.text = "ID: $localId"
                            }
                        }
                    } catch (e: Exception) {
                        // Keep showing global ID if lookup fails
                    }
                }
                
                binding.txtPhoneNumber.text = patient.phoneNumber
                
                // Highlight selected patient
                binding.root.setBackgroundColor(
                    if (isSelected) {
                        binding.root.context.getColor(R.color.purple_500)
                    } else {
                        binding.root.context.getColor(android.R.color.transparent)
                    }
                )
                
                binding.root.setOnClickListener {
                    onPatientSelected(patient)
                }
            }
        }
    }
}

