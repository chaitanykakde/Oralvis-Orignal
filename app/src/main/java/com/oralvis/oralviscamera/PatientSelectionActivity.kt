package com.oralvis.oralviscamera

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
 * Full-screen patient selection activity shown BEFORE MainActivity.
 * Flow: SplashActivity → (login check) → PatientSelectionActivity → MainActivity
 *
 * Reuses the same layout and logic as PatientSessionDialogFragment but as a
 * standalone Activity. The user must select or create a patient before proceeding
 * to the camera screen.
 */
class PatientSelectionActivity : AppCompatActivity() {

    private lateinit var binding: DialogPatientSessionBinding
    private lateinit var patientDao: com.oralvis.oralviscamera.database.PatientDao
    private lateinit var adapter: PatientsAdapter
    private var allPatients: List<Patient> = emptyList()
    private var selectedPatient: Patient? = null

    companion object {
        private const val REQUEST_ALL_APP_PERMISSIONS = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = DialogPatientSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize dependencies
        LocalPatientIdManager.initialize(this)
        GlobalPatientManager.initialize(this)
        val db = MediaDatabase.getDatabase(this)
        patientDao = db.patientDao()

        setupRecycler()
        observePatients()
        setupForm()
        setupSearch()
        setupButtons()

        // Ask ALL app permissions here so MainActivity never shows permission dialogs (camera/USB flow stays clean)
        checkAllAppPermissions()
    }

    /**
     * All runtime permissions: camera, microphone, and storage/media.
     * Requested on patient selection so MainActivity only checks and never requests.
     */
    private fun getAllAppPermissions(): Array<String> {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += android.Manifest.permission.READ_MEDIA_IMAGES
            permissions += android.Manifest.permission.READ_MEDIA_VIDEO
        } else {
            permissions += android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permissions += android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        return permissions.toTypedArray()
    }

    private fun checkAllAppPermissions() {
        val required = getAllAppPermissions()
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        val needsRationale = missing.any {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (needsRationale) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Permissions needed")
                .setMessage("This app needs camera, microphone, and storage access to capture and save photos and videos. Please allow these permissions.")
                .setPositiveButton("Allow") { _, _ ->
                    ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_ALL_APP_PERMISSIONS)
                }
                .setNegativeButton("Not now") { _, _ -> }
                .show()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_ALL_APP_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_ALL_APP_PERMISSIONS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Some permissions were denied. You can enable them later in app settings.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecycler() {
        adapter = PatientsAdapter(
            onSelected = { patient ->
                selectedPatient = patient
                // Show the continue button more prominently when patient selected
                binding.btnContinueWithSelected.isEnabled = true
            }
        )
        binding.recyclerPatients.layoutManager = LinearLayoutManager(this)
        binding.recyclerPatients.adapter = adapter

        // Refresh button syncs patient list
        binding.btnRefreshPatients.setOnClickListener {
            refreshPatientList()
        }
    }

    private fun observePatients() {
        lifecycleScope.launch {
            patientDao.observePatients().collectLatest { list ->
                allPatients = list
                adapter.submitList(list)
            }
        }
    }

    private fun refreshPatientList() {
        lifecycleScope.launch {
            try {
                binding.btnRefreshPatients.isEnabled = false
                binding.btnRefreshPatients.text = "Syncing..."

                val result = PatientSyncManager.syncPatients(this@PatientSelectionActivity)
                if (result.success) {
                    Toast.makeText(
                        this@PatientSelectionActivity,
                        "Synced ${result.patientCount} patients",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@PatientSelectionActivity,
                        "Sync failed: ${result.error ?: "Unknown error"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PatientSelectionActivity, "Sync failed", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnRefreshPatients.isEnabled = true
                binding.btnRefreshPatients.text = "Refresh"
            }
        }
    }

    private fun setupForm() {
        // Fields start empty; ID is generated on save
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
        // Hide close button — this is a full activity, not a dismissible dialog.
        // The user MUST select a patient to proceed.
        binding.btnClose.visibility = View.GONE

        binding.btnCreatePatient.setOnClickListener {
            createPatient()
        }

        binding.btnContinueWithSelected.setOnClickListener {
            val patient = selectedPatient
            if (patient == null) {
                Toast.makeText(this, "Please select a patient", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            proceedWithPatient(patient)
        }
    }

    /**
     * Set selected patient globally and navigate to MainActivity.
     */
    private fun proceedWithPatient(patient: Patient) {
        // Set patient globally so MainActivity picks it up
        GlobalPatientManager.setCurrentPatient(this, patient)

        val mainIntent = Intent(this, MainActivity::class.java)
        mainIntent.putExtra("patient_id", patient.id)
        mainIntent.putExtra("patient_code", patient.code)

        // Forward any pending USB intent from login flow
        val pendingIntent = intent.getParcelableExtra<Intent>("pending_usb_intent")
        if (pendingIntent != null) {
            mainIntent.putExtra("pending_usb_intent", pendingIntent)
        }

        startActivity(mainIntent)
        finish()
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
        val globalPatientId = PatientIdGenerator.generateGlobalPatientId(fullName, age, phone)

        val nameParts = fullName.split(" ", limit = 2)
        val firstName = nameParts[0]
        val lastName = if (nameParts.size > 1) nameParts[1] else ""

        lifecycleScope.launch {
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

            val insertedPatient = withContext(Dispatchers.IO) {
                val existingPatient = patientDao.getPatientByCode(globalPatientId)
                if (existingPatient != null) {
                    existingPatient
                } else {
                    patientDao.insert(patient)
                    patientDao.getPatientByCode(globalPatientId)
                }
            }

            // Cloud upsert (non-blocking for local save)
            try {
                val clientId = LoginManager(this@PatientSelectionActivity).getClientId()
                if (clientId != null) {
                    val dto = PatientDto(
                        patientId = globalPatientId,
                        name = fullName,
                        age = age,
                        phoneNumber = phone
                    )
                    val response = ApiClient.apiService.upsertPatient(clientId, dto)
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@PatientSelectionActivity,
                            "Patient saved locally, cloud sync failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@PatientSelectionActivity,
                    "Patient saved locally, cloud error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // Auto-select newly created patient and proceed to camera
            if (insertedPatient != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PatientSelectionActivity, "Patient created", Toast.LENGTH_SHORT).show()
                    proceedWithPatient(insertedPatient)
                }
            }
        }
    }

    override fun onBackPressed() {
        // Prevent going back without selecting a patient — user must pick one
        Toast.makeText(this, "Please select a patient to continue", Toast.LENGTH_SHORT).show()
    }

    // ─── Adapter (same as PatientSessionDialogFragment) ───

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
                LayoutInflater.from(parent.context), parent, false
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
                val localId = LocalPatientIdManager.getLocalId(patient.id)
                binding.txtPatientId.text = "ID: $localId"
                binding.txtPhoneNumber.text = patient.phone ?: ""

                binding.root.setBackgroundColor(
                    if (selected) {
                        binding.root.context.getColor(R.color.purple_500)
                    } else {
                        binding.root.context.getColor(android.R.color.transparent)
                    }
                )

                binding.imgCloudSync.visibility = View.GONE
                binding.imgDeletePatient.visibility = View.GONE
                binding.txtUnsyncedCount.visibility = View.GONE
            }
        }
    }
}
