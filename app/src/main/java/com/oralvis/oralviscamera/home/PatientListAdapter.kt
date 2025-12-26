package com.oralvis.oralviscamera.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.database.Patient
import com.oralvis.oralviscamera.databinding.ItemPatientRowBinding

class PatientListAdapter(
    private val onPatientSelected: (Patient) -> Unit
) : ListAdapter<Patient, PatientListAdapter.PatientViewHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<Patient>() {
        override fun areItemsTheSame(oldItem: Patient, newItem: Patient): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Patient, newItem: Patient): Boolean =
            oldItem == newItem
    }

    inner class PatientViewHolder(
        private val binding: ItemPatientRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(patient: Patient) {
            binding.patientName.text = patient.displayName.ifBlank { "Unnamed patient" }
            binding.patientSubtitle.text = buildString {
                if (!patient.diagnosis.isNullOrBlank()) {
                    append(patient.diagnosis)
                    append(" • ")
                }
                append(patient.age?.let { "$it yrs" } ?: "New")
            }
            // Removed unwanted fields: patientProblem, patientTime, patientStatus, btnDetails
            binding.patientProblem.visibility = android.view.View.GONE
            binding.patientTime.visibility = android.view.View.GONE
            binding.patientStatus.visibility = android.view.View.GONE
            binding.btnDetails.visibility = android.view.View.GONE
            binding.root.setOnClickListener { onPatientSelected(patient) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPatientRowBinding.inflate(inflater, parent, false)
        return PatientViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PatientViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

