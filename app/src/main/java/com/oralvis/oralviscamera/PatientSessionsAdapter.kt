package com.oralvis.oralviscamera

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.database.Session
import java.text.SimpleDateFormat
import java.util.*

class PatientSessionsAdapter(
    private val sessions: List<Session>,
    private val onItemClick: (Session) -> Unit
) : RecyclerView.Adapter<PatientSessionsAdapter.SessionViewHolder>() {
    
    inner class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtSessionTitle: TextView = view.findViewById(R.id.txtSessionTitle)
        val txtSessionDate: TextView = view.findViewById(R.id.txtSessionDate)
        val txtMediaCount: TextView = view.findViewById(R.id.txtMediaCount)
        val imgSessionIcon: ImageView = view.findViewById(R.id.imgSessionIcon)
        
        init {
            view.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(sessions[position])
                }
            }
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_patient_session, parent, false)
        return SessionViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = sessions[position]
        
        holder.txtSessionTitle.text = session.displayName ?: "Session ${position + 1}"
        
        val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
        holder.txtSessionDate.text = dateFormat.format(session.createdAt)
        
        holder.txtMediaCount.text = "${session.mediaCount} items"
    }
    
    override fun getItemCount(): Int = sessions.size
}

