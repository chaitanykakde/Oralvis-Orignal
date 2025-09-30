package com.oralvis.oralviscamera

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.database.Session

class SessionListAdapter(
    private val onClick: (Session) -> Unit
) : ListAdapter<Session, SessionListAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Session>() {
        override fun areItemsTheSame(oldItem: Session, newItem: Session): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Session, newItem: Session): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sessionName: TextView = itemView.findViewById(R.id.sessionName)
        private val sessionTime: TextView = itemView.findViewById(R.id.sessionTime)
        private val sessionMediaCount: TextView = itemView.findViewById(R.id.sessionMediaCount)
        private val sessionDuration: TextView = itemView.findViewById(R.id.sessionDuration)
        
        fun bind(item: Session) {
            sessionName.text = item.displayName ?: "Session ${item.sessionId.takeLast(8)}"
            sessionTime.text = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(item.createdAt)
            sessionMediaCount.text = "0 images • 0 videos" // TODO: Get actual count
            sessionDuration.text = "2 min" // TODO: Calculate actual duration
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
