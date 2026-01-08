package com.oralvis.oralviscamera.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.provider.MediaStore
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
import kotlinx.coroutines.*
import java.io.File
import java.text.DecimalFormat

class MediaAdapter(
    private val onItemClick: (MediaRecord) -> Unit,
    private val onDeleteClick: (MediaRecord) -> Unit
) : ListAdapter<MediaRecord, MediaAdapter.MediaViewHolder>(MediaDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        android.util.Log.d("MediaAdapter", "Creating ViewHolder for position: $viewType")
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = getItem(position)
        android.util.Log.d("MediaAdapter", "Binding ViewHolder at position: $position, item: ${item.fileName}")
        holder.bind(item)
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
            android.util.Log.d("MediaAdapter", "Binding media: ${mediaRecord.fileName}, path: ${mediaRecord.filePath}")
            
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
            
            // Set file size
            fileSizeText.text = getFileSize(mediaRecord.filePath)

            // Load thumbnail
            loadThumbnail(mediaRecord.filePath, mediaRecord.mediaType)

            itemView.setOnClickListener {
                android.util.Log.d("MediaAdapter", "Item clicked: ${mediaRecord.fileName}")
                onItemClick(mediaRecord)
            }

            deleteButton.setOnClickListener {
                android.util.Log.d("MediaAdapter", "Delete clicked: ${mediaRecord.fileName}")
                deleteMedia(mediaRecord)
            }
        }

        private fun loadThumbnail(filePath: String, mediaType: String) {
            // Set placeholder immediately to prevent UI blocking
            imageView.setImageResource(
                if (mediaType == "Video") android.R.drawable.ic_media_play
                else android.R.drawable.ic_menu_camera
            )

            // Load thumbnail asynchronously
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    android.util.Log.d("MediaAdapter", "Loading thumbnail for: $filePath, type: $mediaType")
                    val file = File(filePath)
                    if (file.exists()) {
                        android.util.Log.d("MediaAdapter", "File exists, size: ${file.length()} bytes")

                        val bitmap = when (mediaType) {
                            "Image" -> loadImageThumbnail(filePath)
                            "Video" -> loadVideoThumbnail(filePath)
                            else -> null
                        }

                        if (bitmap != null) {
                            android.util.Log.d("MediaAdapter", "Thumbnail loaded successfully: ${bitmap.width}x${bitmap.height}")
                            withContext(Dispatchers.Main) {
                                imageView.setImageBitmap(bitmap)
                            }
                        } else {
                            android.util.Log.w("MediaAdapter", "Failed to load thumbnail")
                        }
                    } else {
                        android.util.Log.w("MediaAdapter", "File does not exist: $filePath - STATE INCONSISTENCY DETECTED")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MediaAdapter", "Error loading thumbnail: ${e.message}")
                }
            }
        }
        
        private fun loadImageThumbnail(filePath: String): Bitmap? {
            return try {
                // Load image with reduced size for better performance
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4 // Reduce size by 4x for thumbnails
                }
                BitmapFactory.decodeFile(filePath, options)
            } catch (e: Exception) {
                android.util.Log.e("MediaAdapter", "Error loading image thumbnail: ${e.message}")
                null
            }
        }
        
        private fun loadVideoThumbnail(filePath: String): Bitmap? {
            return try {
                // Use ThumbnailUtils to create video thumbnail
                ThumbnailUtils.createVideoThumbnail(filePath, MediaStore.Video.Thumbnails.MINI_KIND)
            } catch (e: Exception) {
                android.util.Log.e("MediaAdapter", "Error loading video thumbnail: ${e.message}")
                null
            }
        }
        
        private fun getFileSize(filePath: String): String {
            return try {
                val file = File(filePath)
                if (file.exists()) {
                    val bytes = file.length()
                    val df = DecimalFormat("#.##")
                    when {
                        bytes < 1024 -> "$bytes B"
                        bytes < 1024 * 1024 -> "${df.format(bytes / 1024.0)} KB"
                        else -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
                    }
                } else {
                    "0 B"
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaAdapter", "Error getting file size: ${e.message}")
                "Unknown"
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
