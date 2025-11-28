package com.oralvis.oralviscamera

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.Patient
import com.oralvis.oralviscamera.databinding.ActivityAddPatientBinding
import kotlinx.coroutines.launch

class AddPatientActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddPatientBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAddPatientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupNav()
        binding.btnSavePatient.setOnClickListener {
            savePatient()
        }
    }

    private fun setupNav() {
        binding.navHome.setOnClickListener {
            finish()
        }
        binding.navCamera.setOnClickListener {
            startActivity(MainActivity.createIntent(this))
        }
        binding.navPatients.setOnClickListener {
            // already on patient form
        }
    }

    private fun savePatient() {
        val firstName = binding.inputFirstName.textValue()
        val lastName = binding.inputLastName.textValue()
        if (firstName.isBlank()) {
            Toast.makeText(this, "Please enter first name", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val dao = MediaDatabase.getDatabase(this@AddPatientActivity).patientDao()
            val patient = Patient(
                code = binding.inputPatientId.textValue(),
                firstName = firstName,
                lastName = lastName,
                title = binding.inputTitle.textValue(),
                gender = binding.inputGender.textValue(),
                age = null,
                isPregnant = binding.inputPregnant.textValue().equals("yes", true),
                diagnosis = binding.inputDiagnosis.textValue(),
                appointmentTime = null,
                checkInStatus = null,
                phone = binding.inputMobile.textValue(),
                email = binding.inputEmail.textValue(),
                otp = binding.inputOtp.textValue(),
                mobile = binding.inputMobile.textValue(),
                dob = binding.inputDob.textValue(),
                addressLine1 = binding.inputAddress1.textValue(),
                addressLine2 = binding.inputAddress2.textValue(),
                area = binding.inputArea.textValue(),
                city = binding.inputCity.textValue(),
                pincode = binding.inputPincode.textValue()
            )
            dao.insert(patient)
            Toast.makeText(this@AddPatientActivity, "Patient saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun TextInputEditText?.textValue(): String = this?.text?.toString()?.trim().orEmpty()

    companion object {
        fun createIntent(context: android.content.Context) =
            android.content.Intent(context, AddPatientActivity::class.java)
    }
}

