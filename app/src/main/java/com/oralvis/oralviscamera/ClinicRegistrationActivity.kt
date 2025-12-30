package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import android.util.Log
import com.oralvis.oralviscamera.api.ApiClient
import com.oralvis.oralviscamera.api.ClinicRegistrationRequest
import com.oralvis.oralviscamera.core.identity.ClinicIdentityManager
import com.oralvis.oralviscamera.databinding.ActivityClinicRegistrationBinding
import kotlinx.coroutines.launch

class ClinicRegistrationActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityClinicRegistrationBinding
    private lateinit var clinicManager: ClinicManager
    private var pendingClinicIdToPersist: String? = null
    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                Log.d("ClinicRegistration", "SAF folder selected: $uri")
                ClinicIdentityManager.persistFolderSelection(this, uri)
                pendingClinicIdToPersist?.let {
                    ClinicIdentityManager.setClinicId(this, it)
                    Toast.makeText(
                        this,
                        "Clinic ID stored securely for reinstall.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Log.w("ClinicRegistration", "Folder selection cancelled; clinicId may not persist.")
                Toast.makeText(
                    this,
                    "Folder selection skipped. Clinic ID may not persist after reinstall.",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingClinicIdToPersist = null
            goToMain()
        }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivityClinicRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        clinicManager = ClinicManager(this)
        
        setupUI()
    }
    
    private fun setupUI() {
        binding.btnRegister.setOnClickListener {
            registerClinic()
        }
    }
    
    private fun registerClinic() {
        val clinicName = binding.inputClinicName.text.toString().trim()
        
        if (clinicName.isEmpty()) {
            binding.inputClinicName.error = "Clinic name is required"
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        binding.btnRegister.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val request = ClinicRegistrationRequest(clinicName)
                val response = ApiClient.apiService.registerClinic(
                    ApiClient.API_CLINIC_REGISTRATION_ENDPOINT,
                    request
                )
                
                if (response.isSuccessful && response.body() != null) {
                    val clinicId = response.body()!!.clinicId
                    clinicManager.saveClinicId(clinicId)
                    clinicManager.saveClinicName(clinicName)
                    persistClinicIdWithSaf(clinicId)
                } else {
                    Toast.makeText(
                        this@ClinicRegistrationActivity,
                        "Registration failed: ${response.message()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ClinicRegistrationActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnRegister.isEnabled = true
            }
        }
    }

    private fun persistClinicIdWithSaf(clinicId: String) {
        if (ClinicIdentityManager.needsFolderSelection(this)) {
            Log.d("ClinicRegistration", "Requesting SAF folder to persist clinicId.")
            pendingClinicIdToPersist = clinicId
            folderPickerLauncher.launch(ClinicIdentityManager.buildFolderSelectionIntent())
        } else {
            ClinicIdentityManager.setClinicId(this, clinicId)
            Toast.makeText(
                this,
                "Clinic ID stored securely for reinstall.",
                Toast.LENGTH_SHORT
            ).show()
            goToMain()
        }
    }

    private fun goToMain() {
        // After successful clinic registration, navigate to login screen.
        // Do NOT go directly to MainActivity.
        val intent = Intent(this@ClinicRegistrationActivity, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }
}

