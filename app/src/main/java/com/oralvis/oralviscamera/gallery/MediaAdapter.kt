package com.oralvis.oralviscamera.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.R
import com.oralvis.oralviscamera.database.MediaRecord
import java.io.File

class MediaAdapter(
    private val onItemClick: (MediaRecord) -> Unit,
    private val onDeleteClick: (MediaRecord) -> Unit
) : ListAdapter<MediaRecord, MediaAdapter.MediaViewHolder>(MediaDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageView)
        private val fileNameText: TextView = itemView.findViewById(R.id.fileNameText)
        private val modeText: TextView = itemView.findViewById(R.id.modeText)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)
        private val deleteButton: View = itemView.findViewById(R.id.deleteButton)
        private val mediaTypeIcon: ImageView = itemView.findViewById(R.id.mediaTypeIcon)
        private val fileSizeText: TextView = itemView.findViewById(R.id.fileSizeText)

        fun bind(mediaRecord: MediaRecord) {
            fileNameText.text = mediaRecord.fileName
            modeText.text = mediaRecord.mode
            timeText.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(mediaRecord.captureTime)
            
            // Set media type icon
            mediaTypeIcon.setImageResource(
                if (mediaRecord.mediaType == "Video") 
                    android.R.drawable.ic_media_play 
                else 
                    android.R.drawable.ic_menu_camera
            )
            
            // Set file size (placeholder for now)
            fileSizeText.text = "2.4 MB"

            // Load thumbnail
            loadThumbnail(mediaRecord.filePath)

            itemView.setOnClickListener {
                onItemClick(mediaRecord)
            }

            deleteButton.setOnClickListener {
                deleteMedia(mediaRecord)
            }
        }

        private fun loadThumbnail(filePath: String) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(filePath)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(R.drawable.ic_media_placeholder)
                    }
                } else {
                    imageView.setImageResource(R.drawable.ic_media_placeholder)
                }
            } catch (e: Exception) {
                imageView.setImageResource(R.drawable.ic_media_placeholder)
            }
        }

        private fun deleteMedia(mediaRecord: MediaRecord) {
            // Call the delete callback to handle both file and database deletion
            onDeleteClick(mediaRecord)
        }
    }

    class MediaDiffCallback : DiffUtil.ItemCallback<MediaRecord>() {
        override fun areItemsTheSame(oldItem: MediaRecord, newItem: MediaRecord): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MediaRecord, newItem: MediaRecord): Boolean {
            return oldItem == newItem
        }
    }
}
