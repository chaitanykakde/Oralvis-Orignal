package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.oralvis.oralviscamera.api.ApiClient
import com.oralvis.oralviscamera.api.LoginRequest
import com.oralvis.oralviscamera.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Login Activity - UI-level gating only.
 * 
 * Rules:
 * - Do NOT clear clinicId on login failure
 * - Do NOT exit app on login failure
 * - Do NOT block clinic registration
 * - Login is independent of ClinicId
 */
class LoginActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityLoginBinding
    private lateinit var loginManager: LoginManager
    private lateinit var logCollector: LogCollector
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Adjust window for keyboard to ensure scrollable content
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        loginManager = LoginManager(this)
        logCollector = LogCollector(this)
        
        setupUI()
    }
    
    private fun setupUI() {
        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        binding.btnShareLogs.setOnClickListener {
            shareLogs()
        }
        
        // Hide error text initially
        binding.errorText.visibility = View.GONE
    }
    
    private fun performLogin() {
        val clientId = binding.inputClientId.text.toString().trim()
        val password = binding.inputPassword.text.toString().trim()
        
        // Validate non-empty fields
        if (clientId.isEmpty()) {
            binding.inputClientIdLayout.error = "Client ID is required"
            return
        }
        
        if (password.isEmpty()) {
            binding.inputPasswordLayout.error = "Password is required"
            return
        }
        
        // Clear previous errors
        binding.inputClientIdLayout.error = null
        binding.inputPasswordLayout.error = null
        binding.errorText.visibility = View.GONE
        
        // Show progress
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val request = LoginRequest(clientId, password)
                val response = ApiClient.loginApiService.login(request)
                
                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!
                    
                    if (loginResponse.success) {
                        // Save login state with the user-entered Client ID
                        // Use the Client ID that the user entered as the global client ID for syncing
                        loginManager.saveLoginSuccess(clientId)

                        // Sync patients from cloud after successful login
                        performPatientSync()

                        // Navigate to MainActivity
                        navigateToMain()
                    } else {
                        // Show backend error message
                        val errorMessage = loginResponse.message ?: "Login failed"
                        showError(errorMessage)
                    }
                } else {
                    // Handle HTTP error
                    val errorBody = try {
                        response.errorBody()?.string() ?: "Login failed"
                    } catch (e: Exception) {
                        "Login failed: ${response.message()}"
                    }
                    showError(errorBody)
                }
            } catch (e: Exception) {
                showError("Error: ${e.message ?: "Network error"}")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }
    
    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
        // Do NOT clear clinicId
        // Do NOT exit app
    }
    
    private fun navigateToMain() {
        // After login, go to PatientSelectionActivity first so user picks a patient
        // before entering the camera screen.
        val selectionIntent = Intent(this, PatientSelectionActivity::class.java)

        // If there was a pending intent (e.g., USB device attachment that triggered the auth gate),
        // pass it along so PatientSelectionActivity → MainActivity can handle it
        val pendingIntent = intent.getParcelableExtra<Intent>("pending_intent")
        if (pendingIntent != null) {
            android.util.Log.d("LoginActivity", "Forwarding pending intent via PatientSelectionActivity: ${pendingIntent.action}")
            selectionIntent.putExtra("pending_usb_intent", pendingIntent)
        }

        startActivity(selectionIntent)
        finish()
    }

    private fun performPatientSync() {
        // Perform patient sync in background after login
        // This is fire-and-forget - no UI blocking, silent operation
        lifecycleScope.launch {
            try {
                val result = PatientSyncManager.syncPatients(this@LoginActivity)
                if (result.success) {
                    android.util.Log.d("LoginActivity", "Patient sync successful: ${result.patientCount} patients")
                } else {
                    android.util.Log.w("LoginActivity", "Patient sync failed: ${result.error}")
                    // Don't show error to user - sync will retry on next app start or manual refresh
                }
            } catch (e: Exception) {
                android.util.Log.e("LoginActivity", "Exception during patient sync", e)
            }
        }
    }

    private fun shareLogs() {
        // Show progress indicator
        binding.btnShareLogs.isEnabled = false
        binding.btnShareLogs.text = "Collecting Logs..."
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                Log.d("LoginActivity", "Starting full log collection process")

                // First run a test to see what's available
                logCollector.testLogCollection()

                val zipFile = logCollector.collectAndZipLogs()

                if (zipFile != null && zipFile.exists()) {
                    Log.d("LoginActivity", "Log collection successful: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
                    val sizeKB = zipFile.length() / 1024
                    Toast.makeText(this@LoginActivity, "Full logs collected successfully (${sizeKB} KB)!", Toast.LENGTH_LONG).show()

                    // Share the ZIP file
                    logCollector.shareLogZip(zipFile)
                } else {
                    Log.e("LoginActivity", "Log collection failed - no ZIP file created")
                    Toast.makeText(this@LoginActivity, "Failed to collect logs. Please try again.", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e("LoginActivity", "Exception during log collection", e)
                Toast.makeText(this@LoginActivity, "Error collecting logs: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // Reset UI state
                binding.btnShareLogs.isEnabled = true
                binding.btnShareLogs.text = "Share Full Logs"
                binding.progressBar.visibility = View.GONE
            }
        }
    }
}

