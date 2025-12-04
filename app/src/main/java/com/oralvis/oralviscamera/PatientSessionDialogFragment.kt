package com.oralvis.oralviscamera

import android.content.Context
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.oralvis.oralviscamera.api.ApiClient
import com.oralvis.oralviscamera.api.PatientDto
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.Patient
import com.oralvis.oralviscamera.databinding.DialogPatientSessionBinding
import com.oralvis.oralviscamera.databinding.ItemPatientBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Centered dialog (~70% of screen) that shows:
 * - New Patient form on the left
 * - Existing Patients list on the right
 *
 * Used from Home (manage patients) and Camera (choose patient + start session).
 */
class PatientSessionDialogFragment : DialogFragment() {

    private var _binding: DialogPatientSessionBinding? = null
    private val binding get() = _binding!!

    private lateinit var patientDao: com.oralvis.oralviscamera.database.PatientDao
    private lateinit var adapter: PatientsAdapter
    private var allPatients: List<Patient> = emptyList()
    private var selectedPatient: Patient? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPatientSessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Make the dialog window almost full-screen so the dark background area is minimal
        dialog?.window?.let { window ->
            val metrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(metrics)
            window.setLayout(metrics.widthPixels, metrics.heightPixels)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        val db = MediaDatabase.getDatabase(context)
        patientDao = db.patientDao()

        setupRecycler()
        observePatients()
        setupForm()
        setupSearch()
        setupButtons()
    }

    private fun setupRecycler() {
        adapter = PatientsAdapter { patient ->
            selectedPatient = patient
        }
        binding.recyclerPatients.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPatients.adapter = adapter
    }

    private fun observePatients() {
        lifecycleScope.launch {
            patientDao.observePatients().collectLatest { list ->
                allPatients = list
                adapter.submitList(list)
            }
        }
    }

    private fun setupForm() {
        // Nothing special here, just leave fields empty; ID is generated when saving
    }

    private fun setupSearch() {
        val searchEdit = binding.inputSearch as TextInputEditText
        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                val filtered = if (query.isEmpty()) {
                    allPatients
                } else {
                    allPatients.filter { it.displayName.contains(query, ignoreCase = true) }
                }
                adapter.submitList(filtered)
            }
        })
    }

    private fun setupButtons() {
        binding.btnCreatePatient.setOnClickListener {
            createPatient()
        }

        binding.btnContinueWithSelected.setOnClickListener {
            val patient = selectedPatient
            if (patient == null) {
                Toast.makeText(requireContext(), "Please select a patient", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Return selection to host via Fragment Result API
            setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    KEY_PATIENT_ID to patient.id,
                    KEY_PATIENT_CODE to patient.code
                )
            )
            dismiss()
        }
    }

    private fun createPatient() {
        val name = binding.inputPatientName.text?.toString()?.trim().orEmpty()
        val ageText = binding.inputAge.text?.toString()?.trim().orEmpty()
        val phone = binding.inputPhone.text?.toString()?.trim().orEmpty()

        if (name.isBlank()) {
            binding.layoutPatientName.error = "Required"
            return
        } else {
            binding.layoutPatientName.error = null
        }

        if (ageText.isBlank()) {
            binding.layoutAge.error = "Required"
            return
        } else {
            binding.layoutAge.error = null
        }

        if (phone.isBlank()) {
            binding.layoutPhone.error = "Required"
            return
        } else {
            binding.layoutPhone.error = null
        }

        val age = try {
            ageText.toInt()
        } catch (e: NumberFormatException) {
            binding.layoutAge.error = "Invalid age"
            return
        }

        val fullName = name
        val globalPatientId = PatientIdGenerator.generateGlobalPatientId(
            fullName,
            age,
            phone
        )

        // Split name into first/last for Room entity
        val nameParts = fullName.split(" ", limit = 2)
        val firstName = nameParts[0]
        val lastName = if (nameParts.size > 1) nameParts[1] else ""

        lifecycleScope.launch {
            val context = requireContext()
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
                phone = phone,
                email = null,
                otp = null,
                mobile = phone,
                dob = null,
                addressLine1 = null,
                addressLine2 = null,
                area = null,
                city = null,
                pincode = null
            )

            withContext(Dispatchers.IO) {
                patientDao.insert(patient)
            }

            // Cloud upsert, non-blocking for local save
            try {
                val clinicId = ClinicManager(context).getClinicId()
                if (clinicId != null) {
                    val dto = PatientDto(
                        patientId = globalPatientId,
                        name = fullName,
                        age = age,
                        phoneNumber = phone
                    )
                    val response = ApiClient.apiService.upsertPatient(
                        ApiClient.API_PATIENT_SYNC_ENDPOINT,
                        clinicId,
                        dto
                    )
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            context,
                            "Patient saved locally, cloud sync failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Patient saved locally, cloud error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

            Toast.makeText(context, "Patient created", Toast.LENGTH_SHORT).show()

            // Clear form for convenience
            binding.inputPatientName.setText("")
            binding.inputAge.setText("")
            binding.inputPhone.setText("")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class PatientsAdapter(
        private val onSelected: (Patient) -> Unit
    ) : RecyclerView.Adapter<PatientsAdapter.PatientViewHolder>() {

        private var patients: List<Patient> = emptyList()
        private var selectedId: Long? = null

        fun submitList(list: List<Patient>) {
            patients = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientViewHolder {
            val binding = ItemPatientBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PatientViewHolder(binding)
        }

        override fun getItemCount(): Int = patients.size

        override fun onBindViewHolder(holder: PatientViewHolder, position: Int) {
            val patient = patients[position]
            holder.bind(patient, patient.id == selectedId)
            holder.itemView.setOnClickListener {
                selectedId = patient.id
                notifyDataSetChanged()
                onSelected(patient)
            }
        }

        class PatientViewHolder(
            private val binding: ItemPatientBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(patient: Patient, selected: Boolean) {
                binding.txtPatientName.text = patient.displayName
                binding.txtPatientId.text = "ID: ${patient.code}"
                binding.txtPhoneNumber.text = patient.phone ?: ""

                binding.root.setBackgroundColor(
                    if (selected) {
                        binding.root.context.getColor(R.color.purple_500)
                    } else {
                        binding.root.context.getColor(android.R.color.transparent)
                    }
                )
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "patient_session_result"
        const val KEY_PATIENT_ID = "patient_id"
        const val KEY_PATIENT_CODE = "patient_code"

        fun show(context: androidx.fragment.app.FragmentActivity) {
            val fm = context.supportFragmentManager
            val dialog = PatientSessionDialogFragment()
            dialog.show(fm, "PatientSessionDialog")
        }
    }
}


