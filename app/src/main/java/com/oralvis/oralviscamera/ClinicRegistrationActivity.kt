package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.oralvis.oralviscamera.api.ApiClient
import com.oralvis.oralviscamera.api.ClinicRegistrationRequest
import com.oralvis.oralviscamera.databinding.ActivityClinicRegistrationBinding
import kotlinx.coroutines.launch

class ClinicRegistrationActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityClinicRegistrationBinding
    private lateinit var clinicManager: ClinicManager
    
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

                    // Navigate to Home screen after successful registration
                    val intent = Intent(this@ClinicRegistrationActivity, HomeActivity::class.java)
                    startActivity(intent)
                    finish()
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
}

