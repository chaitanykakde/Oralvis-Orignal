package com.oralvis.oralviscamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.Patient
import com.oralvis.oralviscamera.databinding.BottomSheetAddPatientBinding
import com.oralvis.oralviscamera.api.ApiClient
import com.oralvis.oralviscamera.api.PatientDto
import kotlinx.coroutines.launch

class AddPatientBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddPatientBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddPatientBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Patient ID will be generated based on name/age/phone using GlobalPatientId rules
        binding.inputPatientId.isEnabled = false

        // Save button click listener
        binding.btnSavePatient.setOnClickListener {
            savePatient()
        }
    }

    private fun savePatient() {
        val name = binding.inputName.text?.toString()?.trim() ?: ""
        val ageText = binding.inputAge.text?.toString()?.trim() ?: ""
        val mobile = binding.inputMobile.text?.toString()?.trim() ?: ""

        // Validation
        if (name.isBlank()) {
            Toast.makeText(requireContext(), "Please enter patient name", Toast.LENGTH_SHORT).show()
            return
        }

        val age = if (ageText.isNotBlank()) {
            try {
                ageText.toInt()
            } catch (e: NumberFormatException) {
                Toast.makeText(requireContext(), "Please enter a valid age", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            null
        }

        // Split name into first and last name
        val nameParts = name.split(" ", limit = 2)
        val firstName = nameParts[0]
        val lastName = if (nameParts.size > 1) nameParts[1] else ""

        // For GlobalPatientId we need full name and non-null age/phone
        val safeAge = age ?: 0
        val phoneForId = if (mobile.isNotBlank()) mobile else "00000"
        val fullNameForId = name

        val globalPatientId = PatientIdGenerator.generateGlobalPatientId(
            fullNameForId,
            safeAge,
            phoneForId
        )

        // Show generated ID in the read-only field
        binding.inputPatientId.setText(globalPatientId)

        lifecycleScope.launch {
            val context = requireContext()
            val dao = MediaDatabase.getDatabase(context).patientDao()

            val patient = Patient(
                code = globalPatientId,
                firstName = firstName,
                lastName = lastName,
                title = null,
                gender = null,
                age = age,
                isPregnant = false,
                diagnosis = null,
                appointmentTime = null,
                checkInStatus = "IN",
                phone = mobile,
                email = null,
                otp = null,
                mobile = mobile,
                dob = null,
                addressLine1 = null,
                addressLine2 = null,
                area = null,
                city = null,
                pincode = null
            )

            // First save locally
            dao.insert(patient)

            // Then try to sync to cloud (non-blocking for local save)
            try {
                val clientId = LoginManager(context).getClientId()
                if (clientId != null) {
                    val dto = PatientDto(
                        patientId = globalPatientId,
                        name = fullNameForId,
                        age = safeAge,
                        phoneNumber = phoneForId
                    )
                    android.util.Log.d("PatientCreation", "Using PROD API for patient creation")
                val response = ApiClient.apiService.upsertPatient(
                        clientId, // Use Client ID from login
                        dto
                    )
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            context,
                            "Patient saved locally, but cloud sync failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Patient saved locally, but cloud sync error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

            Toast.makeText(context, "Patient saved successfully", Toast.LENGTH_SHORT).show()

            // Notify parent that a patient was added
            parentFragmentManager.setFragmentResult("patient_added", Bundle())

            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddPatientBottomSheet"
    }
}

