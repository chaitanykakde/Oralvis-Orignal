package com.oralvis.oralviscamera.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.database.Patient
import com.oralvis.oralviscamera.databinding.ItemPatientPickerBinding

class PatientPickerAdapter(
    private val onSelected: (Patient) -> Unit
) : RecyclerView.Adapter<PatientPickerAdapter.PickerViewHolder>() {

    private val patients = mutableListOf<Patient>()

    fun submitList(newPatients: List<Patient>) {
        patients.clear()
        patients.addAll(newPatients)
        notifyDataSetChanged()
    }

    inner class PickerViewHolder(
        private val binding: ItemPatientPickerBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(patient: Patient) {
            binding.name.text = patient.displayName
            binding.subtitle.text = patient.diagnosis ?: "General"
            binding.btnSelect.setOnClickListener { onSelected(patient) }
            binding.root.setOnClickListener { onSelected(patient) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickerViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPatientPickerBinding.inflate(inflater, parent, false)
        return PickerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PickerViewHolder, position: Int) {
        holder.bind(patients[position])
    }

    override fun getItemCount(): Int = patients.size
}

