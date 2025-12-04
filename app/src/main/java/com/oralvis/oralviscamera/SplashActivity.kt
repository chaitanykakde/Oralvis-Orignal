package com.oralvis.oralviscamera

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Handler(Looper.getMainLooper()).post {
            val clinicManager = ClinicManager(this)

            val intent = if (clinicManager.hasClinicId()) {
                // Clinic is registered, go to Home screen
                Intent(this, HomeActivity::class.java)
            } else {
                // First launch, go to Clinic Registration
                Intent(this, ClinicRegistrationActivity::class.java)
            }

            startActivity(intent)
            finish()
        }
    }
}
