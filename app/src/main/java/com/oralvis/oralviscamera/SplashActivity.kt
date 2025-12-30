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
            val loginManager = LoginManager(this)

            // Check if user is logged in
            val intent = if (loginManager.isLoggedIn()) {
                // User is logged in → go to main app.
                Intent(this, MainActivity::class.java)
            } else {
                // User not logged in → go to login screen.
                Intent(this, LoginActivity::class.java)
            }

            startActivity(intent)
            finish()
        }
    }
}
