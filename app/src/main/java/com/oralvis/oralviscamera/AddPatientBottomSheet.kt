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

        // Generate and set auto-generated Patient ID
        val patientId = generatePatientId()
        binding.inputPatientId.setText(patientId)
        binding.inputPatientId.isEnabled = false

        // Save button click listener
        binding.btnSavePatient.setOnClickListener {
            savePatient(patientId)
        }
    }

    private fun generatePatientId(): String {
        // Generate a unique ID like "PID-1024"
        val randomNum = (1000..9999).random()
        return "PID-$randomNum"
    }

    private fun savePatient(patientId: String) {
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

        lifecycleScope.launch {
            val dao = MediaDatabase.getDatabase(requireContext()).patientDao()
            val patient = Patient(
                code = patientId,
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
            dao.insert(patient)
            Toast.makeText(requireContext(), "Patient saved successfully", Toast.LENGTH_SHORT).show()
            
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

