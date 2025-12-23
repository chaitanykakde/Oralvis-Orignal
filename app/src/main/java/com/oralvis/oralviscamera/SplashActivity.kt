package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import android.util.Log
import com.oralvis.oralviscamera.core.identity.ClinicIdentityManager

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        proceedAfterStorageCheck()
    }

    private fun proceedAfterStorageCheck() {
        Handler(Looper.getMainLooper()).post {
            val clinicManager = ClinicManager(this)

            // First try to restore an existing clinicId from SAF into local prefs.
            if (!clinicManager.hasClinicId()) {
                val restored = ClinicIdentityManager.tryLoadClinicId(this)
                if (restored != null) {
                    Log.d("SplashActivity", "Restored clinicId from SAF.")
                    clinicManager.saveClinicId(restored)
                    Toast.makeText(
                        this,
                        "Restored clinic ID from secure storage.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.d("SplashActivity", "No clinicId in SAF; will show registration.")
                }
            }

            val intent = if (clinicManager.hasClinicId()) {
                // Clinic already registered (or restored) → go to main app.
                Intent(this, MainActivity::class.java)
            } else {
                // Fresh install or no persisted ID → go to registration.
                Intent(this, ClinicRegistrationActivity::class.java)
            }

            startActivity(intent)
            finish()
        }
    }
}
