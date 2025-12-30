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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Adjust window for keyboard to ensure scrollable content
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        loginManager = LoginManager(this)
        
        setupUI()
    }
    
    private fun setupUI() {
        binding.btnLogin.setOnClickListener {
            performLogin()
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
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}

